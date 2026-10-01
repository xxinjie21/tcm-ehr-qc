package com.tcm.ehr.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.service.IOrgPermissionService;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.dto.QcCheckDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import com.tcm.ehr.domain.vo.DeductionStatsVO;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.QcRulesVO;
import com.tcm.ehr.domain.vo.QcTaskVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.service.IQcBatchService;
import com.tcm.ehr.service.IQcService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import jakarta.validation.Valid;
import java.util.List;

/**
 * 质控：事前检查、逻辑一致性、单条评分、批量重算，以及评分规则的读/写/重置与扣分聚合。
 *
 * <p>评分口径由 {@link QcRuleSet} 决定，判定不经过 LLM。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/qc")
public class QcController {

    private final IQcService qcService;
    /** 组织内写权限（管理员 / 所有者 / 被授权成员） */
    private final IOrgPermissionService orgPermission;
    private final IQcBatchService qcBatchService;

    /**
     * 事前质控：要素缺失与格式校验。
     *
     * <p>【权限：登录即可】缺失按"结构化与原始列都为空"判定。</p>
     *
     * @param dto recordId=病历ID（必填）；structuredData=可选，缺省时读取库中结构化数据
     * @return missingFields=缺失的核心要素；formatErrors=年龄/性别格式问题
     */
    @PostMapping("/check")
    public Result<QcCheckVO> check(@Valid @RequestBody QcCheckDTO dto) {
        return Result.ok(qcService.check(dto));
    }

    /**
     * 诊疗逻辑一致性检查。
     *
     * <p>【权限：登录即可】按当前规则表判定，规则未覆盖的要素不判冲突。</p>
     *
     * @param dto patternList/treatmentList/formulaList 待判定的要素
     * @return conflicts=冲突描述；consistent=是否无冲突
     */
    @PostMapping("/check/logic")
    public Result<LogicCheckVO> checkLogic(@Valid @RequestBody LogicCheckDTO dto) {
        return Result.ok(qcService.checkLogic(dto));
    }

    /**
     * 单条病历评分（不落库）。
     *
     * <p>【权限：登录即可】</p>
     *
     * @param dto recordId=病历ID；structuredData=可选，缺省时读取库中结构化数据
     * @return score=得分；grade=分级；deductions=扣分明细
     */
    @PostMapping("/score")
    public Result<ScoreResultVO> score(@Valid @RequestBody QcScoreDTO dto) {
        return Result.ok(qcService.score(dto));
    }

    /**
     * 提交批量重算任务（异步，§七 L5）。
     *
     * <p>【权限：登录即可】提交后立即返回 taskId，不再同步跑到尾。
     * 任务会覆盖既有分数并同步复核任务状态；超上限或已有任务在跑返回 400。</p>
     *
     * <p>⚠️ <b>破坏性变更</b>：原来返回分级汇总，现在只返回任务状态；
     * 分级汇总改为轮询 {@code GET /api/qc/score/batch/{id}} 获取。</p>
     *
     * @param dto filters=范围条件，为空表示全库
     * @return id=任务ID；status=QUEUED；total=计划处理条数
     */
    @PostMapping("/score/batch")
    public Result<QcTaskVO> submitScoreBatch(@Valid @RequestBody(required = false) QcBatchDTO dto) {
        return Result.ok("已提交，后台运行中", qcBatchService.submit(dto));
    }

    /**
     * 查询批量重算任务进度（含分级汇总）。
     *
     * <p>【权限：登录即可】</p>
     *
     * @param id 任务ID
     * @return 进度与分级汇总；任务不存在时返回 404
     */
    @GetMapping("/score/batch/{id}")
    public ResponseEntity<Result<QcTaskVO>> scoreBatchStatus(@PathVariable String id) {
        // 1. 进度在表里；任务不存在与「任务被清理」同一表现
        QcTaskVO vo = qcBatchService.get(id);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(404, "任务不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 列出最近的批量重算任务（最多 50 条）。
     *
     * <p>【权限：登录即可】不含失败明细（看明细请走详情接口）。</p>
     *
     * @return 按提交时间倒序的任务列表
     */
    @GetMapping("/score/batch")
    public Result<List<QcTaskVO>> listScoreBatch() {
        return Result.ok(qcBatchService.list());
    }

    /**
     * 取消批量重算任务。
     *
     * <p>【权限：登录即可】排队中的直接落已取消；
     * 运行中的置取消位，由 worker 在页边界退出并落库。</p>
     *
     * @param id 任务ID
     * @return 取消后的任务状态；任务不存在时返回 404
     */
    @PostMapping("/score/batch/{id}/cancel")
    public Result<QcTaskVO> cancelScoreBatch(@PathVariable String id) {
        // 不在 Controller 内 catch 拼错误体：任务不存在由 service 抛 ResourceNotFoundException，
        // 统一交给 GlobalExceptionHandler 转 404（全仓唯一错误体出口）
        return Result.ok("已取消", qcBatchService.cancel(id));
    }

    /**
     * 读取当前生效的质控规则。
     *
     * <p>【权限：登录即可】返回<b>当前组织</b>生效的规则。</p>
     *
     * @return rules=规则集；descriptions=自然语言描述；warnings=加载告警
     */
    @GetMapping("/rules")
    public Result<QcRulesVO> rules() {
        return Result.ok(qcService.rules());
    }

    /**
     * 保存质控规则并立即生效。
     *
     * <p>【权限：管理员 / 所有者 / 被授权成员】保存到<b>当前组织</b>（qc_rules 表），
     * 其他组织不受影响；缺项按内置默认补齐。</p>
     *
     * @param rules 规则集
     * @return 保存后的规则集
     */
    @PutMapping("/rules")
    public Result<QcRulesVO> updateRules(@Valid @RequestBody QcRuleSet rules) {
        requireRuleWrite();
        return Result.ok("规则已保存并生效", qcService.updateRules(rules));
    }

    /**
     * 恢复默认质控规则。
     *
     * <p>【权限：仅管理员】删除规则文件并回退到内置默认。</p>
     *
     * @return 恢复后的规则集
     */
    @PostMapping("/rules/reset")
    public Result<QcRulesVO> resetRules() {
        requireRuleWrite();
        return Result.ok("已恢复默认规则", qcService.resetRules());
    }

    /**
     * 汇总范围内的扣分分布。
     *
     * <p>【权限：登录即可】优先读已落库的 qc_results，未算过的按当前规则现算。</p>
     *
     * @param department 科室，空表示不限
     * @param start      接诊时间下界（yyyy-MM-dd），与 end 同时给才生效
     * @param end        接诊时间上界（yyyy-MM-dd）
     * @param pattern    证候关键字，模糊匹配
     * @param grade      分级
     * @return scanned=统计条数；byType/byItem=维度与明细扣分；gradeDist=分级分布
     */
    @GetMapping("/deduction-stats")
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

    /**
     * 质控规则写门槛：管理员 / 组织所有者 / 被授权成员。
     *
     * <p>不做成注解是因为它要查 DB 里的成员授权位（不是只看 JWT 里的 role），
     * 与 {@code @RequireRole} 的能力不同。</p>
     */
    private void requireRuleWrite() {
        String userId = RequestUtils.currentUserId();
        if (RequestUtils.isAdmin() || orgPermission.canWriteQcRules(userId)) {
            return;
        }
        throw new ForbiddenException("无权修改本组织的质控规则（需管理员、组织所有者或被授权成员）");
    }
}
