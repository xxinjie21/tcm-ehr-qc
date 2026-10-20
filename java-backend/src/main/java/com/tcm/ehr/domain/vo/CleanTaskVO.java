package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 数据清洗任务视图（提交 / 轮询进度 / 取消 都用它） */
@Data
public class CleanTaskVO {

    private String id;
    /** QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED */
    private String status;
    private Integer total;
    private Integer done;
    private Integer success;
    private Integer failed;
    /** 是否跳过了术语归一 */
    private Boolean skipNormalize;
    private Integer deduped;
    private Integer repaired;
    private Integer isolated;
    private Integer normalized;
    private LocalDateTime createTime;
    private LocalDateTime finishedAt;
}
