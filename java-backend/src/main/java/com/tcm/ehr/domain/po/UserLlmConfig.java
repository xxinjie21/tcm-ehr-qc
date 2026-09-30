package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户私有 LLM 配置（对应 {@code user_llm_config} 表）。
 *
 * <p><b>每人一行</b>：配置含三方通道密钥，是「用户自己的 LLM 设置」，
 * 不是系统级设置 —— 所以这里按 {@code user_id} 主键存，不做「全局一份」。</p>
 *
 * <p>{@link #apiKey} 存 <b>AES-256-GCM 密文</b>（Base64），明文永不落库；
 * 读取时由 {@code LlmSecretCipher} 解密。解密失败按「未配置密钥」处理。</p>
 */
@Data
@TableName("user_llm_config")
public class UserLlmConfig {

    @TableId(type = IdType.INPUT)
    private String userId;

    private Boolean enabled;

    /** ollama | openai */
    private String provider;

    private String baseUrl;

    /** 加密后的 api-key（Base64）；未配置为 null */
    private String apiKey;

    private String model;

    private Double temperature;

    private Integer timeout;

    private LocalDateTime updateTime;
}
