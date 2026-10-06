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
     * 按属性名取病历的列值（空白视作无值）。
     *
     * <p>为什么放这里：质控评分（{@code QcScorer}）与质控明细（{@code QcServiceImpl}）
     * 各写了一份「属性名 → 列值」的 switch，映射同一个 Record，而两份**覆盖不同**：
     * 明细那份没有 department/doctorId 两个 case。规则里引用这两个字段时，明细侧会
     * 静默取到 null —— 看上去像"这条病历没填"，而不是"程序没认这个字段名"。
     * 两份的取值与空白口径逐字相同，合并后只留一处。</p>
     *
     * @param r     病历，可为 null
     * @param field Record 属性名（由规则集配置），可为 null
     * @return 去掉首尾空白后的列值；病历/字段名为 null、字段名未登记、或值全是空白时返回 null
     */
    public static String column(Record r, String field) {
        if (r == null || field == null) {
            return null;
        }
        String v = switch (field) {
            case "registrationNo" -> r.getRegistrationNo();
            case "outpatientNo" -> r.getOutpatientNo();
            case "gender" -> r.getGender();
            case "age" -> r.getAge();
            case "westernDiagnosis" -> r.getWesternDiagnosis();
            case "tcmDiagnosis" -> r.getTcmDiagnosis();
            case "presentIllness" -> r.getPresentIllness();
            case "chiefComplaint" -> r.getChiefComplaint();
            case "selfReport" -> r.getSelfReport();
            case "inspection" -> r.getInspection();
            case "pulse" -> r.getPulse();
            case "tongue" -> r.getTongue();
            case "physicalExam" -> r.getPhysicalExam();
            case "pattern" -> r.getPattern();
            case "prescription" -> r.getPrescription();
            case "followUp" -> r.getFollowUp();
            case "treatmentEffect" -> r.getTreatmentEffect();
            // 这两个 case 原先只有 QcScorer 那份有（本次收敛的由来）
            case "department" -> r.getDepartment();
            case "doctorId" -> r.getDoctorId();
            default -> null;
        };
        // 空白视作无值，避免把空格当内容做存在性判断
        return v == null || v.isBlank() ? null : v.trim();
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
                // 接诊时间先截到秒再参与哈希：visit_time 列是 DATETIME（无小数秒），
                // 直接 toString() 会把亚秒带上，于是「入库前算的哈希」与「从库读回重算的哈希」
                // 不相等 —— 去重预筛失效，还会撞唯一键
                nvl(r.getVisitTime() == null ? null : r.getVisitTime().withNano(0).toString()));
        return md5Hex(joined);
    }

    /** MD5(UTF-8) → 32 位小写十六进制 */
    public static String md5Hex(String s) {
        // 1. 固定 UTF-8 编码：编码不同则同一内容算出不同哈希
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // 2. MD5 为 JDK 必备算法，真拿不到就抛（不能退化成弱哈希）
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }
}
