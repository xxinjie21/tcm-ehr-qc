package com.tcm.ehr.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * NLP 批量解析任务视图。公共字段见 {@link AbstractTaskVO}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NlpTaskVO extends AbstractTaskVO {

    private int total;
    private int done;
    private int success;
    private int failed;
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
