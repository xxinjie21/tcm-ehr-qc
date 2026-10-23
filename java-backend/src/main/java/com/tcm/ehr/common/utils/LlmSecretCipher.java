package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.ServiceNotReadyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * LLM 密钥的落库加密（AES-256-GCM）。
 *
 * <p>为什么必须加密落库：配置改成「每个用户一份」后，密钥会跟着每个用户
 * 落进数据库。裸存意味着「拿到库就能拿到所有人的三方密钥」。</p>
 *
 * <p>为什么用 GCM 而不是 CBC：GCM 带认证标签，密文被改动会在解密时报错，
 * 而不是悄悄解出一段垃圾明文。</p>
 *
 * <p><b>加解密交给 spring-security-crypto 的 {@link Encryptors}</b>（《自研组件替换计划》#2）：
 * 原实现手写 javax.crypto 的 IV 生成、拼接与 Base64 编解码，属重复实现通用能力。
 * 现在用 {@code Encryptors.delux(password, salt)} —— AES-256-GCM，密钥由 password + salt
 * 经 PBKDF2 派生，密文为 {@code hex(IV‖密文‖tag)}（每次加密随机 IV）。</p>
 *
 * <p><b>配置形态随之改变（raw key → password + salt）</b>：
 * 口令取自环境变量 TCM_LLM_ENC_KEY（其次配置项 {@code llm.crypto-password}），
 * 盐取自 TCM_LLM_ENC_SALT（其次 {@code llm.crypto-salt}，为十六进制偶数长度）。
 * 口令不再要求「32 字节 Base64」，任意非空字符串均可。两者任一缺失或盐不是合法 hex 时
 * {@link #available()} 为 false —— <b>不阻塞启动</b>，只是无法解密已有密文（按「未配置密钥」处理）。</p>
 *
 * <p><b>换密钥的代价</b>：GCM 密文绑定密钥，换密钥后旧密文解不开。此时
 * {@link #decrypt} 返回空串并告警一次，用户重新填一次密钥即可恢复。</p>
 */
@Slf4j
@Component
public class LlmSecretCipher {

    /** null 表示未就绪（未配置口令 / 未配置盐 / 盐非法） */
    private final TextEncryptor encryptor;
    private final boolean available;

    public LlmSecretCipher(@Value("${llm.crypto-password:}") String password,
                           @Value("${llm.crypto-salt:}") String salt) {
        TextEncryptor built = null;
        if (TextUtil.isBlank(password)) {
            log.warn("[LLM] 未配置加密口令（环境变量 TCM_LLM_ENC_KEY 或配置 llm.crypto-password）："
                    + "用户 LLM 密钥将无法落库加密保存，服务照常启动");
        } else if (TextUtil.isBlank(salt)) {
            log.warn("[LLM] 未配置加密盐（环境变量 TCM_LLM_ENC_SALT 或配置 llm.crypto-salt）："
                    + "用户 LLM 密钥将无法落库加密保存，服务照常启动");
        } else {
            try {
                built = Encryptors.delux(password, salt);
            } catch (RuntimeException e) {
                // 盐不是偶数长度 hex 等配置错误：与「没配」表现一致即可，但必须留日志 ——
                // 否则「保存时报未配置密钥」查不出原因。绝不因此让应用起不来。
                log.warn("[LLM 密钥] 加密配置非法（口令/盐），按「未配置」处理: {}", e.getMessage());
            }
        }
        this.encryptor = built;
        this.available = built != null;
    }

    /** 加密密钥是否就绪；false 时保存会直接拒绝（而不是明文落库） */
    public boolean available() {
        return available;
    }

    /**
     * 加密明文。
     *
     * @param plain 明文密钥；空串/null 视为「未配置」，返回 null
     * @return hex(IV‖密文‖tag)；未配置返回 null；未就绪抛 {@link ServiceNotReadyException}（503 + code=1011，消息可展示）
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        if (!available) {
            throw new ServiceNotReadyException("未配置 LLM 加密密钥，无法保存密钥（请联系管理员配置 TCM_LLM_ENC_KEY）");
        }
        try {
            return encryptor.encrypt(plain);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 密钥加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @param cipherText hex(IV‖密文‖tag)
     * @return 明文；null/空、或解密失败（换过密钥 / 数据损坏 / 旧格式密文）返回空串 —— 调用方按「未配置密钥」处理
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return "";
        }
        if (!available) {
            return "";
        }
        try {
            return encryptor.decrypt(cipherText);
        } catch (Exception e) {
            // 解不开最常见的原因是换过加密密钥（或库里仍是旧实现的 Base64 密文）：告警一次，
            // 按未配置处理让用户重填，而不是把异常抛给调用方。
            log.warn("[LLM] 密钥解密失败（可能更换过加密密钥），按「未配置密钥」处理：{}", e.getMessage());
            return "";
        }
    }
}
