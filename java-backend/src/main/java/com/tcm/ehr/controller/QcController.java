package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.dto.QcCheckDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.service.IQcService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质控（批B·2.3）：事前检查 / 逻辑一致性 / 单条评分 / 批量重算。
 * 判定地基=规则引擎（QcScorer + LogicChecker）。
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
}
