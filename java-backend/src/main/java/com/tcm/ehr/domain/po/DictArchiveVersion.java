package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基线归档版本元信息（{@code dict_archive_version}）。
 *
 * <p><b>永久保留</b>：即使 {@link DictArchiveTerm} 快照因 5 份限额被清理，
 * 这一行仍在 —— 版本号连续性与「某次合并由谁做的」是审计链的依据。</p>
 */
@Data
@TableName("dict_archive_version")
public class DictArchiveVersion {

    @TableId(type = IdType.INPUT)
    private String id;

    /** {@code ""} = 基础层，与组织层独立计数 */
    private String orgId;

    private String type;

    /** 组内递增版本号；{@code (org_id,type,version_no)} 唯一 */
    private Integer versionNo;

    /** 来源提案；管理员直写导入时为 null */
    private String proposalId;

    private LocalDateTime mergeTime;

    private String mergeUserId;

    private String comment;

    /** 该版本的快照是否还在（被 5 份限额清理后为 false）——非持久化，前端展示用 */
    @TableField(exist = false)
    private Boolean snapshotPresent;

    /** 该版本的词条数——非持久化 */
    @TableField(exist = false)
    private Integer termCount;
}
