package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ScoreResultVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 终末质控评分。
 *
 * <p>评分维度全部来自规则集：完整性（要素清单，真缺失/漏抽两档）、格式规则、逻辑一致性、
 * 术语标准化、重复；分级阈值同样来自规则集。规则声明依赖要素；要素缺失则跳过（不适用）。</p>
 */
public final class QcScorer {

    private QcScorer() {
    }

    /**
     * 对单份病历做终末质控评分，输出得分、分级与逐条扣分。
     *
     * <p>依次累计五类扣分：完整性（要素缺失分真缺失 / 漏抽两档）、逻辑一致性、格式、
     * 术语标准化、重复；分级取「严重缺失 / 低于无效线 / 存在逻辑冲突 / 达到合格线」中
     * 优先级最高的一档。规则集为 {@code null} 时回退到内置默认规则。</p>
     *
     * @param data 结构化抽取结果，{@code null} 表示未结构化（标记为缺失）
     * @param raw 原始病历，{@code null} 时跳过格式规则
     * @param duplicate 是否与已有病历重复
     * @param rules 质控规则集，可为 {@code null}
     * @return 评分结果（得分、分级、是否严重、扣分明细）
     */
    public static ScoreResultVO score(Map<String, Object> data, Record raw, boolean duplicate,
                                      com.tcm.ehr.common.config.QcRuleSet rules) {
        com.tcm.ehr.common.config.QcRuleSet rs = rules == null ? com.tcm.ehr.common.config.QcRuleSet.defaults() : rules;
        ScoreResultVO vo = new ScoreResultVO();
        vo.setCheckedAt(LocalDateTime.now().withNano(0));
        List<ScoreResultVO.Deduction> ded = new ArrayList<>();

        boolean structuredMissing = data == null;
        vo.setStructuredMissing(structuredMissing);

        // ① 完整性（两档）
        int fullMissing = 0;
        for (com.tcm.ehr.common.config.QcRuleSet.Element el : rs.getCompleteness().getElements()) {
            if (structuredPresent(el.getSource(), data)) {
                continue;
            }
            boolean rawHas = rawPresent(el.getFallback(), raw);
            int points = rawHas ? el.getWeightPartial() : el.getWeightFull();
            if (!rawHas) {
                fullMissing++;
            }
            ded.add(new ScoreResultVO.Deduction("核心字段缺失", el.getName(), points, reasonFor(el, rawHas)));
        }

        // ② 逻辑一致性
        List<String> conflicts = LogicChecker.check(data, rs.getConsistency());
        for (String c : conflicts) {
            String name = c.contains("：") ? c.substring(0, c.indexOf("：")) : c;
            int w = weightOf(rs, name);
            ded.add(new ScoreResultVO.Deduction("逻辑冲突", name, w, c));
        }
        vo.setLogicConflicts(conflicts);

        // ③ 格式
        if (raw != null) {
            for (com.tcm.ehr.common.config.QcRuleSet.FormatRule fr : rs.getFormat()) {
                String v = rawValue(raw, fr.getField());
                if (v == null) {
                    continue;
                }
                if (!formatOk(fr, v)) {
                    String label = fr.getLabel() == null ? fr.getField() : fr.getLabel();
                    String reason = (fr.getReason() == null ? label + "格式不正确" : fr.getReason()) + "：" + v;
                    ded.add(new ScoreResultVO.Deduction("格式错误", label, fr.getWeight(), reason));
                }
            }
        }

        // ④ 术语标准化
        com.tcm.ehr.common.config.QcRuleSet.Standardization st = rs.getStandardization();
        if (st.isEnabled() && data != null && !st.getElementTypes().isEmpty()) {
            int miss = 0;
            for (String type : st.getElementTypes()) {
                miss += countUnnormalized(data, type);
            }
            if (miss > 0) {
                int points = Math.min(st.getCap(), miss * st.getWeightEach());
                ded.add(new ScoreResultVO.Deduction("术语未标准化", "未命中词典",
                        points, "有 " + miss + " 个实体未命中标准词典"));
            }
        }

        // ⑤ 重复
        if (duplicate) {
            ded.add(new ScoreResultVO.Deduction("重复数据", "重复标记", rs.getDuplicateWeight(), "与已有病历内容完全一致"));
        }

        int totalDeduct = ded.stream().mapToInt(ScoreResultVO.Deduction::getPoints).sum();
        int score = Math.max(0, 100 - totalDeduct);

        com.tcm.ehr.common.config.QcRuleSet.Thresholds th = rs.getThresholds();
        boolean serious = fullMissing >= th.getSeriousFullMissing();
        String grade;
        if (serious || score < th.getInvalid()) {
            grade = "无效";
        } else if (!conflicts.isEmpty()) {
            grade = "待复核";
        } else if (score >= th.getQualified()) {
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

    /**
     * 「核心要素」的缺失情况，分两档。
     *
     * <p><b>与 {@link #score} 的完整性扣分同源</b>：同一份要素清单
     * （{@code rules.getCompleteness().getElements()}）、同一套判空原语
     * （{@link #structuredPresent} / {@link #rawPresent}）。存在的意义是让 AI 解读 / 助手
     * 不再手写一份要素清单 —— 那正是「同一份病历质控说缺、AI 说齐全」的根因。</p>
     *
     * @param full <b>真缺失</b>：结构化结果与原始列都没有记录
     * @param partial <b>漏抽</b>：结构化结果里没有，但原始列有记录（可能未被抽取）
     */
    public record Missing(List<String> full, List<String> partial) {
    }

    /** 按规则集判定核心要素缺失；{@code rules} 为 null 时用内置默认（与 score 一致） */
    public static Missing missingElements(Map<String, Object> data, Record raw,
                                          com.tcm.ehr.common.config.QcRuleSet rules) {
        com.tcm.ehr.common.config.QcRuleSet rs =
                rules == null ? com.tcm.ehr.common.config.QcRuleSet.defaults() : rules;
        List<String> full = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        for (com.tcm.ehr.common.config.QcRuleSet.Element el : rs.getCompleteness().getElements()) {
            if (structuredPresent(el.getSource(), data)) {
                continue;
            }
            String name = el.getName();
            if (rawPresent(el.getFallback(), raw)) {
                partial.add(name);
            } else {
                full.add(name);
            }
        }
        return new Missing(full, partial);
    }

    private static int weightOf(com.tcm.ehr.common.config.QcRuleSet rs, String name) {
        for (com.tcm.ehr.common.config.QcRuleSet.ConsistencyRule c : rs.getConsistency()) {
            if (c.getName() != null && c.getName().equals(name)) {
                return c.getWeight();
            }
        }
        return 10;
    }

    private static boolean formatOk(com.tcm.ehr.common.config.QcRuleSet.FormatRule fr, String v) {
        if ("enum".equalsIgnoreCase(fr.getType())) {
            return fr.getValues() != null && fr.getValues().contains(v);
        }
        if (fr.getExpr() == null || fr.getExpr().isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(fr.getExpr()).matcher(v).matches();
        } catch (Exception e) {
            return true; // 表达式非法则该项不判，避免误伤
        }
    }

    private static boolean structuredPresent(String key, Map<String, Object> data) {
        if (key == null || data == null) {
            return false;
        }
        return data.get(key) instanceof List<?> list && !list.isEmpty();
    }

    private static boolean rawPresent(List<String> fields, Record raw) {
        if (raw == null || fields == null) {
            return false;
        }
        for (String f : fields) {
            String v = rawValue(raw, f);
            if (v != null) {
                return true;
            }
        }
        return false;
    }

    /** 原始列取值（按 Record 属性名） */
    private static String rawValue(Record r, String field) {
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
            case "department" -> r.getDepartment();
            case "doctorId" -> r.getDoctorId();
            default -> null;
        };
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 术语类型 → 结构化 key */
    private static String keyOf(String type) {
        return switch (type) {
            case "disease" -> "diseases";
            case "pattern" -> "patternList";
            case "symptom" -> "symptoms";
            case "herb" -> "herbs";
            case "formula" -> "formulaList";
            default -> null;
        };
    }

    /** 该类型下未命中词典（无 normLevel）的实体数 */
    private static int countUnnormalized(Map<String, Object> data, String type) {
        String key = keyOf(type);
        if (key == null || !(data.get(key) instanceof List<?> list)) {
            return 0;
        }
        int n = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object lv = m.get("normLevel");
                if (lv == null) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String reasonFor(com.tcm.ehr.common.config.QcRuleSet.Element el, boolean rawHas) {
        if (rawHas) {
            return "结构化结果中无" + el.getName() + "（原始病历有记录，可能未被抽取）";
        }
        return "结构化结果与原始病历均无" + el.getName() + "记录";
    }

    /** 取实体列表的 content（herbs 取 name）文本 */
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
}
