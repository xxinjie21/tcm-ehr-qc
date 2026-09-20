package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 运行时配置（UX-68）：topbar「导入 LLM」弹窗的后端支撑。
 *
 * <p>【权限：仅管理员】——配置里含三方通道的 api-key，属系统级设置。</p>
 *
 * <p>三条口径：</p>
 * <ol>
 *   <li><b>只覆盖运行时层</b>：{@code PUT} 改的是内存中的生效配置，不写回 {@code application.yml}，
 *       保存即生效、重启回基线；需要长期生效请写入配置文件的 {@code llm} 段。</li>
 *   <li><b>密钥不回明文</b>：{@code GET} 只回掩码（{@code sk-****abcd}）；{@code PUT} 传空串或掩码
 *       表示不修改。api-key 不落盘、也不进前端 localStorage。</li>
 *   <li><b>先试后存</b>：{@code POST /api/llm/test} 用请求体里的参数探测连通性，
 *       不改变当前生效配置。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmController {

    private final ILlmConfigService llmConfigService;

    /** 读取当前生效配置（api-key 仅回传掩码）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/config")
    public ResponseEntity<Result<LlmConfigVO>> getConfig() {
        return ResponseEntity.ok(Result.ok(llmConfigService.get()));
    }

    /** 覆盖运行时配置并立即生效（不写回 application.yml）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PutMapping("/config")
    public ResponseEntity<Result<LlmConfigVO>> updateConfig(@RequestBody LlmConfigDTO dto) {
        return ResponseEntity.ok(Result.ok("配置已生效", llmConfigService.update(dto)));
    }

    /** 连通性探测（先试后存，不改变当前配置）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/test")
    public ResponseEntity<Result<LlmTestVO>> test(@RequestBody(required = false) LlmConfigDTO dto) {
        return ResponseEntity.ok(Result.ok("连接正常", llmConfigService.test(dto)));
    }
}
