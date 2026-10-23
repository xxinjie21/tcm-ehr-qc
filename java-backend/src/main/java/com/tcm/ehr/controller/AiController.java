package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.AiAsyncTasks;
import com.tcm.ehr.common.utils.AsyncTaskRegistry;
import com.tcm.ehr.common.utils.RateLimiter;
import com.tcm.ehr.common.utils.RequestUtils;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 消费端接口：质控解读、复核预检、术语补词。
 *
 * <p>读病历受组织数据域约束；LLM 不可用时降级为规则输出，不阻塞也不外泄底层异常。</p>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final IAiService aiService;
    private final AiAsyncTasks aiAsyncTasks;
    private final RateLimiter rateLimiter;

    /**
     * AI 接口限流（P2-31 / R12）：每用户 {@value #AI_RATE_LIMIT} 次 / {@value #AI_RATE_WINDOW_MINUTES} 分钟。
     *
     * <p><b>为什么只限 AI、不限登录与词典导入</b>：AI 链路是「登录即可」且<b>每次请求都可能
     * 产生一次真实的大模型调用（有成本）</b>，不限的话一个账号就能把额度打光。
     * 另两处刻意不加，避免同一件事做两遍：登录已有「连续失败 N 次锁定」的独立机制
     * （{@code AuthServiceImpl}），再叠一层限流只是换个名字的同一个闸门；
     * 词典导入是管理员专属接口（{@code POST /api/dictionary/import}），不存在自助滥用面。</p>
     *
     * <p>限额取 60 次 / 5 分钟：AI 调用都由人手点击触发，正常使用远达不到；
     * 撞上这个数的只可能是脚本化滥用。Redis 不可用时 {@link RateLimiter} 放行（fail-open），
     * 不让一次 Redis 抖动把 AI 全打成 429。</p>
     */
    private static final int AI_RATE_LIMIT = 60;
    private static final int AI_RATE_WINDOW_MINUTES = 5;

    /** AI 限流闸门：超限抛 {@code RateLimitedException}，由全局异常出口转 HTTP 429 */
    private void guardAiRate() {
        rateLimiter.check("ai", RequestUtils.currentUserId(), AI_RATE_LIMIT,
                Duration.ofMinutes(AI_RATE_WINDOW_MINUTES), "AI 请求过于频繁，请稍后再试");
    }

    /**
     * 异步提交一次 AI 生成（批次 15 · 15.1）。
     *
     * <p>【权限：登录即可】与同步接口**同一套校验**，只是把「等 LLM 生成」搬到专用线程池：请求线程立即返回
     * 202 + 任务号，前端轮询 {@code GET /api/ai/async/{taskId}}。这样长响应不再占请求线程，
     * 用户也可以离开页面再回来取结果（15.2 的「可离开页面」）。</p>
     *
     * <p>同步接口保留不动：调用方按需选择，向后兼容。termsuggest 只有异步这一条路。</p>
     *
     * @param kind interpret | review | termsuggest
     * @return 202 + {taskId}
     */
    @PostMapping("/async/{kind}")
    public ResponseEntity<Result<Map<String, String>>> submitAsync(@PathVariable String kind,
                                                                  @Valid @RequestBody AiQueryDTO dto) {
        if (!List.of("interpret", "review", "termsuggest").contains(kind)) {
            return ResponseEntity.badRequest().body(Result.error(400, "不支持的 AI 动作"));
        }
        // 与同步接口同一套必填校验：缺参数当场 400，不丢进任务里让用户等一轮才知道。
        // termsuggest 不看病历，校验的是待规范词表；interpret / review 必填 recordId。
        if ("termsuggest".equals(kind)) {
            if (dto == null || dto.getTerms() == null || dto.getTerms().isEmpty()) {
                return ResponseEntity.badRequest().body(Result.error(400, "请提供待规范的术语"));
            }
        } else if (dto == null || dto.getRecordId() == null || dto.getRecordId().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error(400, "未指定病历"));
        }
        // 限流放在真正的开销之前：参数非法（400）不计额度，避免「打错字」被算成滥用
        guardAiRate();
        String taskId = aiAsyncTasks.submit(() -> {
            AiReplyVO r = switch (kind) {
                case "interpret" -> aiService.interpret(dto);
                case "termsuggest" -> aiService.suggestTerms(dto);
                default -> aiService.review(dto);
            };
            if (r == null) {
                // 同步路径这里回 404；异步路径只能把结论放进任务结果，所以显式失败，
                // 让前端显示「病历不存在」而不是一个空回复。
                // termsuggest 恒不返回 null，故不会走到这里。
                throw new IllegalArgumentException("病历不存在");
            }
            return r;
        });
        return ResponseEntity.accepted().body(Result.ok(Map.of("taskId", taskId)));
    }

    /**
     * 查异步任务结果（批次 15 · 15.1）。
     *
     * <p>【权限：登录即可】任务不存在、已过期、或不属于调用者，一律返回同一个 404 —— 不区分「不存在」与
     * 「不是你的」，否则任务号可以被用来探测。</p>
     *
     * @return state=RUNNING|DONE|FAILED；DONE 带 reply；FAILED 带 error（前端据此给可重试提示）
     */
    @GetMapping("/async/{taskId}")
    public ResponseEntity<Result<Map<String, Object>>> asyncResult(@PathVariable String taskId) {
        AsyncTaskRegistry.Snapshot<AiReplyVO> s = aiAsyncTasks.get(taskId);
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
        guardAiRate();
        AiReplyVO vo = aiService.interpret(dto);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(1006, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
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
        guardAiRate();
        AiReplyVO vo = aiService.review(dto);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(1006, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }
}
