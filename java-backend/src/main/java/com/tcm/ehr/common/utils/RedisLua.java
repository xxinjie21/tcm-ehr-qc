package com.tcm.ehr.common.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/**
 * 「多条命令必须原子完成」的 Redis Lua 脚本工具（P2-21）。
 *
 * <b>为什么必须原子</b>：INCR 与 EXPIRE 是两条独立命令，中间断掉
 * （客户端超时、连接被回收、进程被杀）就会留下一个<b>永不过期</b>的计数 key。
 * 登录失败计数正是这个形状 —— 一旦永久累积，该账号会被无限期锁死，
 * 只能人工清 key，而表面上完全看不出发生过什么。
 *
 * 脚本在 Redis 端单线程执行，两条命令之间不可能插入别的命令，
 * 因此「自增成功但过期没设上」这个中间态不存在。同理，脚本里的读-改-写也不会
 * 与其它客户端的命令交错。
 *
 * 本类当前只有两个使用者：登录失败计数（{@link #incrWithTtlOnFirst}）与
 * JWT 令牌版本号（{@link #incrWithTtl}）。接口限流已改用 Redisson RRateLimiter
 * （《自研组件替换计划》#1），不再经由本类。
 */
public final class RedisLua {

    /**
     * 自增；<b>只在本次是第一次自增时</b>设置过期（毫秒）。
     *
     * 为什么只在 {@code n == 1} 时设 TTL、而不是每次都设：窗口应当从<b>第一次失败</b>
     * 开始滚动。若每次失败都续期，持续爆破会让窗口无限后移，锁定等于形同虚设。
     */
    private static final String INCR_WITH_TTL_ON_FIRST =
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n";

    private static final DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT =
            new DefaultRedisScript<>(INCR_WITH_TTL_ON_FIRST, Long.class);

    /**
     * 自增；**每次**都把过期重置成同一个值。
     *
     * 与 {@link #incrWithTtlOnFirst} 的分工：那个只在第一次自增时设 TTL，
     * 因为登录失败计数的窗口应当从<b>第一次失败</b>开始滚（每次失败都续期会让
     * 持续爆破把锁定无限后移）。而令牌版本号要的是另一条不变式 ——
     * 「版本 key 比此刻最新的令牌活得更久」：每次作废都让该用户的令牌阵容整体换代，
     * 于是每一次作废都得把 TTL 重新推到 25h+，而不是从第一次作废起算。
     */
    private static final String INCR_WITH_TTL =
            "local n = redis.call('INCR', KEYS[1]) "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[1]) "
                    + "return n";

    private static final DefaultRedisScript<Long> INCR_WITH_TTL_RESET_SCRIPT =
            new DefaultRedisScript<>(INCR_WITH_TTL, Long.class);

    private RedisLua() {
    }

    /**
     * 原子地「自增 + 首次设置过期」。
     *
     * @param redis Redis 模板（脚本与参数都按字符串序列化）
     * @param key   计数 key
     * @param ttl   过期时长，仅在首次自增时生效
     * @return 自增后的计数；Redis 未返回值时为 {@code null}
     */
    public static Long incrWithTtlOnFirst(StringRedisTemplate redis, String key, Duration ttl) {
        return redis.execute(INCR_WITH_TTL_SCRIPT, List.of(key), String.valueOf(ttl.toMillis()));
    }

    /**
     * 原子地「自增 + 重置过期」。
     *
     * 给<b>每次都要刷新 TTL</b>的计数器用（当前：JWT 令牌版本号）。
     * 拆成 {@code increment()} + {@code expire()} 两条命令时，自增本身仍是原子的，
     * 失败只可能是「EXPIRE 没发出去」—— 后果是 key 永不过期（泄漏一个 key），
     * 不会是鉴权漏洞；但复用本类能让形状统一，也不需要调用方各自记这条取舍。
     *
     * @param redis Redis 模板（脚本与参数都按字符串序列化）
     * @param key   计数 key
     * @param ttl   过期时长，本次自增后立即生效
     * @return 自增后的计数；Redis 未返回值时为 {@code null}
     */
    public static Long incrWithTtl(StringRedisTemplate redis, String key, Duration ttl) {
        return redis.execute(INCR_WITH_TTL_RESET_SCRIPT, List.of(key), String.valueOf(ttl.toMillis()));
    }
}
