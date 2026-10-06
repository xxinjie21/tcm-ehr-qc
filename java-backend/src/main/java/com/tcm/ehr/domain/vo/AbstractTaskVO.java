package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量任务视图的公共字段（NLP / 质控）。
 *
 * <p>只收敛两处口径完全一致的字段。计数字段（{@code total}/{@code done}/…）在 NLP 侧是原始
 * {@code int}（列表恒为 0）、质控侧是 {@code Integer}（可空），{@code failureTruncated} 同理
 * （{@code boolean} vs {@code Boolean}），失败清单内层字段也不同（{@code label} vs
 * {@code recordId}）—— 这些**不下沉**，避免为「抽基类」而改前端看到的 JSON 形状。</p>
 */
@Data
public class AbstractTaskVO {

    private String id;
    private String status;
    private String current;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
