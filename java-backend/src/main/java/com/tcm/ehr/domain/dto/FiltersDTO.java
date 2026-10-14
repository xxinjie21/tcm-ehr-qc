package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据范围过滤条件：科室 / 时间区间 / 证候 / 分级。
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
    /**
     * 仅按这些病历 ID 过滤（可空）。用于「仅重试失败」——把任务失败的那批记录 ID 作为范围重跑。
     *
     * <p>为空/缺省时不影响任何现有调用；非空时在数据域之上再收窄到这些 ID。</p>
     */
    private List<String> ids;
}
