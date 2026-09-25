package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ScoreResultVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 终末质控评分（批B·2.3，批M 重构）。
 *
 * <p><b>核心要素（5 项）</b>：症状 / 证候 / 舌象 / 脉象 / 中药。判定<b>以结构化抽取结果为准</b>，
 * 原始列仅用于区分"真缺失"与"漏抽"：</p>
 * <ul>
 *   <li>结构化为空、原始列也空（或无对应原始列） → <b>真缺失 -12</b>；</li>
 *   <li>结构化为空、但原始列有值 → <b>漏抽 -6</b>（病历其实写了，可能未被抽取）；</li>
 *   <li>结构化非空 → 不扣分。</li>
 * </ul>
 *
 * <p>治法、方剂<b>不参与评分</b>（数据源无该两列，抽取恒空，纳入会一刀切）。</p>
 *
 * <p>其余：逻辑冲突每条 -10；年龄/性别格式错误 -5；重复数据 -5。满分 100，最低 0。</p>
 *
 * <p>分级：真缺失 ≥3 或 分数 &lt;60 → 无效；有冲突 或 60≤分&lt;90 → 待复核；无冲突且 ≥90 → 合格。
 * {@code structured_data} 缺失/解析失败时 {@code structuredMissing=true}，此时各核心按"漏抽"口径扣分，
 * 自然落入待复核，不额外硬扣。</p>
 */
public final class QcScorer {

    private static final Pattern NUMERIC = Pattern.compile("^\\d+(\\.\\d+)?(岁|个月|月|天)?$");

    /** 核心要素（5 项） */
    private static final List<String> CORE = List.of("症状", "证候", "舌象", "脉象", "中药");

    /** 真缺失：结构化与原始列均无 */
    private static final int MISS_FULL = 12;
    /** 漏抽：原始列有、结构化为空 */
    private static final int MISS_PARTIAL = 6;
    /** 逻辑冲突每条扣分 */
    public static final int W_LOGIC = 10;
    /** 格式错误扣分 */
    public static final int W_FORMAT = 5;
    /** 重复数据扣分 */
    public static final int W_DUPLICATE = 5;
    /** 合格线 */
    public static final int QUALIFIED = 90;
    /** 无效线（低于即无效） */
    public static final int INVALID = 60;
    /** 真缺失达到该数判严重（直接无效） */
    public static final int SERIOUS_FULL = 3;

    private QcScorer() {
    }

    /** 核心要素清单（供只读接口下发，单一数据源） */
    public static List<String> coreFields() {
        return CORE;
    }

    public static int missFull() {
        return MISS_FULL;
    }

    public static int missPartial() {
        return MISS_PARTIAL;
    }

    public static ScoreResultVO score(Map<String, Object> data, Record raw, boolean duplicate) {
        ScoreResultVO vo = new ScoreResultVO();
        vo.setCheckedAt(LocalDateTime.now().withNano(0));
        List<ScoreResultVO.Deduction> ded = new ArrayList<>();

        boolean structuredMissing = data == null;
        vo.setStructuredMissing(structuredMissing);

        // ① 核心要素（两档扣分）
        int fullMissing = 0;
        for (String field : CORE) {
            if (structuredPresent(field, data)) {
                continue;
            }
            boolean rawHas = rawPresent(field, raw);
            int points = rawHas ? MISS_PARTIAL : MISS_FULL;
            if (!rawHas) {
                fullMissing++;
            }
            ded.add(new ScoreResultVO.Deduction("核心字段缺失", field, points, reasonFor(field, rawHas)));
        }

        // ② 逻辑冲突（单源规则表）
        List<String> conflicts = LogicChecker.check(strList(data, "patternList"),
                strList(data, "treatmentList"), strList(data, "formulaList"),
                strList(data, "tongueList"), strList(data, "pulseList"));
        for (String c : conflicts) {
            ded.add(new ScoreResultVO.Deduction("逻辑冲突", c.split("：")[0], W_LOGIC, c));
        }
        vo.setLogicConflicts(conflicts);

        // ③ 格式 / 重复（各 -5）
        if (raw != null) {
            String age = trim(raw.getAge());
            if (age != null && !NUMERIC.matcher(age).matches()) {
                ded.add(new ScoreResultVO.Deduction("格式错误", "年龄", W_FORMAT, "年龄格式不正确：" + age));
            }
            String gender = trim(raw.getGender());
            if (gender != null && !"男".equals(gender) && !"女".equals(gender)) {
                ded.add(new ScoreResultVO.Deduction("格式错误", "性别", W_FORMAT, "性别非 男/女：" + gender));
            }
        }
        if (duplicate) {
            ded.add(new ScoreResultVO.Deduction("重复数据", "重复标记", W_DUPLICATE, "与已有病历内容完全一致"));
        }

        int totalDeduct = ded.stream().mapToInt(ScoreResultVO.Deduction::getPoints).sum();
        int score = Math.max(0, 100 - totalDeduct);

        boolean serious = fullMissing >= SERIOUS_FULL;
        String grade;
        if (serious || score < INVALID) {
            grade = "无效";
        } else if (!conflicts.isEmpty()) {
            grade = "待复核";
        } else if (score >= QUALIFIED) {
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

    /** 核心要素在结构化结果里是否非空 */
    private static boolean structuredPresent(String field, Map<String, Object> data) {
        return switch (field) {
            case "症状" -> !listEmpty(data, "symptoms");
            case "证候" -> !listEmpty(data, "patternList");
            case "舌象" -> !listEmpty(data, "tongueList");
            case "脉象" -> !listEmpty(data, "pulseList");
            case "中药" -> !listEmpty(data, "herbs");
            default -> false;
        };
    }

    /** 核心要素是否有对应原始列且有值（症状无对应原始列） */
    private static boolean rawPresent(String field, Record raw) {
        if (raw == null) {
            return false;
        }
        return switch (field) {
            case "证候" -> !blank(raw.getPattern());
            case "舌象" -> !blank(raw.getTongue());
            case "脉象" -> !blank(raw.getPulse());
            case "中药" -> !blank(raw.getPrescription());
            default -> false;
        };
    }

    /**
     * 缺失原因文案（直接展示给最终用户，只写业务措辞，不出现 structuredData 字段名）。
     *
     * @param rawHas 原始病历是否有记录（区分"漏抽"与"真缺失"）
     */
    private static String reasonFor(String field, boolean rawHas) {
        if (rawHas) {
            return "结构化结果中无" + field + "（原始病历有记录，可能未被抽取）";
        }
        return switch (field) {
            case "症状" -> "未记录任何症状";
            default -> "结构化结果与原始病历均无" + field + "记录";
        };
    }

    /** structured 中该字段为空数组 / 缺失 → 视为空 */
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
