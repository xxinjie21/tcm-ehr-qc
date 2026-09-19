package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.domain.dto.NlpExtractDTO;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * NLP 实体抽取（批G·8.1）：转发至 Python FastAPI 服务（成员B，:8001）。
 *
 * <p>【权限：登录即可】。服务未启动 / 模型/util 不可用 / {@code nlp.enabled=false} 时
 * 降级返回**空 9 类** + {@code modelAvailable=false}，不阻塞主流程。</p>
 */
@RestController
@RequiredArgsConstructor
public class NlpController {

    private final PythonNlpClient nlpClient;

    @PostMapping("/api/nlp/extract")
    public ResponseEntity<Result<NlpExtractVO>> extract(@RequestBody NlpExtractDTO dto) {
        String text = dto == null ? null : dto.getText();
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "待抽取文本不能为空"));
        }
        NlpExtractVO vo = nlpClient.extract(text);
        return ResponseEntity.ok(Result.ok(vo == null ? NlpExtractVO.empty() : vo));
    }
}
