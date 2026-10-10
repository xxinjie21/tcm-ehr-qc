package com.tcm.ehr.common.cache;

import com.tcm.ehr.common.metrics.Metrics;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.vo.StatsAllVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 看板词频统计的短 TTL 缓存（性能审查 P2-3 / B1）。
 *
 * <p><b>只缓存「/stats/all 的词频分布」这一小块</b>：结果集小（疾病/证候/症状/处方各 Top10）、
 * 计算贵（4 万行 × 词频 JSON 解析）。总览指标（overview）不走这里 —— 计数已下推到
 * SQL 聚合（毫秒级），且它依赖 grade/governed，词频不依赖 —— 每次现算反而更贴合实时性。</p>
 *
 * <p><b>失效用版本号前缀，不用 {@code KEYS}</b>（R3-③ / W3）：数据 key 形如
 * {@code cache:stats:{scope}:v{n}:all:{md5}}，失效只把 {@code cache:stats:ver:{scope}} 自增。
 * 这同时解决三件事 —— ① 不再有 O(N) 全库扫描 + Redis 单线程阻塞（生产禁用级）；
 * ② key 空间不再靠通配删除清理；③ 「读-回填」竞态自然消失：并发读按旧版本算出的值会写回
 * 旧版本 key，新请求读的是新版本 key，读不到它。</p>
 *
 * <p><b>键空间与过期（R3-①②）</b>：① TTL 带抖动（60 ± 10s），避免全部 key 同秒过期、
 * 一起回源；② 过滤条件先过白名单，不在白名单内的一律<b>绕过缓存直接现算</b> ——
 * key 由 {@code md5(filters)} 决定，任意字符串会让 key 无界增长、命中率趋零；
 * ③ 空结果也缓存（10s），区分「查过且为空」与「没查过」；④ 同一 key 并发未命中时单飞，
 * 只让一个请求现算，其余短暂等待后读缓存，避免热点 key 失效瞬间一起回源。</p>
 *
 * <p><b>Redis 不可用 = 自动降级</b>：读/写缓存任何异常都会被吞掉、直接走 loader 现算 ——
 * 统计页不能因为缓存挂了而连页面都打不开（医疗数据要的是「准」，不是「缓存优先」）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsCache {

    static final String KEY_PREFIX = "cache:stats:";
    /** 版本号 key 的固定段：{@code cache:stats:ver:{scope}}，与数据 key 的 {@code {scope}:v{n}} 段不撞名 */
    private static final String VERSION_SEGMENT = "ver:";
    /** 单飞锁 key 前缀 */
    private static final String LOCK_PREFIX = "cache:stats:lock:";

    /** TTL 基准与抖动幅度（R3-①）：固定 60s 会让一批 key 在同一秒集中过期 */
    private static final Duration TTL_BASE = Duration.ofSeconds(60);
    private static final int TTL_JITTER_SECONDS = 10;
    /** 空值缓存 TTL（R3-②） */
    private static final Duration NULL_TTL = Duration.ofSeconds(10);
    /** 单飞锁的存活时间：只覆盖一次 loader 的合理耗时 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    /** 跟随者等待领跑者的时长；等不到就自己算（不做无限等待） */
    private static final long FOLLOWER_WAIT_MS = 100L;
    /**
     * 空值哨兵：JSON 无法表达「无值」，且真实结果一定是对象。
     * 用一个带引号的字面量，与任何真实序列化结果都不会相撞。
     */
    private static final String NULL_SENTINEL = "\"__NULL__\"";

    /** 白名单：日期必须是 yyyy-MM-dd；自由文本限字符集与长度；分级限枚举 */
    private static final Pattern DATE_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TEXT_RE = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9_\\-（）()]{1,40}");
    private static final Set<String> GRADES = Set.of("合格", "待复核", "无效");

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 词频分布：缓存读 → 未命中/失败 → loader 现算并回填 */
    public StatsAllVO wordFreq(FiltersDTO filters, Supplier<StatsAllVO> loader) {
        String scope = scope();
        // ① 白名单外的过滤条件不缓存（R3-②）：否则 key 空间无界、命中率趋零
        if (!cacheable(filters)) {
            Metrics.inc("stats.cache.bypass");
            return loader.get();
        }
        String key = keyOf(scope, currentVersion(scope), filters);
        String json = readQuietly(key);
        if (json != null) {
            if (NULL_SENTINEL.equals(json)) {
                Metrics.inc("stats.cache.hit");
                return null;
            }
            StatsAllVO cached = parseQuietly(json);
            if (cached != null) {
                Metrics.inc("stats.cache.hit");
                return cached;
            }
        }
        Metrics.inc("stats.cache.miss");
        // ② 单飞（R3-③）：同一 key 失效瞬间只让一个请求现算
        return loadSingleFlight(key, loader);
    }

    /** 使「当前数据域 + ALL（管理员视角）」两个作用域的统计缓存立即失效 */
    public void invalidateForWrite() {
        bumpVersion(scope());
        bumpVersion("ALL");
    }

    // ------------------------------------------------------------ 单飞

    private StatsAllVO loadSingleFlight(String key, Supplier<StatsAllVO> loader) {
        String lockKey = LOCK_PREFIX + key;
        boolean leader;
        try {
            leader = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL));
        } catch (Exception e) {
            // Redis 不可用：不参与单飞，直接现算（缓存只是加速项，不能因此报错）
            Metrics.inc("stats.cache.error");
            return loader.get();
        }
        if (leader) {
            try {
                return computeAndCache(key, loader);
            } finally {
                deleteQuietly(lockKey);
            }
        }
        // 跟随者：让出一点时间给领跑者算完，再读一次缓存；仍没有就自己算（不无限等待）
        try {
            Thread.sleep(FOLLOWER_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String json = readQuietly(key);
        if (NULL_SENTINEL.equals(json)) {
            return null;
        }
        StatsAllVO cached = parseQuietly(json);
        return cached != null ? cached : computeAndCache(key, loader);
    }

    private StatsAllVO computeAndCache(String key, Supplier<StatsAllVO> loader) {
        StatsAllVO vo = loader.get();
        try {
            if (vo == null) {
                // 空值缓存（R3-②）：区分「查过且为空」与「没查过」，避免同一空查询反复穿透
                redis.opsForValue().set(key, NULL_SENTINEL, NULL_TTL);
            } else {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(vo), ttl());
            }
        } catch (Exception e) {
            log.debug("[统计缓存] 写失效，下次现算: {}", e.getMessage());
        }
        return vo;
    }

    /** 带抖动的 TTL（R3-①）：60 ± 10s */
    private static Duration ttl() {
        int jitter = ThreadLocalRandom.current().nextInt(-TTL_JITTER_SECONDS, TTL_JITTER_SECONDS + 1);
        return TTL_BASE.plusSeconds(jitter);
    }

    // ------------------------------------------------------------ 读写与版本

    private String readQuietly(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            Metrics.inc("stats.cache.error");
            log.debug("[统计缓存] 读失效，走现算: {}", e.getMessage());
            return null;
        }
    }

    private StatsAllVO parseQuietly(String json) {
        try {
            return objectMapper.readValue(json, StatsAllVO.class);
        } catch (Exception e) {
            log.debug("[统计缓存] 反序列化失败，走现算: {}", e.getMessage());
            return null;
        }
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.debug("[统计缓存] 释放单飞锁失败（锁自带 TTL 兜底）: {}", e.getMessage());
        }
    }

    /** 读作用域当前版本号；键不存在或 Redis 不可用一律按 0（数据 key 仍有 TTL 兜底） */
    private long currentVersion(String scope) {
        try {
            String v = redis.opsForValue().get(versionKey(scope));
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            log.debug("[统计缓存] 读版本号失败，按 0 处理: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * 失效 = 版本号自增（替代原先的 {@code KEYS} + {@code DEL}）。
     *
     * <p>版本号 key <b>不设 TTL</b>：它只是一个自增整数，数量上界 = 组织数 + 2；若给它设 TTL，
     * 过期后版本回落会重新读到旧版本 key 下的残留值，反而制造脏读。</p>
     */
    private void bumpVersion(String scope) {
        try {
            redis.opsForValue().increment(versionKey(scope));
        } catch (Exception e) {
            log.warn("[统计缓存] 版本号自增失败，TTL 兜底: {}", e.getMessage());
        }
    }

    private static String versionKey(String scope) {
        return KEY_PREFIX + VERSION_SEGMENT + scope;
    }

    /** 作用域：管理员「看全部」→ ALL；其余按数据域 orgId（无组=哨兵 → 归一成 NONE） */
    private static String scope() {
        if (RequestUtils.viewAllOrgs()) {
            return "ALL";
        }
        String org = RecordFilter.domainOrgId();
        if (org == null || org.isBlank()) {
            return "NONE";
        }
        // NOAA 哨兵控符不适合做 Redis key，规整成可读 token；同一 org 落同一 key
        return org.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * 过滤条件是否在白名单内（R3-②）。
     *
     * <p>不在白名单 = <b>绕过缓存</b>（照常返回正确结果，只是不落 key），而不是「归一成别的值」——
     * 后者会把两个不同科室映射到同一个 key，直接返回错误数据。</p>
     */
    private static boolean cacheable(FiltersDTO f) {
        if (f == null) {
            return true;
        }
        if (!textOk(f.getDepartment()) || !textOk(f.getPattern())) {
            return false;
        }
        if (f.getGrade() != null && !f.getGrade().isBlank() && !GRADES.contains(f.getGrade())) {
            return false;
        }
        List<String> range = f.getDateRange();
        if (range != null) {
            if (range.size() > 2) {
                return false;
            }
            for (String d : range) {
                if (d != null && !d.isBlank() && !DATE_RE.matcher(d).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean textOk(String s) {
        return s == null || s.isBlank() || TEXT_RE.matcher(s).matches();
    }

    private String keyOf(String scope, long version, FiltersDTO filters) {
        String digest;
        try {
            String json = filters == null ? "null" : objectMapper.writeValueAsString(filters);
            byte[] d = MessageDigest.getInstance("MD5").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            digest = sb.toString();
        } catch (Exception e) {
            // 摘不出哈希就退回空串，所有无筛选请求共用一个 key（仍作用域隔离，可接受）
            digest = "";
        }
        return KEY_PREFIX + scope + ":v" + version + ":all:" + digest;
    }
}
