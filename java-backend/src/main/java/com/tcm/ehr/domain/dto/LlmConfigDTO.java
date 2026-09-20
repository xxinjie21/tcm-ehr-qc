package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * LLM 配置入参（UX-68，{@code PUT /api/llm/config} 与 {@code POST /api/llm/test} 共用）。
 *
 * <p>字段为「可空 = 沿用当前值」语义：前端弹窗只提交用户改过的项也不会把其他项清空。
 * {@code apiKey} 传空串或回传掩码均表示<b>不修改</b>密钥，避免用户看不到明文就无法保存。</p>
 */
@Data
public class LlmConfigDTO {

    /** 是否启用 LLM；null = 沿用当前 */
    private Boolean enabled;

    /** 通道：ollama | openai；null / 空 = 沿用当前 */
    private String provider;

    /** 接口地址；留空用通道默认 */
    private String baseUrl;

    /** api-key；空串或掩码 = 不修改 */
    private String apiKey;

    /** 模型名；留空用通道默认模型 */
    private String model;

    /** 采样温度（0~2） */
    private Double temperature;

    /** 单次请求超时（毫秒） */
    private Integer timeout;
}
