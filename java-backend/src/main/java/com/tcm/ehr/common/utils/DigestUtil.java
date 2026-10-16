package com.tcm.ehr.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要工具：{@code MessageDigest} → 小写十六进制串。
 *
 * <p>{@link RecordUtil#md5Hex}（病历去重哈希）与 {@code ReviewTaskUtil.fingerprint}
 * （复核读时指纹）原各写一份「取 MessageDigest → digest → HexFormat → NoSuchAlgorithmException
 * 兜底」，收敛到此处一处实现（审查报告 §2.6）。</p>
 *
 * <p>刻意<b>不</b>用 Spring 的 {@code DigestUtils} 统一：它只有 MD5 系列、没有 SHA-256
 * （javap 实证），覆盖不了复核指纹那一路。</p>
 */
public final class DigestUtil {

    private DigestUtil() {
    }

    /**
     * 对 {@code UTF-8} 编码的文本求摘要，返回小写十六进制串。
     *
     * <p>编码固定 UTF-8：编码不同则同一内容算出不同哈希。</p>
     *
     * @param algorithm JDK 算法名，如 {@code "MD5"} / {@code "SHA-256"}
     * @param text      待摘要文本
     * @return 小写十六进制摘要串
     * @throws IllegalStateException 该算法不可用。MD5 / SHA-256 均为 JDK 必备算法，正常走不到；
     *                               真拿不到也不能退化成弱哈希或常量指纹
     */
    public static String hex(String algorithm, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " 算法不可用", e);
        }
    }
}
