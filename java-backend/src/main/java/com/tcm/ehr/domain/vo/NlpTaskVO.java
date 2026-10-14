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
    /** 任务模式：EXTRACT（解析）/ NORMALIZE_ONLY（仅归一）；历史任务为 null 时前端按解析显示 */
    private String mode;
    /** 失败清单是否被截断（仅保留前 500 条） */
    private boolean failureTruncated;
    /** 失败清单（label=病历标识，reason=原因） */
    private List<Failure> failures = new ArrayList<>();

    @Data
    public static class Failure {
        private String label;
        private String reason;
        /** 失败病历 ID：供前端「查看详情」跳转与「仅重试失败」；历史 JSON 无此字段时为 null */
        private String recordId;

        public Failure() {
        }

        public Failure(String label, String reason) {
            this.label = label;
            this.reason = reason;
        }

        public Failure(String label, String reason, String recordId) {
            this.label = label;
            this.reason = reason;
            this.recordId = recordId;
        }
    }
}
