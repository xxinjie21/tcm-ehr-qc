package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.DictionaryVersion;
import org.apache.ibatis.annotations.Mapper;

/** 组织级词典索引版本 Mapper（{@code dictionary_versions}） */
@Mapper
public interface DictionaryVersionMapper extends BaseMapper<DictionaryVersion> {
}
