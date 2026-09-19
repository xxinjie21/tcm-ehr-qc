package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * 终末质控评分请求（批B·2.3，对应 openapi QcScoreDTO 的超集）。
 * structuredData 与 recordId 至少提供一个：有 recordId 时加载原始列做判空/格式回退。
 */
@Data
public class QcScoreDTO {

    private String recordId;
    private Object structuredData;
    private Object qcResults;
}
