package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 复核任务。
 *
 * <p>超时仅前端高亮提醒，系统不自动变更任务状态。</p>
 */
@Data
public class ReviewTaskVO {

    private String taskId;
    private String recordId;
    /** 待复核 / 已完成 */
    private String status;
    private String issueType;
    private Integer score;
    private LocalDateTime createTime;
    private LocalDateTime deadlineTime;
    /** 是否已超截止时间（计算属性，仅视觉提醒） */
    private boolean overdue;
    /** 已存储的 NLP 结构化数据（对象），供左侧对照 */
    private Object structuredData;
}
