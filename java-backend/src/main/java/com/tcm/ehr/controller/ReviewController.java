package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;
import com.tcm.ehr.service.IReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工复核：待复核任务列表 + 人工校正提交。
 *
 * <p>超时只在前端高亮，没有定时任务、不会自动流转。</p>
 */
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final IReviewService reviewService;
    private final OperationLogger operationLogger;

    /**
     * 查询待复核任务。
     *
     * <p>【权限：管理员 / 审核员】已作废的任务不返回。</p>
     *
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数
     * @param status   任务状态，为空表示不限
     * @return total=总条数；tasks=任务列表
     */
    @RequireRole(roles = {"管理员", "审核员"})
    @GetMapping("/api/review/tasks")
    public Result<ReviewTasksVO> tasks(@RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String status) {
        return Result.ok(reviewService.listTasks(page, pageSize, status));
    }

    /**
     * 提交人工复核结果。
     *
     * <p>【权限：管理员 / 审核员】校正后的结构化数据会回流重算分级。</p>
     *
     * @param recordId 病历ID
     * @param dto      correctedData=人工校正后的结构化数据；remark=复核意见，均可为空
     * @return status=复核后状态；score=重算得分
     */
    @RequireRole(roles = {"管理员", "审核员"})
    @PostMapping("/api/records/{recordId}/review")
    public Result<ReviewResultVO> review(@PathVariable String recordId,
                                         @RequestBody(required = false) ReviewDTO dto) {
        // 1. 复核（dto 可空 = 不做人工修正，只重算）
        // 病历不存在与"复核记录不存在/已完结"共用 1006，由 GlobalExceptionHandler 统一转 404
        ReviewResultVO vo = reviewService.review(recordId, dto);
        // 2. 留痕：复核结论与评分必须可追溯
        operationLogger.log("人工复核", recordId, "结果：" + vo.getStatus() + "，评分：" + vo.getScore());
        return Result.ok(vo);
    }
}
