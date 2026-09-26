package com.tcm.ehr.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质控规则 → 自然语言描述（批R，单一来源）。
 *
 * <p>标准面板与规则配置弹窗都消费这里的句子，避免前端各写一套措辞。</p>
 */
public final class QcRuleDescriber {

    private QcRuleDescriber() {
    }

    public static List<String> describe(QcRuleSet r) {
        List<String> out = new ArrayList<>();
        if (r == null) {
            return out;
        }

        // 完整性
        List<String> names = new ArrayList<>();
        int full = 0, partial = 0;
        for (QcRuleSet.Element e : r.getCompleteness().getElements()) {
            names.add(e.getName());
            full = e.getWeightFull();
            partial = e.getWeightPartial();
        }
        out.add("病历应包含【" + String.join("、", names) + "】；完全缺失每项扣 " + full
                + " 分，仅有原始记录（未结构化）每项扣 " + partial + " 分。");

        // 格式。regex 类型一律输出 human-readable 的 hint，绝不输出正则本身 ——
        // 这段话是给质控科业务用户看的，正则表达式属于实现细节（审查报告 H3）
        List<String> fmts = new ArrayList<>();
        for (QcRuleSet.FormatRule f : r.getFormat()) {
            String label = f.getLabel() == null ? f.getField() : f.getLabel();
            String cond;
            if ("enum".equalsIgnoreCase(f.getType())) {
                cond = "须为 " + String.join("/", f.getValues());
            } else if (f.getHint() != null && !f.getHint().isBlank()) {
                cond = f.getHint();
            } else {
                cond = f.getReason() == null || f.getReason().isBlank() ? "须符合格式要求" : f.getReason();
            }
            fmts.add("【" + label + "】" + cond + "（- " + f.getWeight() + " 分）");
        }
        if (!fmts.isEmpty()) {
            out.add("格式检查：" + String.join("；", fmts) + "。");
        }

        // 一致性（类型 → 类型，批S）
        for (QcRuleSet.ConsistencyRule c : r.getConsistency()) {
            if (c.getTriggerType() == null || c.getExpectType() == null) {
                continue;
            }
            out.add("若【" + labelOf(c.getTriggerType()) + "】含 " + String.join("/", c.getTriggerValues())
                    + "，则【" + labelOf(c.getExpectType()) + "】应为 " + String.join("/", c.getExpectValues())
                    + " 之一；不符且该项有记录时记逻辑冲突（- " + c.getWeight() + " 分）。");
        }

        // 标准化：元素类型存的是英文 key（disease/pattern/…），这里换成中文名再拼 ——
        // 同一条规则在配置弹窗里是中文、在说明里是英文 key，用户会怀疑哪个才算数（审查报告 H3）
        QcRuleSet.Standardization st = r.getStandardization();
        if (st.isEnabled()) {
            List<String> typeLabels = new ArrayList<>();
            for (String type : st.getElementTypes()) {
                typeLabels.add(labelOf(type));
            }
            out.add("术语（" + String.join("/", typeLabels)
                    + "）未命中标准词典的，每个扣 " + st.getWeightEach() + " 分，最多扣 " + st.getCap() + " 分。");
        } else {
            out.add("术语标准化：已关闭（不参与评分）。");
        }

        // 重复
        out.add("与其它病历内容完全重复的，扣 " + r.getDuplicateWeight() + " 分。");

        // 分级
        QcRuleSet.Thresholds t = r.getThresholds();
        out.add("分级：≥ " + t.getQualified() + " 分且无逻辑冲突为合格；< " + t.getInvalid()
                + " 分或核心要素真缺失 ≥ " + t.getSeriousFullMissing() + " 项为无效；其余为待复核。");
        return out;
    }

    /**
     * 一致性规则的构成摘要，如「14 条（中药 9 / 方剂 5）」。
     *
     * <p>由规则数据拼装、后端下发。质控标准面板原来写死「14 条（证候 → 中药 / 舌象 / 脉象）」，
     * 而实际规则里没有任何舌象/脉象项，改规则也不改文案（审查报告 H2）。</p>
     */
    public static String consistencySummary(QcRuleSet r) {
        if (r == null || r.getConsistency() == null || r.getConsistency().isEmpty()) {
            return "0 条";
        }
        Map<String, Integer> byExpect = new LinkedHashMap<>();
        for (QcRuleSet.ConsistencyRule c : r.getConsistency()) {
            byExpect.merge(labelOf(c.getExpectType()), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        byExpect.forEach((label, n) -> parts.add(label + " " + n));
        return r.getConsistency().size() + " 条（" + String.join(" / ", parts) + "）";
    }

    /** 可选要素目录：取自实体类型目录（9 类，中文名 ↔ 类型 key / structuredKey / 回退列），用户只选中文 */
    public static List<QcRuleSet.Element> catalogElements() {
        List<QcRuleSet.Element> list = new ArrayList<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            QcRuleSet.Element e = el(t.label(), t.structuredKey(), t.fallback());
            e.setTypeKey(t.key());
            list.add(e);
        }
        return list;
    }

    /** 格式模板目录 */
    public static List<QcRuleSet.FormatRule> catalogFormats() {
        List<QcRuleSet.FormatRule> list = new ArrayList<>();
        list.add(fmt("age", "regex", "^\\d+(\\.\\d+)?(岁|个月|月|天)?$", List.of(), "年龄", "年龄格式不正确",
                "须为数字，可带 岁/个月/月/天 单位"));
        list.add(fmt("gender", "enum", null, List.of("男", "女"), "性别", "性别非 男/女"));
        return list;
    }

    private static String labelOf(String type) {
        EntityTypes.EntityType t = EntityTypes.byKey(type);
        return t == null ? type : t.label();
    }

    private static QcRuleSet.Element el(String name, String source, List<String> fallback) {
        QcRuleSet.Element e = new QcRuleSet.Element();
        e.setName(name);
        e.setSource(source);
        e.setFallback(new ArrayList<>(fallback));
        return e;
    }

    private static QcRuleSet.FormatRule fmt(String field, String type, String expr, List<String> values,
                                            String label, String reason) {
        return fmt(field, type, expr, values, label, reason, null);
    }

    private static QcRuleSet.FormatRule fmt(String field, String type, String expr, List<String> values,
                                            String label, String reason, String hint) {
        QcRuleSet.FormatRule f = new QcRuleSet.FormatRule();
        f.setField(field);
        f.setType(type);
        f.setExpr(expr);
        f.setValues(new ArrayList<>(values));
        f.setLabel(label);
        f.setReason(reason);
        f.setHint(hint);
        return f;
    }
}
