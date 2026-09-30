package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.LlmConfigDTO;
import com.tcm.ehr.domain.vo.LlmConfigVO;
import com.tcm.ehr.domain.vo.LlmTestVO;
import com.tcm.ehr.service.ILlmConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 运行时配置：读取、保存、连通性探测。
 *
 * <p>三条都是<b>登录即可</b>，且都只作用于<b>当前用户自己</b>的配置：
 * 读取只回掩码、保存写自己的行、探测用自己的参数试连通。
 * 管理员同样只能改自己的 —— 配置含三方通道密钥，属个人设置而非系统级设置。</p>
 *
 * <ul>
 *   <li>保存只改运行时配置（落盘到 qc 之外的 llm-config.json），不写回 application.yml，重启仍沿用；</li>
 *   <li>密钥不落明文：读取只回掩码，保存时空值或掩码都表示"不修改"；</li>
 *   <li>先试后存：探测用请求里的参数试连通，不改变当前生效配置。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmController {

    private final ILlmConfigService llmConfigService;

    /**
     * 读取当前生效配置。
     *
     * <p>【权限：登录即可】只回掩码，不给明文密钥。</p>
     *
     * @return enabled=是否启用；provider=通道；apiKeySet/apiKeyMask=密钥是否已配及其掩码
     */
    @GetMapping("/config")
    public ResponseEntity<Result<LlmConfigVO>> getConfig() {
        return ResponseEntity.ok(Result.ok(llmConfigService.get()));
    }

    /**
     * 保存<b>本人</b>的 LLM 配置并立即生效。
     *
     * <p>【权限：登录即可】写的是 {@code user_llm_config} 里自己那一行，
     * 管理员也只改自己的 —— 配置是「每个用户一份」，不是系统级设置。
     * 密钥经 AES-256-GCM 加密落库，读取只回掩码。</p>
     *
     * @param dto provider=通道；baseUrl/apiKey/model/temperature/timeout 等连接参数
     * @return 保存后生效的配置
     */
    @PutMapping("/config")
    public ResponseEntity<Result<LlmConfigVO>> updateConfig(@Valid @RequestBody LlmConfigDTO dto) {
        return ResponseEntity.ok(Result.ok("配置已生效", llmConfigService.update(dto)));
    }

    /**
     * 探测与配置文件的连通性。
     *
     * <p>【权限：登录即可】用请求体里的参数试，不改变当前生效配置；openai 通道缺密钥返回 400。</p>
     *
     * @param dto 待试的连接参数，为空则用当前生效配置
     * @return ok=是否连通；provider/model/latencyMs=探测结果
     */
    @PostMapping("/test")
    public ResponseEntity<Result<LlmTestVO>> test(@Valid @RequestBody(required = false) LlmConfigDTO dto) {
        return ResponseEntity.ok(Result.ok("连接正常", llmConfigService.test(dto)));
    }
}