package com.tcm.ehr.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * LLM 密钥的落库加密（AES-256-GCM）。
 *
 * <p><b>为什么必须加密落库</b>：配置改成「每个用户一份」后，密钥会跟着每个用户
 * 落进数据库。裸存意味着「拿到库就能拿到所有人的三方密钥」。</p>
 *
 * <p><b>为什么用 GCM 而不是 CBC</b>：GCM 带认证标签，密文被改动会在解密时报错，
 * 而不是悄悄解出一段垃圾明文。</p>
 *
 * <p><b>密钥来源</b>：环境变量 {@code TCM_LLM_ENC_KEY}（32 字节 Base64）优先，
 * 其次配置项 {@code llm.crypto-key}；两者都没有时 {@link #available()} 为 false，
 * 此时<b>不阻塞启动</b>，只是无法解密已有密文（按「未配置密钥」处理）。</p>
 *
 * <p><b>换密钥的代价</b>：GCM 密文绑定密钥，换密钥后旧密文解不开。此时
 * {@link #decrypt} 返回空串并告警一次，用户重新填一次密钥即可恢复。</p>
 */
@Slf4j
@Component
public class LlmSecretCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final boolean available;

    public LlmSecretCipher(@Value("${llm.crypto-key:}") String cryptoKey) {
        byte[] raw = resolveKey(cryptoKey);
        if (raw == null) {
            this.key = null;
            this.available = false;
            log.warn("[LLM] 未配置加密密钥（环境变量 TCM_LLM_ENC_KEY 或配置 llm.crypto-key）："
                    + "用户 LLM 密钥将无法落库加密保存，服务照常启动");
        } else {
            this.key = new SecretKeySpec(raw, "AES");
            this.available = true;
        }
    }

    /** 读环境变量优先，其次配置项；都不是合法 32 字节就返回 null */
    private byte[] resolveKey(String fromProperties) {
        String b64 = System.getenv("TCM_LLM_ENC_KEY");
        if (b64 == null || b64.isBlank()) {
            b64 = fromProperties;
        }
        if (b64 == null || b64.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(b64.trim());
            return raw.length * 8 == KEY_BITS ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 加密密钥是否就绪；false 时保存会直接拒绝（而不是明文落库） */
    public boolean available() {
        return available;
    }

    /**
     * 加密明文。
     *
     * @param plain 明文密钥；空串/null 视为「未配置」，返回 null
     * @return Base64(IV ‖ 密文 ‖ tag)；未配置返回 null；未就绪抛 IllegalStateException
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        if (!available) {
            throw new IllegalStateException("未配置 LLM 加密密钥，无法保存密钥（请联系管理员配置 TCM_LLM_ENC_KEY）");
        }
        try {
            // 1. 每次都用新 IV：IV 复用会让相同明文产生相同密文，可被比对出密钥是否相同
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            // 2. IV 与密文拼在一起存，省一次列
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 密钥加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @param cipherText Base64(IV ‖ 密文 ‖ tag)
     * @return 明文；null/空、或解密失败（换过密钥 / 数据损坏）返回空串 —— 调用方按「未配置密钥」处理
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return "";
        }
        if (!available) {
            return "";
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length <= IV_LEN) {
                return "";
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, body, 0, body.length);
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解不开最常见的原因是换过加密密钥：告警一次，按未配置处理让用户重填
            log.warn("[LLM] 密钥解密失败（可能更换过加密密钥），按「未配置密钥」处理：{}", e.getMessage());
            return "";
        }
    }
}
