package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.RateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 接口限流 / 防刷（P2-31 / R12）。
 *
 * <b>为什么用 Redisson {@link RRateLimiter} 而不是自己写 Lua</b>：限流是通用能力，
 * Redisson 已提供经过验证的实现，自写 ZSET 滑动窗口只是把同一件事再实现一遍
 * （《自研组件替换计划》#1）。原实现的 key 前缀、fail-open、空身份放行三条对外契约不变。
 *
 * <b>语义已实测为「等价」，不是「令牌桶换滑动窗口」</b>：Redisson 的 acquire 脚本
 * 把每次许可记进 ZSET（score = 时刻），下次取用时先 zrangebyscore 淘汰
 * 「早于 now − interval」的许可并把额度加回，因此<b>任意 interval 内最多 rate 次</b>，
 * 与原实现的滑动窗口同口径。实测（rate=3 / interval=5s，Redisson 3.52.0 + 本机 Redis）：
 * 连打 4 次 → {@code true,true,true,false}；t=3s 再打 → {@code false}（令牌桶此时会放行，
 * 因为按 0.6/s 已补 1.8 个）；t=6s → {@code true}。
 *
 * <b>每次调用都要重设 rate 与 TTL，两件事都不能省</b>（都实测过）：
 * ① trySetRate 内部是 hsetnx，只在配置缺失时写入，
 * <b>不会重置已消耗的额度</b> —— 所以每次调用是幂等的、安全的；
 * 反过来它也<b>不会更新已存在的配置</b>，因此若不设 TTL，改了限额（如 60 → 30）
 * 对老 key 永远不生效，必须手工删 key。② Redisson 只在「key 自身有 TTL」时
 * 才把 TTL 传给内部子 key（脚本里的 {@code if ttl > 0}），而 trySetRate
 * 不设 TTL → 实测 key 的 {@code ttlMs = -1}（永不过期）。原实现每次
 * {@code PEXPIRE key window}，故这里补一次 {@link RRateLimiter#expire(Duration)}
 * 保持同样的「窗口内无请求即回收」语义，否则每个 (scope, identity) 都会在 Redis 里永久留痕。
 *
 * <b>降级方向：fail-open（Redis 不可用即放行）</b>。限流是「防滥用」的增强项，
 * 不是业务正确性的前提；把它做成 fail-closed 会让一次 Redis 抖动把登录、导入、AI 全打成 429 ——
 * 可用性代价远大于它挡掉的那点滥用。这与 DistLock 的 fail-closed 相反，
 * 因为锁保护的是「并发写入会写出坏数据」，而限流保护的是「别打太狠」。
 *
 * <b>身份为空即放行</b>：拿不到可归因的身份（如未登录且无用户名）时不计数，
 * 否则所有匿名请求会共用同一个桶，一个人能把所有人挡在门外。
 */
@Slf4j
@Component
public class RateLimiter {

    private final RedissonClient redisson;

    public RateLimiter(RedissonClient redisson) {
        this.redisson = redisson;
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
        try {
            RRateLimiter limiter = redisson.getRateLimiter(key);
            // 顺序有意：先写配置（缺失才写，不重置额度），再补 TTL，最后取许可。
            // TTL 放在 acquire 之前，acquire 脚本才能读到正的 pttl 并把 TTL 传播给内部子 key。
            limiter.trySetRate(RateType.OVERALL, limit, window);
            limiter.expire(window);
            return limiter.tryAcquire();
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
