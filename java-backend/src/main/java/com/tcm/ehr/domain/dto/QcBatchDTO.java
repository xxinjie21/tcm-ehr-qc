package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * 批量质控评分重算请求：可选 filters，缺省 = 全库。
 */
@Data
public class QcBatchDTO {

    private FiltersDTO filters;
}
