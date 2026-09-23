package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NLP 批量解析任务（批K·K-a）。对应 {@code nlp_task} 表。
 *
 * <p>进度与失败清单落库，故服务重启后仍可查询；重启时把未完成任务标为 {@code INTERRUPTED}（K-c）。</p>
 */
@Data
@TableName("nlp_task")
public class NlpTask {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String FAILED = "FAILED";

    private String id;
    private String status;
    private Integer total;
    private Integer done;
    private Integer success;
    private Integer failed;
    /** 当前处理的病历标识（登记号 / ID） */
    private String currentLabel;
    /** 筛选范围 JSON（FiltersDTO） */
    private String filtersJson;
    private String createdBy;
    /** 失败清单 JSON 数组（仅存前 500 条） */
    private String failureList;
    private Boolean failureTruncated;
    private LocalDateTime createTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
