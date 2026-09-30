package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.UserLlmConfig;
import org.apache.ibatis.annotations.Mapper;

/** 用户私有 LLM 配置。 */
@Mapper
public interface UserLlmConfigMapper extends BaseMapper<UserLlmConfig> {
}
