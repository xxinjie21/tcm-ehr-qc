package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.QcRule;
import org.apache.ibatis.annotations.Mapper;

/** 组织级质控规则。 */
@Mapper
public interface QcRuleMapper extends BaseMapper<QcRule> {
}
