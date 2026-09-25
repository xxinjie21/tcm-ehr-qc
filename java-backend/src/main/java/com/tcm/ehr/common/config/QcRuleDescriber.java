package com.tcm.ehr.common.config;

import java.util.ArrayList;
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

        // 格式
        List<String> fmts = new ArrayList<>();
        for (QcRuleSet.FormatRule f : r.getFormat()) {
            String label = f.getLabel() == null ? f.getField() : f.getLabel();
            String cond = "enum".equalsIgnoreCase(f.getType())
                    ? ("须为 " + String.join("/", f.getValues()))
                    : ("须符合格式 " + f.getExpr());
            fmts.add("【" + label + "】" + cond + "（- " + f.getWeight() + " 分）");
        }
        if (!fmts.isEmpty()) {
            out.add("格式检查：" + String.join("；", fmts) + "。");
        }

        // 一致性
        for (QcRuleSet.ConsistencyRule c : r.getConsistency()) {
            StringBuilder sb = new StringBuilder("若证候含【" + String.join("/", c.getPatternAny()) + "】，则 ");
            List<String> conds = new ArrayList<>();
            if (!c.getExpectHerbs().isEmpty()) {
                conds.add("中药应包含 " + String.join("/", c.getExpectHerbs()) + " 之一");
            }
            if (!c.getExpectTongue().isEmpty()) {
                conds.add("舌象应为 " + String.join("/", c.getExpectTongue()));
            }
            if (!c.getExpectPulse().isEmpty()) {
                conds.add("脉象应为 " + String.join("/", c.getExpectPulse()));
            }
            sb.append(String.join("、", conds));
            sb.append("；不符且该项有记录时记逻辑冲突（- ").append(c.getWeight()).append(" 分）。");
            out.add(sb.toString());
        }

        // 标准化
        QcRuleSet.Standardization st = r.getStandardization();
        if (st.isEnabled()) {
            out.add("术语（" + String.join("/", st.getElementTypes())
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

    /** 可选要素目录：取自实体类型目录（9 类，中文名 ↔ structuredKey / 回退列），用户只选中文 */
    public static List<QcRuleSet.Element> catalogElements() {
        List<QcRuleSet.Element> list = new ArrayList<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            list.add(el(t.label(), t.structuredKey(), t.fallback()));
        }
        return list;
    }

    /** 格式模板目录 */
    public static List<QcRuleSet.FormatRule> catalogFormats() {
        List<QcRuleSet.FormatRule> list = new ArrayList<>();
        list.add(fmt("age", "regex", "^\\d+(\\.\\d+)?(岁|个月|月|天)?$", List.of(), "年龄", "年龄格式不正确"));
        list.add(fmt("gender", "enum", null, List.of("男", "女"), "性别", "性别非 男/女"));
        return list;
    }

    private static QcRuleSet.Element el(String name, String source, List<String> fallback) {
        QcRuleSet.Element e = new QcRuleSet.Element();
        e.setName(name);
        e.setSource(source);
        e.setFallback(new ArrayList<>(fallback));
        return e;
    }

    private static QcRuleSet.FormatRule fmt(String field, String type, String expr, List<String> values, String label, String reason) {
        QcRuleSet.FormatRule f = new QcRuleSet.FormatRule();
        f.setField(field);
        f.setType(type);
        f.setExpr(expr);
        f.setValues(new ArrayList<>(values));
        f.setLabel(label);
        f.setReason(reason);
        return f;
    }
}
