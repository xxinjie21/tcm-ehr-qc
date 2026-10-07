package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewStatsVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;

/**
 * 人工复核：任务列表与复核提交。
 */
public interface IReviewService extends IService<ReviewTask> {

    /**
     * 查询待复核任务。
     *
     * @param page     页码
     * @param pageSize 每页条数
     * @param status   状态，为空表示不限
     * @param overdueOnly 批次9：true 时只返回「已超期且仍待复核」的任务（worklist「只需我处理」）
     * @return total=总条数；tasks=任务列表
     */
    ReviewTasksVO listTasks(Integer page, Integer pageSize, String status, Boolean overdueOnly);

    /**
     * 一次返回复核概览的三个数：待复核 / 已完成 / 待复核超期。
     *
     * <p>统计口径与 {@link #listTasks} 同一 wrapper 组装（未失效任务 + 数据域），
     * 只 {@code COUNT} 不 {@code SELECT} —— 替代原先三次 {@code pageSize=1} 列表查询
     * （性能审查 P1-5，每次都会附带一次全表排序）。</p>
     *
     * @return pending=待复核总数；done=已完成；overdue=待复核中已超期
     */
    ReviewStatsVO countStats();

    /**
     * 提交人工复核并自动重算分级。
     *
     * @param recordId 病历ID
     * @param dto      correctedData=校正后的结构化数据；remark=意见
     * @return status=复核后状态；score=重算得分
     * @throws com.tcm.ehr.common.exception.ResourceNotFoundException 病历或复核任务不存在
     * @throws com.tcm.ehr.common.exception.ConcurrentOperationException 读时指纹与服务端当前不一致（批次 25.15）
     */
    ReviewResultVO review(String recordId, ReviewDTO dto);
}
