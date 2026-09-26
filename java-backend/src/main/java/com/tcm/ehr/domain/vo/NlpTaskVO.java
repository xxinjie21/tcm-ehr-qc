package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * NLP 批量解析任务视图。
 */
@Data
public class NlpTaskVO {

    /** 任务ID */
    private String id;
    /** QUEUED / RUNNING / COMPLETED / CANCELLED / INTERRUPTED / FAILED */
    private String status;
    private int total;
    private int done;
    private int success;
    private int failed;
    private String current;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 失败清单是否被截断（仅保留前 500 条） */
    private boolean failureTruncated;
    /** 失败清单（label=病历标识，reason=原因） */
    private List<Failure> failures = new ArrayList<>();

    @Data
    public static class Failure {
        private String label;
        private String reason;

        public Failure() {
        }

        public Failure(String label, String reason) {
            this.label = label;
            this.reason = reason;
        }
    }
}
