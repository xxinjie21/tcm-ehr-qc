package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.Map;

/**
 * 人工校正与复核请求（入参，批D·5.1，对应 openapi ReviewDTO）。
 *
 * <p>{@code correctedData} 允许为 null，代表仅裁定不修改数据。</p>
 */
@Data
public class ReviewDTO {

    /** 修正后的 structuredData（缺省=不修改） */
    private Map<String, Object> correctedData;

    /** 复核意见（留痕） */
    private String comment;
}
