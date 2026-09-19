package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据范围过滤条件（批B·4.1 公共）：科室 / 时间区间 / 证候 / 分级。
 * 不设条件 = 全部病历。
 */
@Data
public class FiltersDTO {

    private String department;
    /** [开始日期, 结束日期]（yyyy-MM-dd） */
    private List<String> dateRange;
    private String pattern;
    /** 分级：合格/待复核/无效 */
    private String grade;
}
