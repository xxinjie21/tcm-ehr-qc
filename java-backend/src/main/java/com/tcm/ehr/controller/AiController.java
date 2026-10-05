package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.AiAsyncTasks;
import com.tcm.ehr.domain.dto.AiQueryDTO;
import com.tcm.ehr.domain.vo.AiReplyVO;
import com.tcm.ehr.service.IAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 消费端接口：质控解读、助手问答、复核预检。
 *
 * <p>读病历受组织数据域约束；LLM 不可用时降级为规则输出，不阻塞也不外泄底层异常。</p>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final IAiService aiService;
    private final AiAsyncTasks aiAsyncTasks;

    /**
     * 异步提交一次 AI 生成（批次 15 · 15.1）。
     *
     * <p>与三个同步接口**同一套校验**，只是把「等 LLM 生成」搬到专用线程池：请求线程立即返回
     * 202 + 任务号，前端轮询 {@code GET /api/ai/async/{taskId}}。这样长响应不再占请求线程，
     * 用户也可以离开页面再回来取结果（15.2 的「可离开页面」）。</p>
     *
     * <p>同步接口保留不动：调用方按需选择，向后兼容。</p>
     *
     * @param kind interpret | chat | review
     * @return 202 + {taskId}
     */
    @PostMapping("/async/{kind}")
    public ResponseEntity<Result<Map<String, String>>> submitAsync(@PathVariable String kind,
                                                                  @Valid @RequestBody AiQueryDTO dto) {
        if (!List.of("interpret", "chat", "review").contains(kind)) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的 AI 动作"));
        }
        // 与同步接口同一套必填校验：缺参数当场 400，不丢进任务里让用户等一轮才知道
        if (!"chat".equals(kind)
                && (dto == null || dto.getRecordId() == null || dto.getRecordId().isBlank())) {
            return ResponseEntity.badRequest().body(Result.error(400, "未指定病历"));
        }
        if ("chat".equals(kind) && (dto == null || dto.getQuestion() == null || dto.getQuestion().isBlank())) {
            return ResponseEntity.badRequest().body(Result.error(400, "请输入问题"));
        }
        String taskId = aiAsyncTasks.submit(() -> {
            AiReplyVO r = switch (kind) {
                case "interpret" -> aiService.interpret(dto);
                case "chat" -> aiService.chat(dto);
                default -> aiService.review(dto);
            };
            if (r == null) {
                // 同步路径这里回 404；异步路径只能把结论放进任务结果，所以显式失败，
                // 让前端显示「病历不存在」而不是一个空回复
                throw new IllegalArgumentException("病历不存在");
            }
            return r;
        });
        return ResponseEntity.accepted().body(Result.ok(Map.of("taskId", taskId)));
    }

    /**
     * 查异步任务结果（批次 15 · 15.1）。
     *
     * <p>任务不存在、已过期、或不属于调用者，一律返回同一个 404 —— 不区分「不存在」与
     * 「不是你的」，否则任务号可以被用来探测。</p>
     *
     * @return state=RUNNING|DONE|FAILED；DONE 带 reply；FAILED 带 error（前端据此给可重试提示）
     */
    @GetMapping("/async/{taskId}")
    public ResponseEntity<Result<Map<String, Object>>> asyncResult(@PathVariable String taskId) {
        AiAsyncTasks.Snapshot s = aiAsyncTasks.get(taskId);
        if (s == null) {
            return ResponseEntity.status(404).body(Result.error(1006, "任务不存在或已过期"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", s.state().name());
        data.put("reply", s.reply());
        data.put("error", s.error());
        return ResponseEntity.ok(Result.ok(data));
    }

    /**
     * 生成单份病历的质控解读。
     *
     * <p>【权限：登录即可】规则出结论，LLM 仅做叙述增强；病历不存在返回 404。</p>
     *
     * @param dto recordId=病历ID（必填）
     * @return answer=解读正文；source=结果来源（rule/llm）；llmAvailable=LLM 是否可用
     */
    @PostMapping("/interpret")
    public ResponseEntity<Result<AiReplyVO>> interpret(@Valid @RequestBody AiQueryDTO dto) {
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
    public ResponseEntity<Result<AiReplyVO>> chat(@Valid @RequestBody AiQueryDTO dto) {
        // 1. 问题必填；2. 问答永不返回 null，兜底答案也是结果
        if (dto == null || dto.getQuestion() == null || dto.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "请输入问题"));
        }
        return ResponseEntity.ok(Result.ok(aiService.chat(dto)));
    }

    /**
     * 生成复核预检意见。
     *
     * <p>【权限：登录即可】基于规则重算的扣分明细给出建议，病历不存在返回 404。</p>
     *
     * @param dto recordId=病历ID（必填）
     * @return answer=预检建议；source=rule/llm；llmAvailable=LLM 是否可用
     */
    @PostMapping("/review")
    public ResponseEntity<Result<AiReplyVO>> review(@Valid @RequestBody AiQueryDTO dto) {
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
