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

    /**
     * LLM 总开关（文档「AI 开关三清单」中的 llm.enabled；子开关 llm.convert-enabled 依赖它）。
     *
     * <p>注意：这里只是<b>启动基线</b>，运行时会被 {@code LlmConfigStore}（页面上「导入 LLM」）覆盖。
     * {@code llm.convert-enabled} 刻意不并进本类 —— 它是静态 {@code @Value}、改完要重启，
     * 与本类「可被运行时覆盖的连接参数」不是一套机制。</p>
     */
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

    /**
     * 运行时"非密钥"配置的持久化文件（UX-68 增强）。
     *
     * <p>「导入 LLM」保存后，enabled/provider/baseUrl/model/temperature/timeout 会写入此文件，
     * 重启后回显；<b>api-key 不写入</b>（只存内存，需每次填写）。</p>
     */
    private String configFile = "data/llm-config.json";
}
