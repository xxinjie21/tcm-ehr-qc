package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 基线归档术语快照（{@code dict_archive_term}）。
 *
 * <p>每组每 type 只保留最近 5 份；更早的快照被删除，但对应的
 * {@link DictArchiveVersion} 元信息永久保留。回滚只能基于<b>快照仍在</b>的版本。</p>
 */
@Data
@TableName("dict_archive_term")
public class DictArchiveTerm {

    @TableId(type = IdType.INPUT)
    private String id;

    private String versionId;

    private String standardTerm;

    private String code;

    private String source;

    /** 别名数组（JSON 字符串） */
    private String aliases;
}
