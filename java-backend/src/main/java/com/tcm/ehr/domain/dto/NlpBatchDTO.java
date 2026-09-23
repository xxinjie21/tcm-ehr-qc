package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * NLP 批量解析提交请求（批K·K-a）。
 */
@Data
public class NlpBatchDTO {

    /** 筛选范围：科室 / 时间区间 / 证候 / 分级（与病历查询同一套） */
    private FiltersDTO filters;

    /** 处理条数上限；空或 <=0 表示不限（按筛选范围内全部） */
    private Integer limit;
}
