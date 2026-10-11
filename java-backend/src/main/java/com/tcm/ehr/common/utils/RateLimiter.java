package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.RateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 接口限流 / 防刷（P2-31 / R12）。
 *
 * <p><b>为什么用滑动窗口而不是「{@code INCR} + {@code EXPIRE}」</b>：固定窗口在窗口边界会出现
 * 「两倍突发」—— 第 59 秒打满一轮、第 61 秒又打满一轮，两秒内实际放过 2×limit。
 * 登录爆破与「每次调 LLM 都有成本」的 AI 建议都不能容忍这个缺口。滑动窗口把「计数 + 判定 +
 * 记录」放进同一段 Lua，在 Redis 端单线程执行，天然没有边界突发。</p>
 *
 * <p><b>降级方向：fail-open（Redis 不可用即放行）</b>。限流是「防滥用」的增强项，
 * 不是业务正确性的前提；把它做成 fail-closed 会让一次 Redis 抖动把登录、导入、AI 全打成 429 ——
 * 可用性代价远大于它挡掉的那点滥用。这与 {@code DistLock} 的 fail-closed 相反，
 * 因为锁保护的是「并发写入会写出坏数据」，而限流保护的是「别打太狠」。</p>
 *
 * <p><b>身份为空即放行</b>：拿不到可归因的身份（如未登录且无用户名）时不计数，
 * 否则所有匿名请求会共用同一个桶，一个人能把所有人挡在门外。</p>
 */
@Slf4j
@Component
public class RateLimiter {

    /**
     * 滑动窗口：清理过期成员 → 取当前计数 → 未达上限则记一条并返回 1。
     *
     * <p>用 ZSET 而不是「计数器 + 首次过期」：ZSET 的 score 是请求时刻，
     * {@code ZREMRANGEBYSCORE} 精确淘汰窗口外的成员，因此窗口是真正滑动的。
     * member 用「毫秒 + 随机数」保证同一毫秒内的多次请求不会互相覆盖（ZSET 同 member 只算一条）。</p>
     */
    private static final String SLIDING_WINDOW =
            "local key = KEYS[1] "
                    + "local now = tonumber(ARGV[1]) "
                    + "local window = tonumber(ARGV[2]) "
                    + "local limit = tonumber(ARGV[3]) "
                    + "local member = ARGV[4] "
                    + "redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window) "
                    + "local n = redis.call('ZCARD', key) "
                    + "if n < limit then "
                    + "  redis.call('ZADD', key, now, member) "
                    + "  redis.call('PEXPIRE', key, window) "
                    + "  return 1 "
                    + "end "
                    + "return 0";

    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT =
            new DefaultRedisScript<>(SLIDING_WINDOW, Long.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 记一次请求并判断是否放行。
     *
     * @param scope    业务域（login / dict-import / ai …），进 key 前缀，便于按域隔离
     * @param identity 归因主体（用户名 / userId）；为空则直接放行（见类注释）
     * @param limit    窗口内允许的次数；{@code <= 0} 视为关闭限流
     * @param window   窗口时长
     * @return true 放行；false 超出窗口上限
     */
    public boolean allow(String scope, String identity, int limit, Duration window) {
        if (identity == null || identity.isBlank() || limit <= 0) {
            return true;
        }
        String key = RedisKeys.PREFIX + "rl:" + scope + ":" + identity;
        long now = System.currentTimeMillis();
        String member = now + "-" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        try {
            Long ok = redis.execute(SLIDING_WINDOW_SCRIPT, List.of(key),
                    String.valueOf(now), String.valueOf(window.toMillis()),
                    String.valueOf(limit), member);
            // 脚本固定返回 1/0；返回 null（客户端异常吞掉）按放行处理
            return ok == null || ok == 1L;
        } catch (Exception e) {
            // 见类注释：fail-open。限流不能成为「Redis 一抖全站 429」的原因。
            log.warn("[限流] Redis 不可用，放行 scope={} id={}: {}", scope, identity, e.getMessage());
            return true;
        }
    }

    /**
     * 与 {@link #allow} 同义，但超限时抛 {@link RateLimitedException}（429）。
     *
     * @param message 超限提示文案（面向用户，需可直接展示）
     */
    public void check(String scope, String identity, int limit, Duration window, String message) {
        if (!allow(scope, identity, limit, window)) {
            log.info("[限流] 拒绝 scope={} id={}（{} 内超过 {} 次）", scope, identity, window, limit);
            throw new RateLimitedException(message);
        }
    }
}
