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
 * AI 消费端接口：质控解读、助手问答、复核预检。
 *
 * <p>读病历受数据域约束（审核员仅待复核域）；LLM 不可用时降级为规则输出，不阻塞也不外泄底层异常。</p>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final IAiService aiService;

    /**
     * 生成单份病历的质控解读。
     *
     * <p>【权限：登录即可】规则出结论，LLM 仅做叙述增强；病历不存在返回 404。</p>
     *
     * @param dto recordId=病历ID（必填）
     * @return answer=解读正文；source=结果来源（rule/llm）；llmAvailable=LLM 是否可用
     */
    @PostMapping("/interpret")
    public ResponseEntity<Result<AiReplyVO>> interpret(@RequestBody AiQueryDTO dto) {
        // 1. 必填校验在前：缺参数是 400，不该走到 service 才失败
        if (dto == null || dto.getRecordId() == null || dto.getRecordId().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "未指定病历"));
        }
        // 2. 调服务；返回 null = 病历不存在
        AiReplyVO vo = aiService.interpret(dto);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(1006, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 业务问答。
     *
     * <p>【权限：登录即可】技术实现类问题按兜底拒答，不回答系统内部细节；问题为空返回 400。</p>
     *
     * @param dto question=问题文本（必填）；recordId=可选，命中"这份病历…"类问题时带上下文
     * @return answer=回答正文；source=rule/llm；llmAvailable=LLM 是否可用
     */
    @PostMapping("/chat")
    public ResponseEntity<Result<AiReplyVO>> chat(@RequestBody AiQueryDTO dto) {
        // 1. 问题必填；2. 问答永不返回 null，兜底答案也是结果
        if (dto == null || dto.getQuestion() == null || dto.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "请输入问题"));
        }
        return ResponseEntity.ok(Result.ok(aiService.chat(dto)));
    }

    /**
     * 生成复核预检意见。
     *
     * <p>【权限：管理员 / 审核员】基于规则重算的扣分明细给出建议，病历不存在返回 404。</p>
     *
     * @param dto recordId=病历ID（必填）
     * @return answer=预检建议；source=rule/llm；llmAvailable=LLM 是否可用
     */
    @RequireRole(roles = {"管理员", "审核员"})
    @PostMapping("/review")
    public ResponseEntity<Result<AiReplyVO>> review(@RequestBody AiQueryDTO dto) {
        // 1. 必填校验 2. 病历不存在回 404
        if (dto == null || dto.getRecordId() == null || dto.getRecordId().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "未指定病历"));
        }
        AiReplyVO vo = aiService.review(dto);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(1006, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }
}
