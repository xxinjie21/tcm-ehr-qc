package com.tcm.ehr.common.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 统计缓存主动失效的静态门面（B1）。
 *
 * 为什么用静态而非构造注入：写操作分布在 import / createRecord / updateRecord /
 * deleteRecords / deleteByFilter / clean / review / 批量任务完成共 9 个服务方法里，为它们各扩
 * 一个构造参数会让 11 个测试文件的构造代码一起动（且与缓存只有在“写”时才需要）。这里用
 * 进程内单例的静态委托，写方法只需一行 {@code StatsCacheInvalidator.invalidateStats()}，
 * 不引入构造链改动；单例持有者在 Spring 启动时注入，Redis 不可用时 invalidate 内部
 * 已降级为「不失效，60s TTL 兜底」。
 */
@Component
public final class StatsCacheInvalidator {

    private static volatile StatsCache statsCache;

    /**
     * 写后预热钩子（2026-10-28）：{@link #invalidateStats()} 末尾调用。
     *
     * <p>为什么放在这里而不是给 9 个写方法各加一行：失效与预热必须<b>同源</b> ——
     * 任何一处写了数据却没预热，报告就会在下一个读者那里阻塞现算（回到 2~3s）；
     * 而任何一处只预热不失效，则是拿旧版本号写新值，比不预热更糟。挂在失效这一个口子上，
     * 「失效即可疑、可疑即预热」是结构性成立的，不依赖 9 个调用点各自记得加。</p>
     *
     * <p>钩子本身必须是「快返回」：它跑在写请求线程/批量任务回调里，
     * 真正的重算由 {@code ReportWarmup} 的守护线程池异步做（满了就丢）。
     * 未注册（单测 / 极端装配）时为 null，等同于不预热。</p>
     */
    private static volatile Runnable warmupHook;

    @Autowired
    public StatsCacheInvalidator(StatsCache statsCache) {
        StatsCacheInvalidator.statsCache = statsCache;
    }

    /**
     * 注册写后预热钩子（由 {@code ReportWarmup} 在启动时登记、关闭时注销）。
     *
     * <p>公开只是因为预热器在另一个包（{@code service.impl}）：本方法属于"装配口"，
     * 业务代码不应调用它 —— 想扩大预热范围请改 {@code ReportWarmup}，不要在这里挂第二个钩子。</p>
     *
     * @param hook 钩子；null = 注销
     */
    public static void setWarmupHook(Runnable hook) {
        warmupHook = hook;
    }

    /**
     * 写数据后调用：清空「当前数据域 + ALL」两作用域的统计缓存。
     * 供导入 / 单条新增 / 修改结构化 / 删除 / 按范围删除 / 清洗 / 复核修正 /
     * 质控重算 / 批量任务完成的回调处使用。
     */
    public static void invalidateStats() {
        StatsCache cache = statsCache;
        if (cache == null) {
            return; // 未注入（单测/极端装配）：不失效，60s TTL 兜底
        }
        cache.invalidateForWrite();
        warmUp();
    }

    /**
     * 触发写后预热：钩子自己负责异步化与丢弃策略，这里绝不能阻塞或抛异常 ——
     * 预热失败最多是「下次读者多等一次」，不该让写请求失败。
     */
    private static void warmUp() {
        Runnable hook = warmupHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (Exception e) {
            // 1. 钩子内部已自行吞异常；这里再兜一层，确保写入路径零影响
            org.slf4j.LoggerFactory.getLogger(StatsCacheInvalidator.class)
                    .debug("[统计缓存] 预热钩子异常（忽略）: {}", e.getMessage());
        }
    }
}