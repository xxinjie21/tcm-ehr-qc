package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.domain.dto.NlpExtractDTO;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * NLP 实体抽取（批G·8.1）：转发至 Python FastAPI 服务（成员B，:8001）。
 *
 * <p>【权限：登录即可】。服务未启动 / 模型/util 不可用 / {@code nlp.enabled=false} 时
 * 降级返回**空 9 类** + {@code modelAvailable=false}，不阻塞主流程。</p>
 *
 * <p>返回前调用 {@link EntityNormalizer} 做术语归一（UX-63）：{@code content}=标准术语、
 * {@code sourceText}=归一前原文，命中层级随实体返回；无词典的 4 类（舌/脉/病因/治法）只保留原文。
 * 清洗链路的兜底补归一仍然保留，两者幂等、不会重复计数。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class NlpController {

    private final PythonNlpClient nlpClient;
    private final EntityNormalizer entityNormalizer;

    @PostMapping("/api/nlp/extract")
    public ResponseEntity<Result<NlpExtractVO>> extract(@RequestBody NlpExtractDTO dto) {
        String text = dto == null ? null : dto.getText();
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "待抽取文本不能为空"));
        }
        NlpExtractVO vo = nlpClient.extract(text);
        if (vo == null) {
            // 上游未启用 / 不可用：降级为空 9 类（modelAvailable=false）。
            // 具体原因见 PythonNlpClient 的启动 warn 与调用期 warn/debug；
            // 这里只记请求规模，便于把「哪次请求降级了」与上面那条日志对上。
            log.debug("[NLP] 抽取降级为空 9 类（textLength={}），术语归一无可归内容", text.length());
            return ResponseEntity.ok(Result.ok(NlpExtractVO.empty()));
        }
        EntityNormalizer.NormStat stat = entityNormalizer.normalize(vo);
        log.debug("[NLP] 抽取完成并归一：命中 {} 条（精确 {} / 包含 {} / 模糊 {}）",
                stat.hit(), stat.exact(), stat.contain(), stat.fuzzy());
        return ResponseEntity.ok(Result.ok(vo));
    }
}
