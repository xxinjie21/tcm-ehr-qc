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
 * 导入 / 单条增删改 / 清洗 / 质控重算 / 批量任务完成 / 复核修正后清除对应作用域）。</p>
 *
 * <p><b>Redis 不可用 = 自动降级</b>：读/写缓存任何异常都会被吞掉、直接走 loader 现算 ——
 * 统计页不能因为缓存挂了而连页面都打不开（医疗数据要的是「准」，不是「缓存优先」）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsCache {

    static final String KEY_PREFIX = "cache:stats:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 词频分布：缓存读 → 未命中/失败 → loader 现算并回填 */
    public StatsAllVO wordFreq(FiltersDTO filters, Supplier<StatsAllVO> loader) {
        String key = keyOf(filters);
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

    /** 清空「当前数据域 + ALL（管理员视角）」两个作用域的统计缓存 */
    public void invalidateForWrite() {
        clearScope(scope());
        clearScope("ALL");
    }

    private void clearScope(String scope) {
        try {
            var keys = redis.keys(KEY_PREFIX + scope + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("[统计缓存] 失效清理失败，60s TTL 兜底: {}", e.getMessage());
        }
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

    private String keyOf(FiltersDTO filters) {
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
        return KEY_PREFIX + scope() + ":all:" + digest;
    }
}