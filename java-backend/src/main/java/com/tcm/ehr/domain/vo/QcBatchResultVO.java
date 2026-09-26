package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量质控评分结果：分级分布 + 失败清单（明细仅前 50 条示例）。
 */
@Data
public class QcBatchResultVO {

    private int total;
    private int qualified;
    private int pendingReview;
    private int invalid;
    private int failed;
    private List<Failure> failureSamples = new ArrayList<>();

    @Data
    public static class Failure {
        private String recordId;
        private String reason;

        public Failure() {
        }

        public Failure(String recordId, String reason) {
            this.recordId = recordId;
            this.reason = reason;
        }
    }
}
