package com.tcm.ehr.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 跨实例互斥锁（MySQL 命名锁 {@code GET_LOCK}）。
 *
 * <p><b>为什么不用 JVM 锁</b>：{@code synchronized} / {@code ReentrantLock} 在多实例下
 * <b>静默失效</b> —— 不报错、只是不互斥，是最难查的一类 bug（批次 9 已在质控提交
 * 互斥上踩过这个口径）。互斥必须落在所有实例共享的地方。</p>
 *
 * <p><b>为什么不用 Redisson</b>：批次 16 原计划引 Redisson，但它会多一个依赖与一份
 * 运维面（还要额外定 Redis 不可用时的降级）。本项目已有 MySQL，且
 * {@code QcTaskMapper} 早就在用 {@code GET_LOCK}；统一到同一套原语反而更少概念。
 * 若将来引入 Redisson，把这个类换掉即可，调用点不用动。</p>
 *
 * <p><b>锁不承担正确性</b>：它只把并发冲突的概率降下来。真正的正确性仍由
 * DB 唯一键与事务承担（见批次 4 的 {@code uk_records_org_text_hash} 等）。</p>
 */
@Mapper
public interface DbLockMapper {

    /**
     * 取锁。
     *
     * @param name    锁名（按业务 key 划分，不做全局单键）
     * @param seconds 等待秒数
     * @return 1=拿到；0=超时未拿到；NULL=出错
     */
    @Select("SELECT GET_LOCK(#{name}, #{seconds})")
    Integer acquire(@Param("name") String name, @Param("seconds") int seconds);

    /**
     * 释放锁。
     *
     * <p>必须在 {@code finally} 里调：异常时锁不会自动释放，不释放会堵死后续所有请求。</p>
     *
     * @param name 锁名
     * @return 1=已释放；0=并非本连接持有
     */
    @Select("SELECT RELEASE_LOCK(#{name})")
    Integer release(@Param("name") String name);
}
