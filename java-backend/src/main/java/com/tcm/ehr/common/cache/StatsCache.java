package com.tcm.ehr.common.cache;

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
import java.util.function.Supplier;

/**
 * 看板词频统计的短 TTL 缓存（性能审查 P2-3 / B1）。
 *
 * <p><b>只缓存「/stats/all 的词频分布」这一小块</b>：结果集小（疾病/证候/症状/处方各 Top10）、
 * 计算贵（4 万行 × 词频 JSON 解析）。总览指标（overview）不走这里 —— A5 已把计数下推到
 * SQL 聚合（毫秒级），且它依赖 grade/governed，词频不依赖 —— 每次现算反而更贴合实时性。</p>
 *
 * <p><b>一致性双保险</b>：60s 短 TTL 兜底 + 写操作主动失效（{@link StatsCacheInvalidator}，
 * 导入 / 单条增删改 / 清洗 / 质控重算 / 批量任务完成 / 复核修正后失效对应作用域）。</p>
 *
 * <p><b>失效用版本号前缀，不用 {@code KEYS}</b>（R3-③ / W3）：数据 key 形如
 * {@code cache:stats:{scope}:v{n}:all:{md5}}，失效只把 {@code cache:stats:ver:{scope}} 自增。
 * 这同时解决三件事 —— ① 不再有 O(N) 全库扫描 + Redis 单线程阻塞（生产禁用级）；
 * ② key 空间不再靠通配删除清理；③ 「读-回填」竞态自然消失：并发读按旧版本算出的值会写回
 * 旧版本 key，新请求读的是新版本 key，读不到它。</p>
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
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 词频分布：缓存读 → 未命中/失败 → loader 现算并回填 */
    public StatsAllVO wordFreq(FiltersDTO filters, Supplier<StatsAllVO> loader) {
        String scope = scope();
        String key = keyOf(scope, currentVersion(scope), filters);
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, StatsAllVO.class);
            }
        } catch (Exception e) {
            log.debug("[统计缓存] 读失效，走现算: {}", e.getMessage());
        }
        StatsAllVO vo = loader.get();
        if (vo != null) {
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(vo), TTL);
            } catch (Exception e) {
                log.debug("[统计缓存] 写失效，下次现算: {}", e.getMessage());
            }
        }
        return vo;
    }

    /** 使「当前数据域 + ALL（管理员视角）」两个作用域的统计缓存立即失效 */
    public void invalidateForWrite() {
        bumpVersion(scope());
        bumpVersion("ALL");
    }

    /** 读作用域当前版本号；键不存在或 Redis 不可用一律按 0（数据 key 仍有 60s TTL 兜底） */
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
            log.warn("[统计缓存] 版本号自增失败，60s TTL 兜底: {}", e.getMessage());
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