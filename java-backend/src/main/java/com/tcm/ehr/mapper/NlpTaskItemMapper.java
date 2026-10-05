package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.NlpTaskItem;
import org.apache.ibatis.annotations.Mapper;

/** 批量解析任务的待处理病历明细 Mapper（批次 16 工作项 3） */
@Mapper
public interface NlpTaskItemMapper extends BaseMapper<NlpTaskItem> {
}
