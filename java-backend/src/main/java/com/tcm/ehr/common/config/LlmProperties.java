package com.tcm.ehr.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 增强配置（前缀 {@code llm}）。
 *
 * <p>与 {@code nlp.enabled} 同一口径：<b>默认关闭</b>。关闭时
 * {@link com.tcm.ehr.common.utils.LlmClient} 不做任何模型装配，主流程全部走规则/模板兜底，
 * 并且不需要任何 api-key 即可正常启动。</p>
 *
 * <p>对应 {@code application.yml} 的 {@code llm} 段。注意 Spring AI 的模型自动装配
 * （{@code spring.ai.model.*}）已被统一置为 {@code none}，模型由 LlmClient 按本配置自行构造，
 * 以避免「未配 api-key 就被自动装配急切校验凭据、导致整个上下文启动失败」。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** LLM 总开关（文档「AI 开关三清单」中的 llm.enabled；子开关 nlp.convert-enabled 依赖它） */
    private boolean enabled = false;

    /** 通道：{@code ollama}（本机，无需 api-key）或 {@code openai}（OpenAI 及兼容三方网关） */
    private String provider = "ollama";

    /** 接口地址；留空用各通道默认值（ollama: http://localhost:11434，openai: https://api.openai.com/v1） */
    private String baseUrl = "";

    /** api-key；{@code provider=openai} 时必填，留空则通道降级（不影响启动） */
    private String apiKey = "";

    /** 模型名；留空用各通道默认模型 */
    private String model = "";

    /** 采样温度 */
    private Double temperature = 0.2D;

    /** 单次请求超时（毫秒） */
    private int timeout = 60000;
}
