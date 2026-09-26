package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 病历公共工具。
 *
 * <p>原始文本去重哈希口径由数据清洗（{@code GovernanceServiceImpl}）与病历导入
 * （{@code RecordServiceImpl}）共用，避免两处各写一份导致去重标准漂移。</p>
 */
public final class RecordUtil {

    private RecordUtil() {
    }

    /**
     * 原始文本哈希（21 字段固定顺序拼接 → MD5(UTF-8) 32 位十六进制），用于去重。
     *
     * <p><b>字段顺序（勿改，变更需回归去重，否则历史数据会被误判）</b>：
     * registrationNo → outpatientNo → gender → age → westernDiagnosis → tcmDiagnosis →
     * presentIllness → chiefComplaint → selfReport → inspection → pulse → tongue →
     * physicalExam → pattern → prescription → followUp → treatmentEffect → department →
     * doctorId → visitCount → visitTime</p>
     *
     * <p>原实现用 {@code String.hashCode()}（32 位 int，碰撞率高），改为 MD5(UTF-8)。</p>
     */
    public static String textHash(Record r) {
        String joined = String.join("|",
                nvl(r.getRegistrationNo()), nvl(r.getOutpatientNo()), nvl(r.getGender()), nvl(r.getAge()),
                nvl(r.getWesternDiagnosis()), nvl(r.getTcmDiagnosis()), nvl(r.getPresentIllness()),
                nvl(r.getChiefComplaint()), nvl(r.getSelfReport()), nvl(r.getInspection()),
                nvl(r.getPulse()), nvl(r.getTongue()), nvl(r.getPhysicalExam()), nvl(r.getPattern()),
                nvl(r.getPrescription()), nvl(r.getFollowUp()), nvl(r.getTreatmentEffect()),
                nvl(r.getDepartment()), nvl(r.getDoctorId()),
                nvl(r.getVisitCount() == null ? null : String.valueOf(r.getVisitCount())),
                nvl(r.getVisitTime() == null ? null : r.getVisitTime().toString()));
        return md5Hex(joined);
    }

    /** MD5(UTF-8) → 32 位小写十六进制 */
    public static String md5Hex(String s) {
        try {
            // 固定 UTF-8 编码：编码不同则同一内容算出不同哈希
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // MD5 为 JDK 必备算法，正常不会发生
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }
}
