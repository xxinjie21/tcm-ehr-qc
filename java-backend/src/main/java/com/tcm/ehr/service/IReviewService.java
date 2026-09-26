package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
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
     * @return total=总条数；tasks=任务列表
     */
    ReviewTasksVO listTasks(Integer page, Integer pageSize, String status);

    /**
     * 提交人工复核并自动重算分级。
     *
     * @param recordId 病历ID
     * @param dto      correctedData=校正后的结构化数据；remark=意见
     * @return status=复核后状态；score=重算得分
     * @throws com.tcm.ehr.common.exception.ResourceNotFoundException 病历或复核任务不存在
     */
    ReviewResultVO review(String recordId, ReviewDTO dto);
}
