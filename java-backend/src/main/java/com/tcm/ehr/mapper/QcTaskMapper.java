package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.QcTask;
import org.apache.ibatis.annotations.Mapper;

/** 质控批量重算任务 Mapper（§七 L5） */
@Mapper
public interface QcTaskMapper extends BaseMapper<QcTask> {
}
