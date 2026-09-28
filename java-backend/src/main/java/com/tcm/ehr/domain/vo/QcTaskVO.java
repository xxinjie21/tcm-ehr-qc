package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 质控批量重算任务视图（§七 L5）。
 *
 * <p>进度接口与列表接口复用同一套字段；分级汇总（qualified / pendingReview / invalid）
 * 直接用 {@link QcBatchResultVO} 的那三个计数，本类不重复定义，避免两处口径漂移。</p>
 */
@Data
public class QcTaskVO {

    private String id;
    private String status;
    private Integer total;
    private Integer done;
    private Integer success;
    private Integer failed;
    /** 合格数 */
    private Integer qualified;
    /** 待复核数 */
    private Integer pendingReview;
    /** 无效数 */
    private Integer invalid;
    /** 当前处理的病历标识 */
    private String current;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Boolean failureTruncated;
    /** 失败清单；列表接口不带（省流量），详情接口带 */
    private List<Failure> failures = new ArrayList<>();

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
