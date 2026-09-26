package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.Map;

/**
 * 人工校正与复核请求。
 *
 * <p>{@code correctedData} 允许为 null，代表仅裁定不修改数据。</p>
 */
@Data
public class ReviewDTO {

    /** 修正后的 structuredData（缺省=不修改） */
    private Map<String, Object> correctedData;

    /**
     * 复核意见。
     *
     * <p>{@code review_tasks} 表没有该列，故由 {@code ReviewController} 并入操作日志的
     * {@code detail} 落库（{@code operation_log.detail} 是 TEXT，容量不限）；
     * 空串与空白视为未填。</p>
     */
    private String comment;
}
