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
 * NLP 实体抽取（批G·8.1）：转发至 Python FastAPI 服务（成员B，:8001）。
 *
 * <p>【权限：登录即可】。服务未启动 / 模型/util 不可用 / {@code nlp.enabled=false} 时
 * 降级返回**空 9 类** + {@code modelAvailable=false}，不阻塞主流程。</p>
 *
 * <p>返回前调用 {@link EntityNormalizer} 做术语归一（UX-63）：{@code content}=标准术语、
 * {@code sourceText}=归一前原文，命中层级随实体返回；无词典的 4 类（舌/脉/病因/治法）只保留原文。
 * 清洗链路的兜底补归一仍然保留，两者幂等、不会重复计数。</p>
 *
 * <p>降级时同时下发 {@code unavailableReason}（第八轮）：原先只有一个 {@code modelAvailable}
 * 布尔值，页面横幅只能把「功能没开」与「服务挂了」合写成「未开启，或抽取服务暂时不可用」，
 * 用户看不出该找谁。原因在这里判定 —— {@code nlp.enabled} 是静态配置，本类自己就能读，
 * 不必改 {@link PythonNlpClient#extract} 的签名（该方法被导入链路共用）。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class NlpController {

    private final PythonNlpClient nlpClient;
    private final EntityNormalizer entityNormalizer;
    private final INlpBatchService nlpBatchService;
    private final OperationLogger operationLogger;

    /** 与 {@link PythonNlpClient} 读同一个配置项，仅用于区分「没开」与「连不上」 */
    @Value("${nlp.enabled:false}")
    private boolean nlpEnabled;

    /**
     * 单条文本抽取 + 术语归一。
     *
     * <p>上游不可用时<b>不报错</b>，而是返回空 9 类 + {@code unavailableReason}，
     * 让页面照常渲染并显示降级横幅；只有入参为空才回 400。</p>
     *
     * @param dto 待抽取文本
     * @return 9 类实体（含归一结果与来源原文）；降级时为空结构 + 原因枚举
     */
    @PostMapping("/api/nlp/extract")
    public ResponseEntity<Result<NlpExtractVO>> extract(@RequestBody NlpExtractDTO dto) {
        // 1. 取待抽取文本，为空回 400（其余情况一律降级，不报错）
        String text = dto == null ? null : dto.getText();
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "待抽取文本不能为空"));
        }
        // 2. 转发 Python 抽取服务
        NlpExtractVO vo = nlpClient.extract(text);
        // 3. 上游不可用（vo 为 null）→ 降级为空 9 类并给出原因
        if (vo == null) {
            // 上游未启用 / 不可用：降级为空 9 类（modelAvailable=false）。
            // 具体原因见 PythonNlpClient 的启动 warn 与调用期 warn/debug；
            // 这里只记请求规模与判定出的原因，便于把「哪次请求降级了」与上面那条日志对上。
            String reason = nlpEnabled
                    ? NlpExtractVO.REASON_UNREACHABLE
                    : NlpExtractVO.REASON_DISABLED;
            log.debug("[NLP] 抽取降级为空 9 类（textLength={}，reason={}），术语归一无可归内容",
                    text.length(), reason);
            NlpExtractVO empty = NlpExtractVO.empty();
            empty.setUnavailableReason(reason);
            return ResponseEntity.ok(Result.ok(empty));
        }
        // 4. 服务在跑但模型未加载 → 只标记原因，规则兜底结果照常返回
        if (!vo.isModelAvailable()) {
            // 服务在跑但模型没加载：仍会返回规则兜底的少数类别，不算「没产出」，
            // 但要让用户知道这次少了模型那一半。
            vo.setUnavailableReason(NlpExtractVO.REASON_MODEL_MISSING);
        }
        // 5. 对抽取结果做术语归一（content=标准词、sourceText=归一前原文）
        EntityNormalizer.NormStat stat = entityNormalizer.normalize(vo);
        log.debug("[NLP] 抽取完成并归一：命中 {} 条（精确 {} / 包含 {} / 模糊 {}）",
                stat.hit(), stat.exact(), stat.contain(), stat.fuzzy());
        return ResponseEntity.ok(Result.ok(vo));
    }

    // ------------------------------------------------------------------ 批量解析（批K·K-a，异步任务）

    /** 提交批量解析任务（后台队列执行）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/nlp/extract/batch")
    public Result<NlpTaskVO> submitBatch(@RequestBody(required = false) NlpBatchDTO dto) {
        NlpTaskVO vo = nlpBatchService.submit(dto, RequestUtils.currentUsername());
        operationLogger.log("批量解析", RecordFilter.describe(dto == null ? null : dto.getFilters()),
                "提交任务，计划 " + vo.getTotal() + " 条");
        return Result.ok("已提交，后台解析中", vo);
    }

    /** 批量任务进度查询；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/nlp/extract/batch/{id}")
    public ResponseEntity<Result<NlpTaskVO>> batchStatus(@PathVariable String id) {
        NlpTaskVO vo = nlpBatchService.get(id);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(404, "任务不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /** 取消批量任务（排队中直接取消，运行中置取消位）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/nlp/extract/batch/{id}/cancel")
    public Result<NlpTaskVO> cancelBatch(@PathVariable String id) {
        return Result.ok("已取消", nlpBatchService.cancel(id));
    }

    /** 批量任务列表（最近 50 条）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/nlp/extract/batch")
    public Result<List<NlpTaskVO>> batchList() {
        return Result.ok(nlpBatchService.list());
    }
}
