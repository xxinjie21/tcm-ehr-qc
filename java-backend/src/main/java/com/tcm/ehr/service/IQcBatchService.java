package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.vo.QcTaskVO;
import com.tcm.ehr.domain.vo.QcTasksVO;

/**
 * 批量质控重算任务：提交、查询、取消、列表（§七 L5）。
 *
 * <p>生产/消费：任务 ID 进队列，固定 qc.batch.concurrency（默认 1）个工作线程消费；
 * 任务与进度写 qc_task 表，故提交后不占用请求线程、可关页面后回来轮询。</p>
 *
 * <p>并发固定为 1 的理由：重算逐条写 records（评分字段 + review_tasks），
 * 是 DB 写密集型；与在线查询抢库只会让两边都变慢。</p>
 */
public interface IQcBatchService {

    /**
     * 按范围提交批量重算任务，异步入队后立即返回。
     *
     * @param dto 批量请求（filters），可为 null（表示不限）
     * @return 新任务的进度视图（不含失败明细）
     * @throws IllegalArgumentException 超出单次上限、或已有任务在排队/运行中时抛出
     */
    QcTaskVO submit(QcBatchDTO dto);

    /**
     * 查询任务详情（含失败明细），只读。
     *
     * @param id 任务 ID
     * @return 任务进度视图；任务不存在时为 {@code null}
     */
    QcTaskVO get(String id);

    /**
     * 取消任务。
     *
     * @param id 任务 ID
     * @return 取消操作后的任务视图
     * @throws IllegalArgumentException 任务不存在时抛出
     */
    QcTaskVO cancel(String id);

    /**
     * 仅重试失败的记录：读原任务的完整失败记录 ID，按同一范围条件重新提交一个只含这批 ID 的任务。
     *
     * @param id 原任务 ID
     * @return 新任务的进度视图
     * @throws IllegalArgumentException 任务不存在 / 无失败记录时抛出
     */
    QcTaskVO retryFailed(String id);

    /**
     * 列出最近任务（最多 50 条，按创建时间倒序），只读。
     *
     * @return 任务列表 + 截断标记（多取一条精确判定，不靠 size == 上限猜）；不含失败明细
     */
    QcTasksVO list();
}
