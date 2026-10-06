package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.QcTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质控批量重算任务 Mapper（§七 L5）。
 *
 * <p>提交互斥原先是这里的 {@code GET_LOCK}/{@code RELEASE_LOCK}；批次 26.2 收口到
 * {@code DistLock}，批次 16.1 再换成 Redisson（{@link com.tcm.ehr.common.config.RedissonConfig}）——
 * 连接级命名锁绑在一条物理连接上，取锁/放锁一跨连接就漏锁。本类现在只管 {@code qc_task} 的 CRUD。</p>
 */
@Mapper
public interface QcTaskMapper extends BaseMapper<QcTask> {
}
