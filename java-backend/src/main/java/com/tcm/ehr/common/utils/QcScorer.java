package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ScoreResultVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终末质控评分。
 *
 * <p>评分维度全部来自规则集：完整性（要素清单，真缺失/漏抽两档）、格式规则、逻辑一致性、
 * 术语标准化、重复；分级阈值同样来自规则集。规则声明依赖要素；要素缺失则跳过（不适用）。</p>
 */
public final class QcScorer {

    /**
     * 质控分级的三个取值（2026-10-23 收敛为单一事实源）。
     *
     * <p><b>为什么必须集中定义</b>：这三个字面量此前散落在 12 处 —— 本类赋值 4 处、
     * GovernanceServiceImpl 5 处、QcServiceImpl 5 处、ReviewServiceImpl 2 处，
     * 且 StatsCache 还自维护一份 GRADES 白名单。改一处漏十一处时
     * <b>编译器完全不会报错</b>：{@code switch} 落到 default、导出条件一条都匹配不上、
     * 待办列表变空 —— 属于静默数据错乱。故收敛到此，下游一律引用常量。</p>
     */
    public static final String GRADE_QUALIFIED = "合格";
    public static final String GRADE_PENDING = "待复核";
    public static final String GRADE_INVALID = "无效";

    private QcScorer() {
    }

