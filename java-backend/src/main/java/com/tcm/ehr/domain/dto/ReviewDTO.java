package com.tcm.ehr.domain.dto;

import lombok.Data;

import jakarta.validation.constraints.Size;
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
    @Size(max = 500, message = "复核意见最长 500 字")
    private String comment;

    /**
     * 读时指纹（批次 25.16）：读详情时从 {@code RawRecordVO.fingerprint} 拿到，提交时原样回传。
     *
     * <p>为空表示不校验（兼容旧客户端）；非空且与服务端当前指纹不一致时，复核提交以 409 拒绝，
     * 避免后提交者静默覆盖先提交者的修正（lost update）。</p>
     */
    private String fingerprint;
}
