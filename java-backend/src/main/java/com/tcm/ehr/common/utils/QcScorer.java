package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ScoreResultVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 终末质控评分（批B·2.3，文档9.2 calculateScore）。
 *
 * <p>三层扣分：① 核心字段缺失各 -15；② 逻辑冲突各 -10；③ 格式/重复/症状为空各 -5。
 * 满分 100，最低扣至 0。严重问题 / &lt;60 → 无效；≥90 且无逻辑冲突 → 合格；其余 → 待复核。
 * <b>分级与分数独立判定</b>：invalid 仍保留扣完的分数（仅用于统计）。</p>
 */
public final class QcScorer {

    private static final Pattern NUMERIC = Pattern.compile("^\\d+(\\.\\d+)?(岁|个月|月|天)?$");

    /** 核心字段（6 项），缺失各 -15 */
    private static final List<String> CORE = List.of("脉象", "舌象", "证候", "治法", "方剂", "中药");

    private QcScorer() {
    }

    public static ScoreResultVO score(Map<String, Object> data, Record raw, boolean duplicate) {
        ScoreResultVO vo = new ScoreResultVO();
        vo.setCheckedAt(LocalDateTime.now().withNano(0));
        List<ScoreResultVO.Deduction> ded = new ArrayList<>();

        // ① 核心字段缺失（structured 非空优先；为空回退原始列）
        int coreMissing = 0;
        for (String field : CORE) {
            if (isCoreMissing(field, data, raw)) {
                coreMissing++;
                ded.add(new ScoreResultVO.Deduction("核心字段缺失", field, 15,
                        reasonFor(field)));
            }
        }

        // ② 逻辑冲突（单源规则表）
        List<String> conflicts = LogicChecker.check(strList(data, "patternList"),
                strList(data, "treatmentList"), strList(data, "formulaList"),
                strList(data, "tongueList"), strList(data, "pulseList"));
        for (String c : conflicts) {
            ded.add(new ScoreResultVO.Deduction("逻辑冲突", c.split("：")[0], 10, c));
        }
        vo.setLogicConflicts(conflicts);

        // ③ 格式 / 重复 / 症状为空（各 -5）
        if (raw != null) {
            String age = trim(raw.getAge());
            if (age != null && !NUMERIC.matcher(age).matches()) {
                ded.add(new ScoreResultVO.Deduction("格式错误", "年龄", 5, "age 非数字：" + age));
            }
            String gender = trim(raw.getGender());
            if (gender != null && !"男".equals(gender) && !"女".equals(gender)) {
                ded.add(new ScoreResultVO.Deduction("格式错误", "性别", 5, "性别非 男/女：" + gender));
            }
        }
        if (strList(data, "symptoms").isEmpty()) {
            ded.add(new ScoreResultVO.Deduction("症状为空", "症状", 5, "symptoms 为空"));
        }
        if (duplicate) {
            ded.add(new ScoreResultVO.Deduction("重复数据", "文本MD5", 5, "与已评分病历 21 字段完全一致"));
        }

        int totalDeduct = ded.stream().mapToInt(ScoreResultVO.Deduction::getPoints).sum();
        int score = Math.max(0, 100 - totalDeduct);

        boolean bothConflict = hasConflictType(conflicts, LogicChecker.TYPE_TREATMENT)
                && hasConflictType(conflicts, LogicChecker.TYPE_FORMULA);
        boolean unparseable = data == null && raw != null && raw.getStructuredData() != null
                && !raw.getStructuredData().isBlank();
        boolean serious = coreMissing >= 3 || bothConflict || unparseable;

        String grade;
        if (serious || score < 60) {
            grade = "无效";
        } else if (!conflicts.isEmpty()) {
            grade = "待复核";
        } else if (score >= 90) {
            grade = "合格";
        } else {
            grade = "待复核";
        }

        vo.setScore(score);
        vo.setGrade(grade);
        vo.setSerious(serious);
        vo.setDeductions(ded);
        return vo;
    }

    private static boolean hasConflictType(List<String> conflicts, String type) {
        return conflicts.stream().anyMatch(c -> c.startsWith(type));
    }

    private static boolean isCoreMissing(String field, Map<String, Object> data, Record raw) {
        return switch (field) {
            case "脉象" -> listEmpty(data, "pulseList") && (raw == null || blank(raw.getPulse()));
            case "舌象" -> listEmpty(data, "tongueList") && (raw == null || blank(raw.getTongue()));
            case "证候" -> listEmpty(data, "patternList") && (raw == null || blank(raw.getPattern()));
            case "治法" -> listEmpty(data, "treatmentList");
            case "方剂" -> listEmpty(data, "formulaList");
            case "中药" -> listEmpty(data, "herbs") && (raw == null || blank(raw.getPrescription()));
            default -> false;
        };
    }

    /**
     * 缺失原因文案。
     *
     * <p><b>返回值会直接展示给最终用户</b>（质控扣分明细的「原因」列），
     * 因此只能写业务措辞，<b>不得出现 structuredData 字段名</b>（如 `formulaList` / `patternList`）——
     * 医生看不懂字段名，也无法据此判断该补什么（见 UX-55）。</p>
     */
    private static String reasonFor(String field) {
        return switch (field) {
            case "脉象" -> "结构化数据与原始病历均无脉象记录";
            case "舌象" -> "结构化数据与原始病历均无舌象记录";
            case "证候" -> "结构化数据与原始病历均无证候结论";
            case "治法" -> "结构化数据中无治法";
            case "方剂" -> "结构化数据中无方剂";
            case "中药" -> "结构化数据与原始病历均无中药记录";
            default -> "缺失";
        };
    }

    /** structured 中该字段为空数组 / 缺失 → 视为缺（结构化非空判断） */
    private static boolean listEmpty(Map<String, Object> data, String key) {
        if (data == null || !(data.get(key) instanceof List<?> list)) {
            return true;
        }
        return list.isEmpty();
    }

    /** 取实体列表的 content（herbs 取 name）文本 */
    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> data, String key) {
        List<String> out = new ArrayList<>();
        if (data == null || !(data.get(key) instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object c = m.get("content") != null ? m.get("content") : m.get("name");
                if (c != null && !String.valueOf(c).isBlank()) {
                    out.add(String.valueOf(c).trim());
                }
            } else if (item != null && !String.valueOf(item).isBlank()) {
                out.add(String.valueOf(item).trim());
            }
        }
        return out;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
