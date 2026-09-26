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
    private String pattern;
    /** 分级：合格/待复核/无效 */
    private String grade;
    private String status;
    private Integer page;

    /**
     * 每页条数上限 200。
     *
     * <p>不设上限时 {@code POST /api/records/search {"pageSize":1000000}} 一次拉全表，
     * 等同绕过导出权限（审查报告 M1）。</p>
     *
     * <p>刻意<b>不</b>在 {@code PaginationInnerInterceptor} 上设全局 {@code setMaxLimit}：
     * 那会把 {@code QcServiceImpl} 内部批处理的 {@code BATCH_PAGE_SIZE=1000} 也截到 500，
     * 而它的循环退出条件是 {@code records.size() < BATCH_PAGE_SIZE} —— 会恒真、
     * 让批量重算与扣分聚合静默只处理前 500 条。限制只加在「用户可传入」的这一处。</p>
     */
    @Max(value = 200, message = "每页最多 200 条")
    private Integer pageSize;
}
