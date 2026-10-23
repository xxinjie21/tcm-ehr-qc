package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.Max;
import lombok.Data;

import java.util.List;

/**
 * 多条件病历查询请求。
 */
@Data
public class SearchDTO {

    private String registrationNo;
    private String outpatientNo;
    private String gender;
    private String department;
    private String doctorId;
    /** [开始日期, 结束日期]（yyyy-MM-dd） */
    private List<String> dateRange;
    /**
     * [开始日期, 结束日期]（yyyy-MM-dd），按病历**导入时间**（records.create_time）过滤。
     *
     * 与 {@link #dateRange}（接诊时间 visit_time）是两个独立维度，可同时生效、取交集。
     * 用途：刚导入一批数据后，直接筛出「今天导入的」来复核 / 解析 / 清洗，不必按接诊时间去猜。
     * 缺省或两端不齐时不生效（与 dateRange 同一判据）。
     */
    private List<String> importRange;
    /** 分级：合格/待复核/无效 */
    private String grade;
    private String status;
    private Integer page;

    /**
     * 仅处理「尚未结构化解析」的病历（{@code structured_data IS NULL}）。
     *
     * 供「只解析未解析的」的提交前计数（{@code POST /records/count}）与列表复用之。缺省不生效。
     */
    private Boolean onlyUnparsed;

    /**
     * 排序列（B3 用户可排序 · 方案 b）。**值永远只进白名单映射**，绝不能拼进 SQL：
     * 白名单见 RecordFilter.SORT_WHITELIST（score / visit_time / registration_no）。
     * 任意其它值（含注入串）一律回落默认排序，不报错。
     */
    private String sortBy;
    /** 排序方向：asc | desc（小写）。空/非法取该列默认方向。 */
    private String sortOrder;

    /**
     * 每页条数上限 200。
     *
     * 不设上限时 {@code POST /api/records/search {"pageSize":1000000}} 一次拉全表，
     * 等同绕过导出权限（审查报告 M1）。
     *
     * 刻意<b>不</b>在 PaginationInnerInterceptor 上设全局 setMaxLimit：
     * 那会把 QcServiceImpl 内部批处理的 {@code BATCH_PAGE_SIZE=1000} 也截到 500，
     * 而它的循环退出条件是 {@code records.size() < BATCH_PAGE_SIZE} —— 会恒真、
     * 让批量重算与扣分聚合静默只处理前 500 条。限制只加在「用户可传入」的这一处。
     */
    @Max(value = 200, message = "每页最多 200 条")
    private Integer pageSize;
}
