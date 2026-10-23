package com.tcm.ehr.controller;

import com.tcm.ehr.common.utils.PageSizeGuard;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewStatsVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;
import com.tcm.ehr.service.IReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 人工复核：待复核任务列表 + 人工校正提交。
 *
 * <p>超时只在前端高亮，没有定时任务、不会自动流转。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReviewController {

    private final IReviewService reviewService;
    private final OperationLogger operationLogger;

    /**
     * 查询待复核任务。
     *
     * <p>【权限：登录即可】已作废的任务不返回。范围参数（科室 / 接诊时间 / 导入时间）
     * 按<b>关联病历</b>收窄（review_tasks 表本身没有这些列）；两个时间维度都两端齐全才生效，
     * 与病历数据、质控等页同一套 RangeFilter 口径。不给范围 = 全部（跨导入日期的积压队列默认可见）。</p>
     *
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数，上限 {@value PageSizeGuard#MAX_PAGE_SIZE}（超出按上限截断）
     * @param status   任务状态，为空表示不限
     * @param overdueOnly 批次9：true 时只返回已超期且仍待复核的任务
     * @param department 科室，空表示不限
     * @param start      接诊时间下界（yyyy-MM-dd），与 end 同时给才生效
     * @param end        接诊时间上界（yyyy-MM-dd）
     * @param importStart 导入时间下界（yyyy-MM-dd），与 importEnd 同时给才生效；与接诊时间独立
     * @param importEnd   导入时间上界（yyyy-MM-dd）
     * @return total=总条数；tasks=任务列表
     */
    @GetMapping("/review/tasks")
    public Result<ReviewTasksVO> tasks(@RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Boolean overdueOnly,
                                       @RequestParam(required = false) String department,
                                       @RequestParam(required = false) String start,
                                       @RequestParam(required = false) String end,
                                       @RequestParam(required = false) String importStart,
                                       @RequestParam(required = false) String importEnd) {
        return Result.ok(reviewService.listTasks(page, PageSizeGuard.clamp(pageSize), status, overdueOnly,
                filters(department, start, end, importStart, importEnd)));
    }

    /**
     * 复核概览统计：一次返回「待复核 / 已完成 / 待复核超期」三个数。
     *
     * <p>【权限：登录即可】与 {@code GET /review/tasks} 同一 wrapper 口径（含范围），只 COUNT
     * 不 SELECT —— 替代原先的三次 {@code pageSize=1} 列表查询（性能审查 P1-5）。
     * 三个数随范围联动：卡片是「点进去看到的那个队列」的入口，数字与点击后的列表一致。</p>
     *
     * @return pending / done / overdue 三个计数
     */
    @GetMapping("/review/tasks/count")
    public Result<ReviewStatsVO> countStats(@RequestParam(required = false) String department,
                                            @RequestParam(required = false) String start,
                                            @RequestParam(required = false) String end,
                                            @RequestParam(required = false) String importStart,
                                            @RequestParam(required = false) String importEnd) {
        return Result.ok(reviewService.countStats(
                filters(department, start, end, importStart, importEnd)));
    }

    /**
     * GET 查询参数 → 统一的范围过滤对象（两个时间维度都需上下界同时存在）。
     *
     * <p>与 QcController.filters 同一形状，只是复核任务不适用「分级」，
     * 故不设 grade。分级是病历的分级，复核任务的入参里本来就没有它。</p>
     */
    private FiltersDTO filters(String department, String start, String end,
                               String importStart, String importEnd) {
        // 1. 科室直接搬
        FiltersDTO f = new FiltersDTO();
        f.setDepartment(department);
        // 2. 时间要上下界同时存在才生效：只给一端会被当成"从某时到最新"，那不是用户的意思
        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            List<String> range = new ArrayList<>(2);
            range.add(start);
            range.add(end);
            f.setDateRange(range);
        }
        // 3. 导入时间（records.create_time）同理：与接诊时间是两个独立维度，可同时生效
        if (importStart != null && !importStart.isBlank() && importEnd != null && !importEnd.isBlank()) {
            List<String> importRange = new ArrayList<>(2);
            importRange.add(importStart);
            importRange.add(importEnd);
            f.setImportRange(importRange);
        }
        return f;
    }

    /**
     * 提交人工复核结果。
     *
     * <p>【权限：登录即可】校正后的结构化数据会回流重算分级。</p>
     *
     * @param recordId 病历ID
     * @param dto      correctedData=人工校正后的结构化数据；remark=复核意见，均可为空
     * @return status=复核后状态；score=重算得分
     */
    @PostMapping("/records/{recordId}/review")
    public Result<ReviewResultVO> review(@PathVariable String recordId,
                                         @Valid @RequestBody(required = false) ReviewDTO dto) {
        // 1. 复核（dto 可空 = 不做人工修正，只重算）
        // 病历不存在与"复核记录不存在/已完结"共用 1006，由 GlobalExceptionHandler 统一转 404
        ReviewResultVO vo = reviewService.review(recordId, dto);
        // 2. 留痕：复核结论与评分必须可追溯
        String detail = "结果：" + vo.getStatus() + "，评分：" + vo.getScore();
        // 3. 复核意见随操作日志落库：review_tasks 没有该列，日志是它唯一的去处
        String comment = dto == null ? null : dto.getComment();
        if (comment != null && !comment.isBlank()) {
            detail = detail + "，意见：" + comment.trim();
        }
        operationLogger.log("人工复核", recordId, detail);
        return Result.ok(vo);
    }
}
