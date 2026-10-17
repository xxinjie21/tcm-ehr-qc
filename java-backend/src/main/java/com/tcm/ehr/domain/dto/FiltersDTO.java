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
    /**
     * [开始日期, 结束日期]（yyyy-MM-dd），按病历**导入时间**（{@code records.create_time}）过滤。
     *
     * <p>与 {@link #dateRange}（接诊时间）相互独立，可同时生效、取交集；缺省或两端不齐时不生效。</p>
     */
    private List<String> importRange;
    /** 分级：合格/待复核/无效 */
    private String grade;
    /**
     * 仅按这些病历 ID 过滤（可空）。用于「仅重试失败」——把任务失败的那批记录 ID 作为范围重跑。
     *
     * <p>为空/缺省时不影响任何现有调用；非空时在数据域之上再收窄到这些 ID。</p>
     */
    private List<String> ids;

    /**
     * 仅处理「尚未结构化解析」的病历（{@code structured_data IS NULL}）。
     *
     * <p>用于「只解析未解析的」：避免把已解析过的几万条再重抽一遍。缺省/为 false 时不加条件。</p>
     */
    private Boolean onlyUnparsed;
}
