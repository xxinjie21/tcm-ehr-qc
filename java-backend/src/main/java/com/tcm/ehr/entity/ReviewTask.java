package com.tcm.ehr.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReviewTask {

    private String id;
    private String recordId;
    /** 状态：pending/completed */
    private String status;
    /** 问题类型（缺失字段/逻辑冲突/评分不达标） */
    private String issueType;
    /** 当前评分 */
    private Integer score;
    private LocalDateTime createTime;
    /** 复核截止时间（创建时间+7个工作日） */
    private LocalDateTime deadlineTime;
}
