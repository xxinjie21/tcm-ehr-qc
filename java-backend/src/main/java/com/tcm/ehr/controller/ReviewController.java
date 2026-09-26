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
 * 人工复核（批D·5.1）：待复核任务列表 + 人工校正/提交复核（自动重算回流）。
 * 超时仅前端高亮，无后台定时任务、不自动流转。
 */
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final IReviewService reviewService;
    private final OperationLogger operationLogger;

    /** 待复核任务列表；【权限：管理员 / 审核员】 */
    @RequireRole(roles = {"管理员", "审核员"})
    @GetMapping("/api/review/tasks")
    public Result<ReviewTasksVO> tasks(@RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String status) {
        return Result.ok(reviewService.listTasks(page, pageSize, status));
    }

    /** 人工校正与复核；【权限：管理员 / 审核员】 */
    @RequireRole(roles = {"管理员", "审核员"})
    @PostMapping("/api/records/{recordId}/review")
    public Result<ReviewResultVO> review(@PathVariable String recordId,
                                         @RequestBody(required = false) ReviewDTO dto) {
        // 「病历不存在」与「复核记录不存在或状态已完结」都走 ResourceNotFoundException(1006)
        // → GlobalExceptionHandler 统一回 404 + code=1006，不再有第二套码（B8-4）
        ReviewResultVO vo = reviewService.review(recordId, dto);
        operationLogger.log("人工复核", recordId, "结果：" + vo.getStatus() + "，评分：" + vo.getScore());
        return Result.ok(vo);
    }
}
