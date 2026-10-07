package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * 人工复核页概览统计：待复核 / 已完成 / 待复核超期 三个数。
 *
 * <p>由 GET /api/review/tasks/count 一次性返回 —— 替代原先的三次
 * {@code pageSize=1} 列表查询（每个都会附带一次全表排序，见性能审查 P1-5）。</p>
 */
@Data
public class ReviewStatsVO {

    /** 待复核（未超期 + 超期合计） */
    private long pending;
    /** 已完成 */
    private long done;
    /** 待复核中已超期（worklist「只需我处理」） */
    private long overdue;
}