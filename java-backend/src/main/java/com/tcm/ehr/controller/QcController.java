package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.dto.QcCheckDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import com.tcm.ehr.domain.vo.GraphVO;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.service.IQcService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 质控（批B·2.3）：事前检查 / 逻辑一致性 / 单条评分 / 批量重算；
 * 批D·3.3 增图谱聚合。判定地基=规则引擎（QcScorer + LogicChecker）。
 */
@RestController
@RequiredArgsConstructor
public class QcController {

    private final IQcService qcService;

    /** 事前质控（缺失 / 格式 / 查重）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/check")
    public Result<QcCheckVO> check(@RequestBody QcCheckDTO dto) {
        return Result.ok(qcService.check(dto));
    }

    /** 诊疗逻辑一致性；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/check/logic")
    public Result<LogicCheckVO> checkLogic(@RequestBody LogicCheckDTO dto) {
        return Result.ok(qcService.checkLogic(dto));
    }

    /** 单条评分；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/score")
    public Result<ScoreResultVO> score(@RequestBody QcScoreDTO dto) {
        return Result.ok(qcService.score(dto));
    }

    /** 全库/范围内批量重算；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/score/batch")
    public Result<QcBatchResultVO> scoreBatch(@RequestBody(required = false) QcBatchDTO dto) {
        return Result.ok(qcService.scoreBatch(dto));
    }

    /** 质控检验图谱（批D·3.3）：全库/范围内聚合；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/qc/graph")
    public Result<GraphVO> graph(@RequestParam(required = false) String department,
                                 @RequestParam(required = false) String start,
                                 @RequestParam(required = false) String end,
                                 @RequestParam(required = false) String pattern,
                                 @RequestParam(required = false) String grade) {
        return Result.ok(qcService.graph(filters(department, start, end, pattern, grade)));
    }

    /** GET query 参数 → FiltersDTO */
    private FiltersDTO filters(String department, String start, String end, String pattern, String grade) {
        FiltersDTO f = new FiltersDTO();
        f.setDepartment(department);
        f.setPattern(pattern);
        f.setGrade(grade);
        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            List<String> range = new ArrayList<>(2);
            range.add(start);
            range.add(end);
            f.setDateRange(range);
        }
        return f;
    }

    /** 质控评分标准（批P）：只读下发当前口径与逻辑规则；【权限：登录即可】 */
    @GetMapping("/api/qc/rules")
    public Result<com.tcm.ehr.domain.vo.QcRuleSetVO> rules() {
        return Result.ok(qcService.rules());
    }

    /** 范围扣分维度聚合（批P）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/qc/deduction-stats")
    public Result<com.tcm.ehr.domain.vo.DeductionStatsVO> deductionStats(
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String pattern,
            @RequestParam(required = false) String grade) {
        return Result.ok(qcService.deductionStats(filters(department, start, end, pattern, grade)));
    }
}
