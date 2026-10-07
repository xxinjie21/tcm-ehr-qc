package com.tcm.ehr.common.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 统计缓存主动失效的静态门面（B1）。
 *
 * <p>为什么用静态而非构造注入：写操作分布在 import / createRecord / updateRecord /
 * deleteRecords / deleteByFilter / clean / review / 批量任务完成共 9 个服务方法里，为它们各扩
 * 一个构造参数会让 11 个测试文件的构造代码一起动（且与缓存只有在“写”时才需要）。这里用
 * 进程内单例的静态委托，写方法只需一行 {@code StatsCacheInvalidator.invalidateStats()}，
 * 不引入构造链改动；单例持有者在 Spring 启动时注入，Redis 不可用时 {@code invalidate} 内部
 * 已降级为「不失效，60s TTL 兜底」。</p>
 */
@Component
public final class StatsCacheInvalidator {

    private static volatile StatsCache statsCache;

    @Autowired
    public StatsCacheInvalidator(StatsCache statsCache) {
        StatsCacheInvalidator.statsCache = statsCache;
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
    }
}