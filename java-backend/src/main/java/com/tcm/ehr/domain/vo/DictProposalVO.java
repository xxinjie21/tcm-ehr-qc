package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 提案列表视图（批次 17） */
@Data
public class DictProposalVO {

    private String id;
    private String orgId;
    private String type;
    private String submitUserId;
    private String status;
    private String auditUserId;
    private String auditComment;
    private LocalDateTime createTime;
    private LocalDateTime auditTime;
    private LocalDateTime purgeAfter;
    /** 快照词条数；快照已被惰性清理后为 0（此时不能再编辑） */
    private Integer termCount;
    /** 快照是否还在：false 表示已清理，仅剩元信息可看 */
    private Boolean snapshotPresent;
}
