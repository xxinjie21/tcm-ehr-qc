package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 组织级词典备份（{@code dictionary_backups}）：导入前把该组织的词条整份存档，用于回滚。
 *
 * <p>{@code snapshot} 是词条数组的 JSON 文本（longtext）。用整份快照而不是
 * 「记录 id + 版本号」：回滚要的是「把当时那份原样放回去」，
 * 中间若有人改过词条，靠 id 回滚只会拿到新内容，不是要回滚的那一版。</p>
 */
@Data
@TableName("dictionary_backups")
public class DictionaryBackup {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属组织；{@code ""} = 基础层（共享） */
    private String orgId;

    /** 术语类型 */
    private String type;

    /** 词条数组的 JSON 快照文本 */
    private String snapshot;

    private String createdBy;

    private java.time.LocalDateTime createTime;
}
