package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.Organization;
import org.apache.ibatis.annotations.Mapper;

/** 课题组 Mapper */
@Mapper
public interface OrgMapper extends BaseMapper<Organization> {
}
