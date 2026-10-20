package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.CleanTask;
import org.apache.ibatis.annotations.Mapper;

/** 数据清洗任务表 Mapper */
@Mapper
public interface CleanTaskMapper extends BaseMapper<CleanTask> {
}
