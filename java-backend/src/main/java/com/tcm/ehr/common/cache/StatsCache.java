package com.tcm.ehr.common.cache;

import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.common.utils.RedisKeys;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.vo.StatsAllVO;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 统计短 TTL 缓存（性能审查 P2-3 / B1 + 缓存审查方案）。
 *
 * <p><b>两类入口</b>：① {@link #wordFreq} 只缓「/stats/all 的词频分布」，按数据域分片、命中率高；
 * ② {@link #getOrLoad} 是通用入口，供报告（report）/ 看板扩展（extra）/
 * 扣分分布（deduction）等聚合用，<b>版本号走全局段</b>（见该方法）。</p>
 *
 * <p><b>失效用版本号前缀，不用 KEYS</b>（R3-③ / W3）：数据 key 形如
 * {@code tcm:ehr:cache:stats:{scope}:v{n}:...}（词频）或 {@code tcm:ehr:cache:stats:{kind}:v{n}:...}（通用），
 * 失效只把 {@code tcm:ehr:cache:stats:ver:{seg}} 自增。
 * 这同时解决三件事 —— ① 不再有 O(N) 全库扫描 + Redis 单线程阻塞（生产禁用级）；
 * ② key 空间不再靠通配删除清理；③ 「读-回填」竞态自然消失：并发读按旧版本算出的值会写回
 * 旧版本 key，新请求读的是新版本 key，读不到它。</p>
 *
 * <p><b>键空间与过期（R3-①②）</b>：① TTL 带抖动（基准 ± 10s），避免全部 key 同秒过期、
 * 一起回源；② 过滤条件先过白名单，不在白名单内的一律<b>绕过缓存直接现算</b> ——
 * key 由 {@code md5(params)} 决定，任意字符串会让 key 无界增长、命中率趋零；
 * ③ 空结果也缓存（10s），区分「查过且为空」与「没查过」；④ 同一 key 并发未命中时单飞，
 * 只让一个请求现算，其余短暂等待后读缓存，避免热点 key 失效瞬间一起回源。</p>
 *
 * <p><b>Redis 不可用 = 自动降级</b>：读/写缓存任何异常都会被吞掉、直接走 loader 现算 ——
 * 统计页不能因为缓存挂了而连页面都打不开（医疗数据要的是「准」，不是「缓存优先」）。</p>
 *
 * <p><b>SWR（stale-while-revalidate，见 {@link #getOrLoadSwr}）</b>：给「冷算特别贵」的报告用。
 * 值仍存 dataKey（TTL 拉长到基准 ×10），另用 {@code dataKey:fresh} 作新鲜标记
 * （TTL = 基准）。时间到期后值仍在、只是标记没了 ⇒ <b>立即返回旧值 + 后台单飞重算</b>，
 * 那几秒不再挡在用户前面；而写入走版本号前缀（{@link #invalidateForWrite()}）⇒ key 变 ⇒
 * 两把 key 都落空 ⇒ <b>仍阻塞现算新值</b>（正确性优先：绝不把写前的旧数字端出去）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsCache {

    private static final String KEY_PREFIX = RedisKeys.PREFIX + "cache:stats:";
    /** 版本号 key 的固定段：{@code ...:cache:stats:ver:{scope}}，与数据 key 的 {@code {scope}:v{n}} 段不撞名 */
    private static final String VERSION_SEGMENT = "ver:";
    /**
     * 通用 kind（report/extra/overview/deduction）的全局版本段：任一写入都自增它 ⇒ 全部 kind 失效。
     *
     * <p>作用域与「数据实际所属 org」无关，故管理员写入落别组也不会漏失效（见 {@link #getOrLoad}）。</p>
     */
    private static final String GLOBAL_SCOPE = "GLOBAL";
    /** 单飞锁 key 前缀 */
    private static final String LOCK_PREFIX = RedisKeys.PREFIX + "cache:stats:lock:";

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

    /** SWR 新鲜标记 key 的后缀：{@code {dataKey}:fresh}，存在 = 值新鲜 */
    private static final String FRESH_SUFFIX = ":fresh";
    /**
     * SWR 硬过期倍率：dataKey 的 TTL = 基准 ×该值（180s → 30min）。
     *
     * <p>它是「后台重算迟迟不成功」的上界 —— 旧值最多被端出这么久，之后 dataKey 也过期，
     * 回落成阻塞现算（宁可让用户等一次，也不能无限期用旧数）。</p>
     */
    private static final long SWR_HARD_FACTOR = 10L;

    /** 白名单：日期必须是 yyyy-MM-dd；自由文本限字符集与长度；分级限枚举 */
    private static final Pattern DATE_RE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TEXT_RE = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9_\\-（）()]{1,40}");
    /**
     * 分级白名单（2026-10-23）：改为引用 {@link QcScorer} 的常量，不再自维护一份。
     *
     * <p>原先这里独立写死三个中文字面量，与 QcScorer 是两份「事实」—— 若分级取值调整，
     * 缓存白名单会把新值判为非法而直接绕过缓存（静默降低命中率），且不会有人察觉。</p>
     */
    private static final Set<String> GRADES = Set.of(
            QcScorer.GRADE_QUALIFIED, QcScorer.GRADE_PENDING, QcScorer.GRADE_INVALID);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * SWR 后台重算池：1~2 线程、有界队列、守护线程，满则丢弃（DiscardPolicy）。
     *
     * <p>有界而非无界：重算很贵（报告 ~5s），不能因为一次热点集中过期就把任务堆爆内存；
     * 丢弃的只是「这次刷新机会」，下次读还会再触发，不会丢数据。</p>
     */
    private final ExecutorService refreshPool = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            r -> {
                Thread t = new Thread(r, "stats-cache-refresh");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /** 实际提交后台重算的执行器；非 final 仅为测试可注入同步执行器（见 {@link #setRefreshExecutorForTest}） */
    private Executor refreshExecutor = refreshPool;

    /** 本方法专用：缓存读 → 未命中/失败 → loader 现算并回填 */
    public StatsAllVO wordFreq(FiltersDTO filters, Supplier<StatsAllVO> loader) {
        String scope = scope();
        // ① 白名单外的过滤条件不缓存（R3-②）：否则 key 空间无界、命中率趋零
        if (!cacheable(filters)) {
            return loader.get();
        }
        String key = keyOf(scope, currentVersion(scope), filters);
        String json = readQuietly(key);
        if (json != null) {
            if (NULL_SENTINEL.equals(json)) {
                return null;
            }
            StatsAllVO cached = parseQuietly(json, StatsAllVO.class);
            if (cached != null) {
                return cached;
            }
        }
        // ② 单飞（R3-③）：同一 key 失效瞬间只让一个请求现算
        return loadSingleFlight(key, TTL_BASE, loader, StatsAllVO.class);
    }

    /**
     * 通用统计缓存：报告 / 看板扩展 / 指标卡 / 扣分分布等聚合都走这里。
     *
     * <p><b>版本号用全局段</b>（{@link #GLOBAL_SCOPE}）：任一次写入（{@link #invalidateForWrite()}）
     * 都自增它 ⇒ 所有 kind 立即失效。选「全局版本」而非「按 org 失效」，是为了避开
     * 《缓存场景审查与改造方案》§3.6 的缺口 —— 失效取的是<b>请求者</b>视角（管理员 = ALL），
     * 而数据落地的 org 可能不同；全局版本与 org 无关，管理员写入落别组也不会让该组普通用户脏读。
     * 代价：任一组写入会作废其它组的统计缓存 —— 本项目单组织、写入低频，可接受。</p>
     *
     * @param kind      缓存类别（作 key 段），如 extra / report / deduction
     * @param keyParams 参与摘要、决定命中范围的参数；若为 {@link FiltersDTO} 会先过白名单
     *                  （白名单外直接现算、不落 key，防 key 空间无界）
     * @param baseTtl   基准 TTL（实际写入带 ±10s 抖动，避免同秒集中过期）
     * @param loader    未命中时的现算逻辑
     * @param type      缓存值的反序列化目标类型
     */
    public <T> T getOrLoad(String kind, Object keyParams, Duration baseTtl,
                           Supplier<T> loader, Class<T> type) {
        return getOrLoad(kind, keyParams, null, baseTtl, loader, type);
    }

    /**
     * 同 {@link #getOrLoad(String, Object, Duration, Supplier, Class)}，额外用 salt 参与 key。
     *
     * <p>用于「参数一样但结果会因外部版本变化」的场景：报告页的入参（时间范围 / 科室）不变，
     * 但换过词典后数字会变 —— 必须把词典版本作为 salt 拼进 key，否则换词表后仍命中旧缓存。</p>
     *
     * @param salt 附加版本串（如词典版本号）；null / 空白视为不加
     */
    public <T> T getOrLoad(String kind, Object keyParams, String salt, Duration baseTtl,
                           Supplier<T> loader, Class<T> type) {
        if (keyParams instanceof FiltersDTO f && !cacheable(f)) {
            return loader.get();
        }
        String key = cacheKey(kind, keyParams, salt);
        String json = readQuietly(key);
        if (json != null) {
            if (NULL_SENTINEL.equals(json)) {
                return null;
            }
            T cached = parseQuietly(json, type);
            if (cached != null) {
                return cached;
            }
        }
        return loadSingleFlight(key, baseTtl, loader, type);
    }

    /**
     * 同 {@link #getOrLoad(String, Object, String, Duration, Supplier, Class)}，但时间到期后
     * <b>stale-while-revalidate</b>：先秒回旧值，再后台重算。
     *
     * <p>给「冷算特别贵」的接口用（本项目的标准化报告：全量拉数据域 + 每条解析 2 次 JSON，
     * 冷算约 5s，其中 ~3s 是读 110MB JSON 的磁盘 I/O，SQL 侧已无优化空间）。</p>
     *
     * <p><b>语义边界（务必理解）</b>：</p>
     * <ul>
     *   <li><b>时间到期</b>（数据没变，只是缓存老了）→ 秒回旧值 + 后台刷新。用户不必为
     *       「缓存该续期了」这件事等 5s；旧值本身仍然正确。</li>
     *   <li><b>写入</b>（{@link #invalidateForWrite()} 自增版本号）→ key 段变化 → 新 key 落空
     *       → <b>阻塞现算新值</b>。绝不用 SWR 掩盖「数据真的变了」，否则会端出写前的旧数字。</li>
     * </ul>
     *
     * <p><b>loader 必须与请求上下文无关</b>：陈旧分支会在<b>后台线程</b>里调用它，那里没有
     * RequestContextHolder（RequestUtils / RecordFilter 读到的是
     * {@code "unknown"} 且 fail-closed）。调用方需在请求线程先把 orgId / viewAllOrgs 等
     * 捕获进闭包再传进来。</p>
     */
    public <T> T getOrLoadSwr(String kind, Object keyParams, String salt, Duration baseTtl,
                              Supplier<T> loader, Class<T> type) {
        if (keyParams instanceof FiltersDTO f && !cacheable(f)) {
            return loader.get();
        }
        String key = cacheKey(kind, keyParams, salt);
        String json = readQuietly(key);
        if (json != null) {
            boolean sentinel = NULL_SENTINEL.equals(json);
            T cached = sentinel ? null : parseQuietly(json, type);
            if (sentinel || cached != null) {
                if (readQuietly(freshKey(key)) != null) {
                    return cached; // 新鲜：直接命中
                }
                // 陈旧（时间到期，数据未改）：先返回旧值，后台单飞重算，本请求不等
                refreshAsync(key, baseTtl, loader, type);
                return cached;
            }
            // 反序列化失败（如类结构不兼容）→ 视同未命中，落到下面的阻塞现算
        }
        return loadSingleFlight(key, baseTtl, loader, type, true);
    }

    /** 统计缓存 key：{@code {前缀}{kind}:v{全局版本}:{参数摘要}[:{salt}]} */
    private String cacheKey(String kind, Object keyParams, String salt) {
        return KEY_PREFIX + kind + ":v" + currentVersion(GLOBAL_SCOPE) + ":" + digestOf(keyParams)
                + (salt == null || salt.isBlank() ? "" : ":" + salt);
    }

    private static String freshKey(String key) {
        return key + FRESH_SUFFIX;
    }

    /** 使「当前数据域 + ALL + 全局」三个版本号立即失效（任一写入都调它） */
    public void invalidateForWrite() {
        bumpVersion(scope());
        bumpVersion("ALL");
        bumpVersion(GLOBAL_SCOPE);
    }

    // ------------------------------------------------------------ 单飞

    private <T> T loadSingleFlight(String key, Duration baseTtl, Supplier<T> loader, Class<T> type) {
        return loadSingleFlight(key, baseTtl, loader, type, false);
    }

    /** @param swr 命中回填时是否同时写 SWR 新鲜标记（见 {@link #getOrLoadSwr}） */
    private <T> T loadSingleFlight(String key, Duration baseTtl, Supplier<T> loader, Class<T> type,
                                   boolean swr) {
        String lockKey = LOCK_PREFIX + key;
        boolean leader;
        try {
            leader = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL));
        } catch (Exception e) {
            // Redis 不可用：不参与单飞，直接现算（缓存只是加速项，不能因此报错）
            return loader.get();
        }
        if (leader) {
            try {
                return computeAndCache(key, baseTtl, loader, type, swr);
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
        T cached = parseQuietly(json, type);
        return cached != null ? cached : computeAndCache(key, baseTtl, loader, type, swr);
    }

    /**
     * 后台单飞重算（SWR 专用）：不占用请求线程，任何失败都只记日志。
     *
     * <p>复用 {@link #LOCK_PREFIX} 那把锁 ⇒ 多实例 / 并发读只会有一个真重算，
     * 其余（含正在阻塞现算的冷请求）看到锁被占即退出，不重复干活。</p>
     */
    private <T> void refreshAsync(String key, Duration baseTtl, Supplier<T> loader, Class<T> type) {
        try {
            refreshExecutor.execute(() -> {
                String lockKey = LOCK_PREFIX + key;
                boolean leader;
                try {
                    leader = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL));
                } catch (Exception e) {
                    return; // Redis 不可用：本次不刷新，下次读再触发
                }
                if (!leader) {
                    return; // 别的请求/实例正在算，避免重复
                }
                long t0 = System.nanoTime();
                try {
                    computeAndCache(key, baseTtl, loader, type, true);
                    log.debug("[统计缓存] 后台重算完成 {} ms，key={}",
                            (System.nanoTime() - t0) / 1_000_000, key);
                } catch (Exception e) {
                    // 绝不能影响任何请求；旧值留到硬 TTL 到期再回落阻塞现算
                    log.warn("[统计缓存] 后台重算失败（保留旧值，下次读再试）: {}", e.getMessage());
                } finally {
                    deleteQuietly(lockKey);
                }
            });
        } catch (Exception e) {
            // 池满（DiscardPolicy）/ 已关闭：静默跳过，下次读再触发
            log.debug("[统计缓存] 后台重算任务被拒（池满/关闭），下次读再试: {}", e.getMessage());
        }
    }

    private <T> T computeAndCache(String key, Duration baseTtl, Supplier<T> loader, Class<T> type) {
        return computeAndCache(key, baseTtl, loader, type, false);
    }

    private <T> T computeAndCache(String key, Duration baseTtl, Supplier<T> loader, Class<T> type,
                                  boolean swr) {
        T vo = loader.get();
        try {
            if (vo == null) {
                // 空值缓存（R3-②）：区分「查过且为空」与「没查过」，避免同一空查询反复穿透。
                // SWR 下标记与值同寿命：空值不适用「先端旧空值」那套，过期就一起没。
                redis.opsForValue().set(key, NULL_SENTINEL, NULL_TTL);
                if (swr) {
                    redis.opsForValue().set(freshKey(key), "1", NULL_TTL);
                }
            } else {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(vo),
                        swr ? hardTtl(baseTtl) : ttl(baseTtl));
                if (swr) {
                    redis.opsForValue().set(freshKey(key), "1", ttl(baseTtl));
                }
            }
        } catch (Exception e) {
            log.debug("[统计缓存] 写失效，下次现算: {}", e.getMessage());
        }
        return vo;
    }

    /** 带抖动的 TTL（R3-①）：base ± 10s */
    private static Duration ttl(Duration base) {
        int jitter = ThreadLocalRandom.current().nextInt(-TTL_JITTER_SECONDS, TTL_JITTER_SECONDS + 1);
        return base.plusSeconds(jitter);
    }

    /** SWR 硬过期：基准 ×{@link #SWR_HARD_FACTOR}（同样带抖动，避免同秒集中过期） */
    private static Duration hardTtl(Duration base) {
        return ttl(base.multipliedBy(SWR_HARD_FACTOR));
    }

    /** 测试用：替换后台重算执行器（可注入 {@code Runnable::run} 同步执行，让断言确定性） */
    void setRefreshExecutorForTest(Executor executor) {
        this.refreshExecutor = executor;
    }

    @PreDestroy
    void shutdownRefreshPool() {
        refreshPool.shutdown();
    }

    // ------------------------------------------------------------ 读写与版本

    private String readQuietly(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.debug("[统计缓存] 读失效，走现算: {}", e.getMessage());
            return null;
        }
    }

    private <T> T parseQuietly(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
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
     * 失效 = 版本号自增（替代原先的 KEYS + DEL）。
     *
     * <p>版本号 key <b>不设 TTL</b>：它只是一个自增整数，数量上界 = 组织数 + 3（含 ALL /
     * GLOBAL）；若给它设 TTL，过期后版本回落会重新读到旧版本 key 下的残留值，反而制造脏读。</p>
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
        return normalizeScope(org);
    }

    /**
     * orgId → Redis key 作用域段：鉴权哨兵含控制符，不能直接做 key；同一 org 必落同一 token。
     */
    private static String normalizeScope(String org) {
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
        if (!textOk(f.getDepartment())) {
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
        // 词频专用 key：{scope}:v{n}:all:{md5}（按数据域分片，命中率更高；与通用 kind 各走各的版本段）
        return KEY_PREFIX + scope + ":v" + version + ":all:" + digestOf(filters);
    }

    /**
     * 参数 → md5 摘要（决定 key 的命中范围）。
     *
     * <p>摘要口径收敛到 {@link RecordUtil#md5Hex}（《自研组件替换计划》#12）。外层 try 兜的是
     * Jackson 序列化失败，与 md5Hex 内部兜的 NoSuchAlgorithmException 不是一回事，
     * 这里降级为「退空串」（所有同类请求共用一个 key，仍有版本段隔离，可接受）。</p>
     */
    private String digestOf(Object params) {
        try {
            String json = params == null ? "null" : objectMapper.writeValueAsString(params);
            return RecordUtil.md5Hex(json);
        } catch (Exception e) {
            return "";
        }
    }
}
