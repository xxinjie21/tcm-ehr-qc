package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.dto.QcCheckDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import com.tcm.ehr.domain.vo.DeductionStatsVO;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.QcRulesVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.service.IQcService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 质控：事前检查、逻辑一致性、单条评分、批量重算，以及评分规则的读/写/重置与扣分聚合。
 *
 * <p>评分口径由 {@link QcRuleSet} 决定，判定不经过 LLM。</p>
 */
@RestController
@RequiredArgsConstructor
public class QcController {

    private final IQcService qcService;

    /**
     * 事前质控：要素缺失与格式校验。
     *
     * <p>【权限：仅管理员】缺失按"结构化与原始列都为空"判定。</p>
     *
     * @param dto recordId=病历ID（必填）；structuredData=可选，缺省时读取库中结构化数据
     * @return missingFields=缺失的核心要素；formatErrors=年龄/性别格式问题
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/check")
    public Result<QcCheckVO> check(@RequestBody QcCheckDTO dto) {
        return Result.ok(qcService.check(dto));
    }

    /**
     * 诊疗逻辑一致性检查。
     *
     * <p>【权限：仅管理员】按当前规则表判定，规则未覆盖的要素不判冲突。</p>
     *
     * @param dto patternList/treatmentList/formulaList 待判定的要素
     * @return conflicts=冲突描述；consistent=是否无冲突
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/check/logic")
    public Result<LogicCheckVO> checkLogic(@RequestBody LogicCheckDTO dto) {
        return Result.ok(qcService.checkLogic(dto));
    }

    /**
     * 单条病历评分（不落库）。
     *
     * <p>【权限：仅管理员】</p>
     *
     * @param dto recordId=病历ID；structuredData=可选，缺省时读取库中结构化数据
     * @return score=得分；grade=分级；deductions=扣分明细
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/score")
    public Result<ScoreResultVO> score(@RequestBody QcScoreDTO dto) {
        return Result.ok(qcService.score(dto));
    }

    /**
     * 按范围批量重算评分与分级（写库）。
     *
     * <p>【权限：仅管理员】会覆盖既有分数并同步复核任务状态；超上限返回 400。</p>
     *
     * @param dto filters=范围条件，为空表示全库
     * @return total/qualified/pendingReview/invalid/failed=分级汇总；failureSamples=失败样本
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/score/batch")
    public Result<QcBatchResultVO> scoreBatch(@RequestBody(required = false) QcBatchDTO dto) {
        return Result.ok(qcService.scoreBatch(dto));
    }

    /**
     * 读取当前生效的质控规则。
     *
     * <p>【权限：登录即可】</p>
     *
     * @return rules=规则集；descriptions=自然语言描述；warnings=加载告警
     */
    @GetMapping("/api/qc/rules")
    public Result<QcRulesVO> rules() {
        return Result.ok(qcService.rules());
    }

    /**
     * 保存质控规则并立即生效。
     *
     * <p>【权限：仅管理员】落盘到 qc-rules.json，下次启动沿用；缺项按默认补齐。</p>
     *
     * @param rules 规则集
     * @return 保存后的规则集
     */
    @RequireRole(roles = {"管理员"})
    @PutMapping("/api/qc/rules")
    public Result<QcRulesVO> updateRules(@RequestBody QcRuleSet rules) {
        return Result.ok("规则已保存并生效", qcService.updateRules(rules));
    }

    /**
     * 恢复默认质控规则。
     *
     * <p>【权限：仅管理员】删除规则文件并回退到内置默认。</p>
     *
     * @return 恢复后的规则集
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/qc/rules/reset")
    public Result<QcRulesVO> resetRules() {
        return Result.ok("已恢复默认规则", qcService.resetRules());
    }

    /**
     * 汇总范围内的扣分分布。
     *
     * <p>【权限：仅管理员】优先读已落库的 qc_results，未算过的按当前规则现算。</p>
     *
     * @param department 科室，空表示不限
     * @param start      接诊时间下界（yyyy-MM-dd），与 end 同时给才生效
     * @param end        接诊时间上界（yyyy-MM-dd）
     * @param pattern    证候关键字，模糊匹配
     * @param grade      分级
     * @return scanned=统计条数；byType/byItem=维度与明细扣分；gradeDist=分级分布
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/qc/deduction-stats")
    public Result<DeductionStatsVO> deductionStats(
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String pattern,
            @RequestParam(required = false) String grade) {
        return Result.ok(qcService.deductionStats(filters(department, start, end, pattern, grade)));
    }

    /** GET 查询参数 → 统一的范围过滤对象（时间需上下界同时存在） */
    private FiltersDTO filters(String department, String start, String end, String pattern, String grade) {
        // 1. 三个标量条件直接搬
        FiltersDTO f = new FiltersDTO();
        f.setDepartment(department);
        f.setPattern(pattern);
        f.setGrade(grade);
        // 2. 时间要上下界同时存在才生效：只给一端会被当成"从某时到最新"，那不是用户的意思
        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            List<String> range = new ArrayList<>(2);
            range.add(start);
            range.add(end);
            f.setDateRange(range);
        }
        return f;
    }
}
