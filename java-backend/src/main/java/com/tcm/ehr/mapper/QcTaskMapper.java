package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.QcTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质控批量重算任务 Mapper（§七 L5）。
 *
 * <p>提交互斥原先是这里的 {@code GET_LOCK}/{@code RELEASE_LOCK}，批次 26.2 起统一走
 * {@link DbLockMapper}（{@code DistLock}）：命名锁是<b>连接级</b>的，散在两个 Mapper 里各写一份
 * 就会各自踩「取锁与放锁不在同一物理连接」的坑。本类现在只管 {@code qc_task} 的 CRUD。</p>
 */
@Mapper
public interface QcTaskMapper extends BaseMapper<QcTask> {
}
