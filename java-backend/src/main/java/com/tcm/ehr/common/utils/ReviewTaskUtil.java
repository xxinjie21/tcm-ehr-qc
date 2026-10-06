package com.tcm.ehr.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 复核任务工具。
 *
 * <p><b>无后台定时任务</b>：超时仅由前端/出参计算属性做视觉提醒，不自动流转任务状态。</p>
 *
 * <p><b>读时指纹</b>（批次 25.15）：复核页读病历详情时由服务端按本类算一个内容哈希，
 * 提交时原样带回；写库前重算比对，不一致即 409 —— 防的是「甲读、乙读、甲先提交、
 * 乙后提交把甲的修正静默覆盖」的 lost update。</p>
 */
public final class ReviewTaskUtil {

    private ReviewTaskUtil() {
    }

    /** 是否已超复核截止时间（deadlineTime 为空视为未超时） */
    public static boolean isOverdue(LocalDateTime deadlineTime) {
        return deadlineTime != null && LocalDateTime.now().isAfter(deadlineTime);
    }

    /**
     * 复核提交的读时指纹（批次 25.15）。
     *
     * <p>纳入 {@code structuredData}（复核会覆盖的对象）与 {@code score}/{@code grade}
     * （复核重算会改写的结论）；<b>不纳入</b> {@code update_time} ——
     * {@code RecordMapper.updateStructuredData} / {@code updateScoreFields} 两条回写 SQL 都不
     * bump 它，用时间戳会比用内容更容易漏判。也不要求对 JSON 做规范化：前端把读到的
     * 指纹原样回传，服务端用同一函数对当前行重算，两边口径一致即可。</p>
     *
     * <p>null 与空串一律按空值参与哈希（不复用 {@code String.valueOf}，避免把 null 当成
     * 字面量 "null" 与真实内容 "null" 撞哈希）。</p>
     *
     * @param structuredData 结构化数据 JSON，可为 null
     * @param score          当前评分，可为 null
     * @param grade          当前分级，可为 null
     * @return SHA-256 十六进制串
     */
    public static String fingerprint(String structuredData, Integer score, String grade) {
        String raw = (structuredData == null ? "" : structuredData) + "\u0000"
                + (score == null ? "" : score) + "\u0000"
                + (grade == null ? "" : grade);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，正常走不到这里；真到了也不能返回常量指纹 ——
            // 那等于对所有病历返回同一个值，并发保护直接失效。
            throw new IllegalStateException("SHA-256 不可用，无法计算复核指纹", e);
        }
    }
}
