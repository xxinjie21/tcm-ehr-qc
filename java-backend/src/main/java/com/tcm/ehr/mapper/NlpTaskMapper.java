package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.NlpTask;
import org.apache.ibatis.annotations.Mapper;

/** NLP 批量解析任务 Mapper*/
@Mapper
public interface NlpTaskMapper extends BaseMapper<NlpTask> {
}
