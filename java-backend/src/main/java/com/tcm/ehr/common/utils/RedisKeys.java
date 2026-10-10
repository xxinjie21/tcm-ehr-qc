package com.tcm.ehr.common.utils;

/**
 * 本项目 Redis key 的统一前缀（P2-23）。
 *
 * <p>改造前是两套命名并存：令牌版本 / 登录失败计数 / 提交锁 / 词典重建锁 / 启动探活
 * 用 {@code tcm:*}，而看板词频缓存用 {@code cache:stats:*} —— 后者既没有项目前缀、
 * 又恰好是键空间最容易失控的那一处（数据 key 由筛选条件的哈希决定）。统一成
 * {@code tcm:ehr:} 之后，{@code INFO keyspace} 里本项目的 key 一眼可辨，
 * 也不会与同一个 Redis 库里的其它应用撞名。</p>
 *
 * <p>换库（{@code spring.data.redis.database} 由 {@code 0} 改 {@code 1}）与本次前缀统一
 * 一并落地：{@code StringRedisTemplate} 与 {@code RedissonClient} 都读
 * {@code spring.data.redis.*}（见 {@code RedissonConfig}），一处配置改动即同时迁移
 * 缓存与分布式锁，不需要在 yml 里维护第二份地址配置。</p>
 *
 * <p><b>所有 key 都必须以 {@link #PREFIX} 开头</b>：新增 key 请写
 * {@code RedisKeys.PREFIX + "业务段:..."}，不要再写字面量前缀。</p>
 */
public final class RedisKeys {

    /** 统一前缀 */
    public static final String PREFIX = "tcm:ehr:";

    private RedisKeys() {
    }
}
