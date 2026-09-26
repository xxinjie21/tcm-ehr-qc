package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.NlpBatchDTO;
import com.tcm.ehr.domain.dto.NlpExtractDTO;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import com.tcm.ehr.domain.vo.NlpTaskVO;
import com.tcm.ehr.service.INlpBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * NLP 接口：单条文本抽取 + 批量异步解析任务。
 *
 * <p>抽取转发到 Python 服务（:8001）。上游未启动、模型不可用或 {@code nlp.enabled=false} 时
 * 降级返回空 9 类并带 {@code unavailableReason}，不阻塞主流程。</p>
 *
 * <p>返回前统一做术语归一：{@code content}=标准词、{@code sourceText}=归一前原文，命中层级随实体返回；
 * 无词典的舌象/脉象/病因/治法只保留原文。清洗链路仍保留一次兜底补归一，两者幂等不会重复计数。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class NlpController {

    private final PythonNlpClient nlpClient;
    private final EntityNormalizer entityNormalizer;
    private final INlpBatchService nlpBatchService;
    private final OperationLogger operationLogger;

    /** 与 {@link PythonNlpClient} 读同一配置项，仅用于区分"没开"与"连不上" */
    @Value("${nlp.enabled:false}")
    private boolean nlpEnabled;

    /**
     * 抽取单条文本并做术语归一。
     *
     * <p>【权限：登录即可】除入参为空外一律不报错：上游不可用时返回空 9 类与降级原因，页面照常渲染。</p>
     *
     * @param dto text=待抽取文本
     * @return 9 类实体（含归一结果与原文）；降级时为空结构 + unavailableReason
     */
    @PostMapping("/api/nlp/extract")
    public ResponseEntity<Result<NlpExtractVO>> extract(@RequestBody NlpExtractDTO dto) {
        // 1. 取待抽取文本，为空回 400（其余情况一律降级）
        String text = dto == null ? null : dto.getText();
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "待抽取文本不能为空"));
        }
        // 2. 转发 Python 抽取服务
        NlpExtractVO vo = nlpClient.extract(text);
        // 3. 上游不可用 → 降级为空 9 类并标注原因，供页面显示降级横幅
        if (vo == null) {
            // 具体原因见 PythonNlpClient 的启动/调用日志，这里只记规模与判定结果，便于对账
            String reason = nlpEnabled
                    ? NlpExtractVO.REASON_UNREACHABLE
                    : NlpExtractVO.REASON_DISABLED;
            log.debug("[NLP] 抽取降级为空 9 类（textLength={}，reason={}），术语归一无可归内容",
                    text.length(), reason);
            NlpExtractVO empty = NlpExtractVO.empty();
            empty.setUnavailableReason(reason);
            return ResponseEntity.ok(Result.ok(empty));
        }
        // 4. 服务可用但模型未加载 → 规则兜底结果照常返回，只标记少了模型那一半
        if (!vo.isModelAvailable()) {
            vo.setUnavailableReason(NlpExtractVO.REASON_MODEL_MISSING);
        }
        // 5. 术语归一
        EntityNormalizer.NormStat stat = entityNormalizer.normalize(vo);
        log.debug("[NLP] 抽取完成并归一：命中 {} 条（精确 {} / 包含 {} / 模糊 {}）",
                stat.hit(), stat.exact(), stat.contain(), stat.fuzzy());
        return ResponseEntity.ok(Result.ok(vo));
    }

    // ------------------------------------------------------------------ 批量解析（后台队列）

    /**
     * 提交批量解析任务。
     *
     * <p>【权限：仅管理员】任务入队后立即返回，进度靠查询接口轮询。</p>
     *
     * @param dto filters=范围条件；limit=处理条数上限，0 表示不限
     * @return 任务ID、状态与计划处理条数
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/nlp/extract/batch")
    public Result<NlpTaskVO> submitBatch(@RequestBody(required = false) NlpBatchDTO dto) {
        // 1. 提交即返回（异步任务）；dto 可空 = 全库范围
        NlpTaskVO vo = nlpBatchService.submit(dto, RequestUtils.currentUsername());
        // 2. 留痕：把操作范围写进日志，便于事后核对这次解析动了哪些病历
        operationLogger.log("批量解析", RecordFilter.describe(dto == null ? null : dto.getFilters()),
                "提交任务，计划 " + vo.getTotal() + " 条");
        return Result.ok("已提交，后台解析中", vo);
    }

    /**
     * 查询批量解析任务进度。
     *
     * <p>【权限：仅管理员】任务不存在返回 404。</p>
     *
     * @param id 任务ID
     * @return 任务状态、进度、失败清单（截断存储）
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/nlp/extract/batch/{id}")
    public ResponseEntity<Result<NlpTaskVO>> batchStatus(@PathVariable String id) {
        // 1. 取任务；2. 不存在回 404（前端轮询时据此停止轮询）
        NlpTaskVO vo = nlpBatchService.get(id);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(404, "任务不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 取消批量解析任务。
     *
     * <p>【权限：仅管理员】排队中直接取消，运行中置取消位由工作线程在边界处退出。</p>
     *
     * @param id 任务ID
     * @return 取消后的任务状态
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/nlp/extract/batch/{id}/cancel")
    public Result<NlpTaskVO> cancelBatch(@PathVariable String id) {
        return Result.ok("已取消", nlpBatchService.cancel(id));
    }

    /**
     * 查询最近的批量解析任务。
     *
     * <p>【权限：仅管理员】固定返回最近 50 条，不含失败明细。</p>
     *
     * @return 任务列表（按提交时间倒序）
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/nlp/extract/batch")
    public Result<List<NlpTaskVO>> batchList() {
        return Result.ok(nlpBatchService.list());
    }
}
