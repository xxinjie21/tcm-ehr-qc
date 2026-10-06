package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ScoreResultVO;

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
     * @param rawRecord 原始病历，{@code null} 时跳过格式规则
     * @param duplicate 是否与已有病历重复
     * @param rules 质控规则集，可为 {@code null}
     * @return 评分结果（得分、分级、是否严重、扣分明细）
     */
    public static ScoreResultVO score(Map<String, Object> data, Record rawRecord, boolean duplicate,
                                      com.tcm.ehr.common.config.QcRuleSet rules) {
        com.tcm.ehr.common.config.QcRuleSet rs = rules == null ? com.tcm.ehr.common.config.QcRuleSet.defaults() : rules;
        ScoreResultVO vo = new ScoreResultVO();
        List<ScoreResultVO.Deduction> ded = new ArrayList<>();

        boolean structuredMissing = data == null;
        vo.setStructuredMissing(structuredMissing);

        // 1. 完整性（两档）
        int fullMissing = 0;
        for (com.tcm.ehr.common.config.QcRuleSet.Element el : rs.getCompleteness().getElements()) {
            if (structuredPresent(el.getSource(), data)) {
                continue;
            }
            boolean rawHas = rawPresent(el.getFallback(), rawRecord);
            int points = rawHas ? el.getWeightPartial() : el.getWeightFull();
            if (!rawHas) {
                fullMissing++;
            }
            ded.add(new ScoreResultVO.Deduction("核心字段缺失", el.getName(), points, reasonFor(el, rawHas)));
        }

        // 2. 逻辑一致性
        List<String> conflicts = LogicChecker.check(data, rs.getConsistency());
        for (String c : conflicts) {
            String name = c.contains("：") ? c.substring(0, c.indexOf("：")) : c;
            int weight = weightOf(rs, name);
            ded.add(new ScoreResultVO.Deduction("逻辑冲突", name, weight, c));
        }
        vo.setLogicConflicts(conflicts);

        // 3. 格式
        if (rawRecord != null) {
            for (com.tcm.ehr.common.config.QcRuleSet.FormatRule formatRule : rs.getFormat()) {
                String value = rawValue(rawRecord, formatRule.getField());
                if (value == null) {
                    continue;
                }
                if (!formatOk(formatRule, value)) {
                    String label = formatRule.getLabel() == null ? formatRule.getField() : formatRule.getLabel();
                    String reason = (formatRule.getReason() == null ? label + "格式不正确" : formatRule.getReason()) + "：" + value;
                    ded.add(new ScoreResultVO.Deduction("格式错误", label, formatRule.getWeight(), reason));
                }
            }
        }

        // 4. 术语标准化
        com.tcm.ehr.common.config.QcRuleSet.Standardization st = rs.getStandardization();
        if (st.isEnabled() && data != null && !st.getElementTypes().isEmpty()) {
            int miss = 0;
            for (String type : st.getElementTypes()) {
                miss += countUnnormalized(data, type);
            }
            if (miss > 0) {
                // 分段扣分（2026-10-05 修）：原先 `Math.min(cap, miss * weightEach)` 在未归一数 ≥ cap 后
        // 恒定扣 cap 分 —— 实测 51.6% 的病历都停在这一档，再糟也不多扣，「未归一」这一项
        // 因此失去区分度（好病历与差病历同分）。改成：
        //   前 cap 条：每条 weightEach 分（与原来一致，保护轻微未归一）
        //   之后：每再满 cap 条，追加 weightEach × cap 分（即每满一档多扣一档）
        // 口径写成注释里的公式，避免「看起来还在封顶」的误解；档位由既有 weightEach/cap 推导，
        // 不新增配置项（前端规则表单与 openapi 无需改动）。
        int points = miss <= st.getCap()
                ? miss * st.getWeightEach()
                : st.getWeightEach() * (st.getCap() + (miss - st.getCap()) / st.getCap());
                ded.add(new ScoreResultVO.Deduction("术语未标准化", "未命中词典",
                        points, "有 " + miss + " 个实体未命中标准词典"));
            }
        }

        // 5. 重复
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
    public static Missing missingElements(Map<String, Object> data, Record rawRecord,
                                          com.tcm.ehr.common.config.QcRuleSet rules) {
        com.tcm.ehr.common.config.QcRuleSet rs =
                rules == null ? com.tcm.ehr.common.config.QcRuleSet.defaults() : rules;
        List<String> full = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        // 1. 逐个核心要素判两档：结构化里有 → 跳过（不扣）
        for (com.tcm.ehr.common.config.QcRuleSet.Element el : rs.getCompleteness().getElements()) {
            if (structuredPresent(el.getSource(), data)) {
                continue;
            }
            String name = el.getName();
            // 2. 原始病历里有 → 漏抽（半档）；两边都没有 → 真缺失（全档）
            if (rawPresent(el.getFallback(), rawRecord)) {
                partial.add(name);
            } else {
                full.add(name);
            }
        }
        return new Missing(full, partial);
    }

    private static int weightOf(com.tcm.ehr.common.config.QcRuleSet rs, String name) {
        // 1. 按规则名找对应权重
        for (com.tcm.ehr.common.config.QcRuleSet.ConsistencyRule c : rs.getConsistency()) {
            if (c.getName() != null && c.getName().equals(name)) {
                return c.getWeight();
            }
        }
        // 2. 规则里没配这项时给默认 10，不让冲突变成 0 分
        return 10;
    }

    private static boolean formatOk(com.tcm.ehr.common.config.QcRuleSet.FormatRule fr, String v) {
        // 1. enum 规则看白名单
        if ("enum".equalsIgnoreCase(fr.getType())) {
            return fr.getValues() != null && fr.getValues().contains(v);
        }
        // 2. 没配表达式就跳过
        if (fr.getExpr() == null || fr.getExpr().isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(fr.getExpr()).matcher(v).matches();
        } catch (Exception e) {
            return true; // 表达式非法则该项不判，避免误伤
        }
    }

    private static boolean structuredPresent(String structuredKey, Map<String, Object> data) {
        // 1. 无 structuredKey 或无数据一律视为没抽到
        if (structuredKey == null || data == null) {
            return false;
        }
        // 2. 只有非空列表才算有记录（空数组等同缺失）
        return data.get(structuredKey) instanceof List<?> list && !list.isEmpty();
    }

    private static boolean rawPresent(List<String> fields, Record rawRecord) {
        // 1. 无回退字段或无原始病历时判为「原始也没写」
        if (rawRecord == null || fields == null) {
            return false;
        }
        // 2. 任一字段有值即算原始写了
        for (String field : fields) {
            String value = rawValue(rawRecord, field);
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    /** 原始列取值（按 Record 属性名） */
    private static String rawValue(Record r, String field) {
        // 取值与空白口径都在 RecordUtil.column 一处（批次14 收敛）：原先本类与
        // QcServiceImpl 各写一份 19 / 17 个 case 的 switch，而两边的取值与 trim
        // 逐字相同，差别只在明细那份少了 department、doctorId 两个 case。
        return RecordUtil.column(r, field);
    }

    /** 术语类型 → 结构化 structuredKey；未纳入未归一扣分的类型返回 null */
    private static String keyOf(String type) {
        // 批次14 · 14.1：这张「5 类」的小表已搬进 EntityTypes（countsUnnormalized），
        // 这里改为查目录 —— 以后新增类型（或改某类是否计分）只动 EntityTypes 一处。
        // 语义与搬走前逐字相同：只有 countsUnnormalized=true 的类型才映射出 structuredKey。
        com.tcm.ehr.common.config.EntityTypes.EntityType t =
                com.tcm.ehr.common.config.EntityTypes.byKey(type);
        return t != null && t.countsUnnormalized() ? t.structuredKey() : null;
    }

    /** 该类型下未命中词典（无 normLevel）的实体数 */
    private static int countUnnormalized(Map<String, Object> data, String type) {
        String structuredKey = keyOf(type);
        // 1. 该类型没有对应列表（未抽取）就不计未命中
        if (structuredKey == null || !(data.get(structuredKey) instanceof List<?> list)) {
            return 0;
        }
        // 2. 逐个实体看有没有 normLevel：没有就是没命中词典
        int missCount = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> entity) {
                Object normLevel = entity.get("normLevel");
                if (normLevel == null) {
                    missCount++;
                }
            }
        }
        return missCount;
    }

    private static String reasonFor(com.tcm.ehr.common.config.QcRuleSet.Element el, boolean rawHas) {
        // 1. 两档给不同说法：用户要能分清「该写没写」和「写了没抽出来」
        if (rawHas) {
            return "结构化结果中无" + el.getName() + "（原始病历有记录，可能未被抽取）";
        }
        return "结构化结果与原始病历均无" + el.getName() + "记录";
    }
}
