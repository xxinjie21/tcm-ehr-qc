package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.DictionaryTerm;
import org.apache.ibatis.annotations.Mapper;

/** 组织级词典词条 Mapper（{@code dictionary_terms}） */
@Mapper
public interface DictionaryTermMapper extends BaseMapper<DictionaryTerm> {
}
