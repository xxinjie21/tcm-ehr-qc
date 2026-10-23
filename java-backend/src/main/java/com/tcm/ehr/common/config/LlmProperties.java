package com.tcm.ehr.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 增强配置（前缀 llm）。
 *
 * 与 nlp.enabled 同一口径：<b>默认关闭</b>。关闭时
 * {@link com.tcm.ehr.common.utils.LlmClient} 不做任何模型装配，主流程全部走规则/模板兜底，
 * 并且不需要任何 api-key 即可正常启动。
 *
 * 对应 application.yml 的 llm 段。注意 Spring AI 的模型自动装配
 * （{@code spring.ai.model.*}）已被统一置为 none，模型由 LlmClient 按本配置自行构造，
 * 以避免「未配 api-key 就被自动装配急切校验凭据、导致整个上下文启动失败」。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /**
     * LLM 总开关。
     *
     * 注意：这里只是<b>启动基线</b>，运行时会被 LlmConfigStore（页面上「导入 LLM」）覆盖。
     */
    private boolean enabled = false;

    /** 通道：ollama（本机，无需 api-key）或 openai（OpenAI 及兼容三方网关） */
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

    /**
     * 分功能的局部开关：<b>启动基线默认全部开启</b>（只受总开关控制）。
     *
     * 关掉其中任意一项，该项 AI 能力回退规则/模板兜底，其余项不受影响 ——
     * 用户只想让解读走模型、而复核意见一律走规则时，不必把整个 LLM 关掉。
     */
    private boolean interpretEnabled = true;
    private boolean reviewEnabled = true;
    private boolean termSuggestEnabled = true;

    /**
     * 可选的「非密钥」基线配置文件：默认打包在 classpath（{@code classpath:llm-config.json}），
     * 不存在即用本 yml 基线。用户的运行期配置（含加密 api-key）已按用户落库，不写本文件。
     */
    private String configFile = "classpath:llm-config.json";
}