    /**
     * 对单份病历做终末质控评分，输出得分、分级与逐条扣分。
     *
     * <p>依次累计五类扣分：完整性（要素缺失分真缺失 / 漏抽两档）、逻辑一致性、格式、
     * 术语标准化、重复；分级取「低于无效线 / 存在逻辑冲突 / 达到合格线」中优先级最高的一档。
     * 规则集为 {@code null} 时回退到内置默认规则。</p>
     *
     * @param data 结构化抽取结果，{@code null} 表示未结构化（标记为缺失）
     * @param rawRecord 原始病历，{@code null} 时跳过格式规则
     * @param duplicate 是否与已有病历重复
     * @param rules 质控规则集，可为 {@code null}
     * @return 评分结果（得分、分级、扣分明细）
     */
    public static ScoreResultVO score(Map<String, Object> data, Record rawRecord, boolean duplicate,
                                      com.tcm.ehr.common.config.QcRuleSet rules) {
        com.tcm.ehr.common.config.QcRuleSet rs = rules == null ? com.tcm.ehr.common.config.QcRuleSet.defaults() : rules;
        ScoreResultVO vo = new ScoreResultVO();
        List<ScoreResultVO.Deduction> ded = new ArrayList<>();

        boolean structuredMissing = data == null;
        vo.setStructuredMissing(structuredMissing);

        // 1. 完整性（两档）
        for (com.tcm.ehr.common.config.QcRuleSet.Element el : rs.getCompleteness().getElements()) {
            if (structuredPresent(el.getSource(), data)) {
                continue;
            }
            boolean rawHas = rawPresent(el.getFallback(), rawRecord);
            int points = rawHas ? el.getWeightPartial() : el.getWeightFull();
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
            // 计分范围 = 质控勾选 ∩ 归一勾选：未纳入归一范围的类型，其实体本就不写 normLevel，
            // 若仍计入扣分会恒判「未归一」而扣满（丢区分度的假信号）。见 QcRuleSet.Normalization。
            java.util.Set<String> normalized = normalizedTypesOf(rs);
            int miss = 0;
            Map<String, Integer> byType = new LinkedHashMap<>();
            for (String type : st.getElementTypes()) {
                if (!normalized.contains(type)) {
                    continue;
                }
                int n = countUnnormalized(data, type);
                if (n > 0) {
                    byType.put(com.tcm.ehr.common.config.EntityTypes.byKey(type).label(), n);
                    miss += n;
                }
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
                // F2：把「miss 的构成」写进 reason（按类型明细），便于对账
                StringBuilder detail = new StringBuilder();
                for (Map.Entry<String, Integer> e : byType.entrySet()) {
                    if (detail.length() > 0) {
                        detail.append(" / ");
                    }
                    detail.append(e.getKey()).append(' ').append(e.getValue());
                }
                ded.add(new ScoreResultVO.Deduction("术语未标准化", "未命中词典",
                        points, "有 " + miss + " 个实体未命中标准词典（" + detail + "）"));
            }
        }

        // 5. 重复
        if (duplicate) {
            ded.add(new ScoreResultVO.Deduction("重复数据", "重复标记", rs.getDuplicateWeight(), "与已有病历内容完全一致"));
        }

        int totalDeduct = ded.stream().mapToInt(ScoreResultVO.Deduction::getPoints).sum();
        int score = Math.max(0, 100 - totalDeduct);

        com.tcm.ehr.common.config.QcRuleSet.Thresholds th = rs.getThresholds();
        String grade;
        if (score < th.getInvalid()) {
            grade = GRADE_INVALID;
        } else if (!conflicts.isEmpty()) {
            grade = GRADE_PENDING;
        } else if (score >= th.getReviewUpper()) {
            grade = GRADE_QUALIFIED;
        } else {
            grade = GRADE_PENDING;
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

    /** 按规则集判定核心要素缺失；rules 为 null 时用内置默认（与 score 一致） */
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
            // P2-1：走 RegexCache —— 表达式是配置常量，原先每条病历每条规则都现编译一次
            return RegexCache.of(fr.getExpr()).matcher(v).matches();
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

    /** 术语类型 → 结构化 structuredKey；未登记的类型返回 null（不计未命中） */
    private static String keyOf(String type) {
        // 计分范围完全由规则集的 standardization.elementTypes 决定（调用方已按它筛选）：
        // 这里只做「类型 → structuredKey」的映射，未登记类型返回 null。
        // 原实现另叠一道 EntityTypes.countsUnnormalized 过滤 —— 结果是前端即便勾上
        // 舌象/脉象/治法也照样不计分（双开关、勾了不生效）。已按「以规则配置为准」移除。
        com.tcm.ehr.common.config.EntityTypes.EntityType t =
                com.tcm.ehr.common.config.EntityTypes.byKey(type);
        return t != null ? t.structuredKey() : null;
    }

    /** 该类型下未命中词典的实体数：`normLevel` 缺失**或**不在命中区间（0 = 查过但没命中）都算 */
    private static int countUnnormalized(Map<String, Object> data, String type) {
        String structuredKey = keyOf(type);
        // 1. 该类型没有对应列表（未抽取）就不计未命中
        if (structuredKey == null || !(data.get(structuredKey) instanceof List<?> list)) {
            return 0;
        }
        // 2. 逐个实体看 normLevel：缺失或非命中区间（0/越界）= 未命中词典
        int missCount = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> entity) {
                Object normLevel = entity.get("normLevel");
                int lv = (normLevel instanceof Number n) ? n.intValue() : 0;
                if (!EsTermNormalizer.isHit(lv)) {
                    missCount++;
                }
            }
        }
        return missCount;
    }

    /** 规则集里「参与归一」的类型集合；缺字段时退回出厂默认（维持旧行为） */
    private static java.util.Set<String> normalizedTypesOf(com.tcm.ehr.common.config.QcRuleSet rs) {
        com.tcm.ehr.common.config.QcRuleSet.Normalization n = rs.getNormalization();
        return (n == null || n.getElementTypes() == null)
                ? java.util.Set.copyOf(com.tcm.ehr.common.config.QcRuleSet.DEFAULT_NORMALIZATION_TYPES)
                : java.util.Set.copyOf(n.getElementTypes());
    }

    private static String reasonFor(com.tcm.ehr.common.config.QcRuleSet.Element el, boolean rawHas) {
        // 1. 两档给不同说法：用户要能分清「该写没写」和「写了没抽出来」
        if (rawHas) {
            return "结构化结果中无" + el.getName() + "（原始病历有记录，可能未被抽取）";
        }
        return "结构化结果与原始病历均无" + el.getName() + "记录";
    }
}
