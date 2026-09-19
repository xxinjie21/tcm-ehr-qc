package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;

/**
 * 复核服务（批D·5.1）：待复核列表 + 人工校正/复核（自动重算回流）。
 */
public interface IReviewService extends IService<ReviewTask> {

    /** 待复核任务列表（is_obsolete=0；审核员仅待复核域） */
    ReviewTasksVO listTasks(Integer page, Integer pageSize, String status);

    /**
     * 人工校正 + 提交复核：合并修正数据 → 自动重算评分与逻辑 → 更新任务流转。
     *
     * @return 复核结果；病历不存在抛 {@code ResourceNotFoundException}；
     *         任务不存在或已完结返回 {@code null}（控制器回 2003）
     */
    ReviewResultVO review(String recordId, ReviewDTO dto);
}
