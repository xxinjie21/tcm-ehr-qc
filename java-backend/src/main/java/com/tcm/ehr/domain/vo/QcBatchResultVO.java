package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * 批量质控评分的<b>进程内累加器</b>：批任务与单条质控用它累计分级分布与失败数。
 *
 * <p>⚠️ 它<b>不是</b>接口出参 —— 对外的任务视图是 {@link QcTaskVO}。
 * 因此这里只放「算得出来、下游要汇总」的字段，不放任何面向页面的展示字段。</p>
 *
 * <p>原先还有一个 {@code failureSamples} 列表（类注释写着「失败清单，明细仅前 50 条示例」），
 * 但全仓无人写入 —— 失败明细走 {@code qc_task.failure_list} 落库、由任务详情接口读回，
 * 这个字段从来没被填过。2026-10-05 连同它的 {@code Failure} 内部类一起删除：
 * 留着会让读代码的人以为「批任务会把失败样本带回内存」。</p>
 */
@Data
public class QcBatchResultVO {

    private int total;
    private int qualified;
    private int pendingReview;
    private int invalid;
    private int failed;
}
