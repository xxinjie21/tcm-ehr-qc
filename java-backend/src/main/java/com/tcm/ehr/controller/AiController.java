package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.AiQueryDTO;
import com.tcm.ehr.domain.vo.AiReplyVO;
import com.tcm.ehr.service.IAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 消费端接口（批C · 3.1 解读卡 / 3.2 助手浮窗；批D 复用本控制器加 /review）。
 *
 * <p>【权限：登录即可】；读取受数据域约束（审核员仅待复核域）。LLM 不可用一律降级，
 * 不阻塞、不泄漏底层异常。</p>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final IAiService aiService;

    /** AI 质控解读：规则出结论 + LLM 叙述；【权限：登录即可】 */
    @PostMapping("/interpret")
    public ResponseEntity<Result<AiReplyVO>> interpret(@RequestBody AiQueryDTO dto) {
        if (dto == null || dto.getRecordId() == null || dto.getRecordId().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "recordId不能为空"));
        }
        AiReplyVO vo = aiService.interpret(dto);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(1006, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /** AI 助手问答：业务问题 LLM+规则检索，技术实现问题兜底拒答；【权限：登录即可】 */
    @PostMapping("/chat")
    public ResponseEntity<Result<AiReplyVO>> chat(@RequestBody AiQueryDTO dto) {
        if (dto == null || dto.getQuestion() == null || dto.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "question不能为空"));
        }
        return ResponseEntity.ok(Result.ok(aiService.chat(dto)));
    }

    /** AI 复核预检意见（批D·5.1）；【权限：管理员 / 审核员】 */
    @RequireRole(roles = {"管理员", "审核员"})
    @PostMapping("/review")
    public ResponseEntity<Result<AiReplyVO>> review(@RequestBody AiQueryDTO dto) {
        if (dto == null || dto.getRecordId() == null || dto.getRecordId().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "recordId不能为空"));
        }
        AiReplyVO vo = aiService.review(dto);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(1006, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }
}
