package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.QcTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 质控批量重算任务 Mapper（§七 L5） */
@Mapper
public interface QcTaskMapper extends BaseMapper<QcTask> {

    /**
     * 取 DB 互斥锁（MySQL {@code GET_LOCK}）。
     *
     * <p><b>为什么不用 JVM 锁</b>：多实例下 {@code synchronized} / {@code ReentrantLock}
     * 静默失效 —— 不报错、只是不互斥，是最难查的一类 bug。互斥必须落在 DB。</p>
     *
     * @param name    锁名
     * @param seconds 获取超时（秒）；拿不到返回 0
     * @return 1=拿到；0=超时未拿到；NULL=出错
     */
    @Select("SELECT GET_LOCK(#{name}, #{seconds})")
    Integer acquireLock(@Param("name") String name, @Param("seconds") int seconds);

    /**
     * 释放 DB 互斥锁。
     *
     * @param name 锁名
     * @return 1=已释放；0=不是本连接持有的锁
     */
    @Select("SELECT RELEASE_LOCK(#{name})")
    Integer releaseLock(@Param("name") String name);
}
