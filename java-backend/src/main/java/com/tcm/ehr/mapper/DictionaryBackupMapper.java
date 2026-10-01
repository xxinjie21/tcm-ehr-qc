package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.DictionaryBackup;
import org.apache.ibatis.annotations.Mapper;

/** 组织级词典备份 Mapper（{@code dictionary_backups}） */
@Mapper
public interface DictionaryBackupMapper extends BaseMapper<DictionaryBackup> {
}
