package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * LLM 当前生效配置。
 *
 * <p><b>密钥只回传掩码</b>：{@code apiKeyMask} 形如 {@code sk-****abcd}，明文密钥永不离开服务端，
 * 前端也不得写入 localStorage。{@code apiKeySet} 用于让弹窗提示「已配置 / 未配置」。</p>
 */
@Data
public class LlmConfigVO {

    private boolean enabled;

    /** ollama | openai */
    private String provider;

    private String baseUrl;

    /** api-key 掩码；未配置为空串 */
    private String apiKeyMask;

    /** 是否已配置 api-key */
    private boolean apiKeySet;

    private String model;

    private Double temperature;

    private int timeout;

    /** 当前是否已按生效配置装配成功（enabled 且参数合法） */
    private boolean available;

    /** 病历解读是否用 LLM（原始开关值；是否真正生效还取决于 enabled） */
    private boolean interpretEnabled;

    /** 助手问答是否用 LLM（原始开关值；是否真正生效还取决于 enabled） */
    private boolean chatEnabled;

    /** 复核意见是否用 LLM（原始开关值；是否真正生效还取决于 enabled） */
    private boolean reviewEnabled;

    /** 术语建议是否用 LLM（原始开关值；是否真正生效还取决于 enabled） */
    private boolean termSuggestEnabled;
}
