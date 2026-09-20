package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.config.LlmConfig;
import com.tcm.ehr.common.config.LlmConfigStore;
import com.tcm.ehr.common.exception.LlmProbeException;
import com.tcm.ehr.common.utils.LlmClient;
import com.tcm.ehr.domain.dto.LlmConfigDTO;
import com.tcm.ehr.domain.vo.LlmConfigVO;
import com.tcm.ehr.domain.vo.LlmTestVO;
import com.tcm.ehr.service.ILlmConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LLM 运行时配置实现（UX-68）。
 *
 * <p><b>参数合并口径</b>：DTO 中为 null / 空串的字段一律沿用当前值，前端弹窗可以只提交改过的项。
 * 唯一的例外是 api-key —— 空串与「回传的掩码」都视为「不修改」，因为用户看不到明文，
 * 无法区分「没改」与「想清空」。</p>
 *
 * <p><b>探测超时</b>：前端 axios 超时为 30 秒，探测本身再收紧到 20 秒，
 * 避免用户配置了 60 秒超时后「测试连接」被前端先行中断、只看到一句网络异常。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmConfigServiceImpl implements ILlmConfigService {

    /** 探测超时上限（毫秒） */
    private static final int PROBE_TIMEOUT_MS = 20000;

    private final LlmConfigStore store;
    private final LlmClient llmClient;

    @Override
    public LlmConfigVO get() {
        return toVO(store.get());
    }

    @Override
    public LlmConfigVO update(LlmConfigDTO dto) {
        LlmConfig next = merge(dto, store.get());
        if (next.enabled() && LlmConfig.PROVIDER_OPENAI.equals(next.provider()) && isBlank(next.apiKey())) {
            throw new IllegalArgumentException("选择 openai 通道时必须填写 API Key");
        }
        return toVO(store.update(next));
    }

    @Override
    public LlmTestVO test(LlmConfigDTO dto) {
        LlmConfig cfg = merge(dto, store.get());
        if (LlmConfig.PROVIDER_OPENAI.equals(cfg.provider()) && isBlank(cfg.apiKey())) {
            throw new IllegalArgumentException("选择 openai 通道时必须填写 API Key");
        }
        // 探测固定为「启用」：用户点测试连接就是要验证这套参数能不能用
        LlmConfig probeCfg = new LlmConfig(true, cfg.provider(), cfg.baseUrl(), cfg.apiKey(),
                cfg.model(), cfg.temperature(), Math.min(cfg.timeout(), PROBE_TIMEOUT_MS));

        long start = System.currentTimeMillis();
        String reply;
        try {
            reply = llmClient.probe(probeCfg);
        } catch (Exception e) {
            String reason = sanitize(e.getMessage(), cfg.apiKey());
            log.warn("[LLM] 连通性探测失败：provider={}，{}", probeCfg.provider(), reason);
            throw new LlmProbeException("连接失败：" + reason);
        }

        LlmTestVO vo = new LlmTestVO();
        vo.setProvider(probeCfg.provider());
        vo.setModel(isBlank(probeCfg.model()) ? "(通道默认)" : probeCfg.model());
        vo.setLatencyMs(System.currentTimeMillis() - start);
        vo.setReply(reply.length() > 200 ? reply.substring(0, 200) + "…" : reply);
        return vo;
    }

    // ------------------------------------------------------------------ 内部

    /** DTO 合并到当前配置；只做参数层面的合法性归一，不做业务校验 */
    private LlmConfig merge(LlmConfigDTO dto, LlmConfig cur) {
        if (dto == null) {
            return cur;
        }
        boolean enabled = dto.getEnabled() == null ? cur.enabled() : dto.getEnabled();

        String provider = isBlank(dto.getProvider()) ? cur.provider() : dto.getProvider();
        String normProvider = LlmConfig.normalizeProvider(provider);
        if (normProvider == null) {
            throw new IllegalArgumentException("通道只能选 ollama 或 openai");
        }

        Double temperature = dto.getTemperature() == null ? cur.temperature() : dto.getTemperature();
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("温度需在 0~2 之间");
        }

        int timeout = dto.getTimeout() == null || dto.getTimeout() <= 0 ? cur.timeout() : dto.getTimeout();
        if (timeout < 1000 || timeout > 300000) {
            throw new IllegalArgumentException("超时需在 1000~300000 毫秒之间");
        }

        return new LlmConfig(enabled, normProvider,
                dto.getBaseUrl() == null ? cur.baseUrl() : dto.getBaseUrl().trim(),
                resolveApiKey(dto.getApiKey(), cur.apiKey()),
                dto.getModel() == null ? cur.model() : dto.getModel().trim(),
                temperature, timeout);
    }

    /** 空串 / 回传的掩码 = 不修改，其余视为新密钥 */
    private String resolveApiKey(String incoming, String existing) {
        if (isBlank(incoming)) {
            return existing;
        }
        String v = incoming.trim();
        return v.equals(mask(existing)) ? existing : v;
    }

    private LlmConfigVO toVO(LlmConfig cfg) {
        LlmConfigVO vo = new LlmConfigVO();
        vo.setEnabled(cfg.enabled());
        vo.setProvider(cfg.provider());
        vo.setBaseUrl(cfg.baseUrl());
        vo.setApiKeySet(!isBlank(cfg.apiKey()));
        vo.setApiKeyMask(mask(cfg.apiKey()));
        vo.setModel(cfg.model());
        vo.setTemperature(cfg.temperature());
        vo.setTimeout(cfg.timeout());
        vo.setAvailable(llmClient.isAvailable());
        return vo;
    }

    /** 掩码：保留通道前缀（如 sk-）与末 4 位，形如 {@code sk-****abcd} */
    private String mask(String key) {
        if (isBlank(key)) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 8) {
            return "****";
        }
        String head = k.startsWith("sk-") ? "sk-" : k.substring(0, 3);
        return head + "****" + k.substring(k.length() - 4);
    }

    /** 异常信息脱敏：三方 SDK 常把密钥回显在错误里（如 "Incorrect API key: sk-xxx"） */
    private String sanitize(String message, String apiKey) {
        if (message == null || message.isBlank()) {
            return "无法连接模型服务";
        }
        String out = message;
        if (!isBlank(apiKey)) {
            out = out.replace(apiKey.trim(), mask(apiKey));
        }
        return out.length() > 300 ? out.substring(0, 300) + "…" : out;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
