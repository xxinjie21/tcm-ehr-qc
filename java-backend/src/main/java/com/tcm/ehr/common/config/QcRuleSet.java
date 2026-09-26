package com.tcm.ehr.common.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 质控规则集（批Q，可配置、单一数据源）。
 *
 * <p>默认值由 {@link #defaults()} 给出（按当前数据集调优的通用示例）；运行时可被
 * {@code data/qc-rules.json} 覆盖，字段级深合并（缺的用默认补、多的忽略）。</p>
 *
 * <p>规则声明它依赖的病历要素；判定时若该要素在数据中缺失，则视为"不适用"、跳过而不误判。</p>
 */
@Data
public class QcRuleSet {

    private Completeness completeness = new Completeness();
    private List<FormatRule> format = new ArrayList<>();
    private List<ConsistencyRule> consistency = new ArrayList<>();
    private Standardization standardization = new Standardization();
    private int duplicateWeight = 5;
    private Thresholds thresholds = new Thresholds();

    /** 完整性：核心要素清单 */
    @Data
    public static class Completeness {
        private List<Element> elements = new ArrayList<>();
    }

    @Data
    public static class Element {
        /** 展示名，如"症状" */
        private String name;
        /** 实体类型 key（目录用；如 symptom） */
        private String typeKey;
        /** 结构化数据的 key，如 symptoms */
        private String source;
        /** 原始列回退字段（Record 属性名），可多个，命中任一即视为"有记录（漏抽）" */
        private List<String> fallback = new ArrayList<>();
        /** 结构化与原始列均无 → 真缺失扣分 */
        private int weightFull = 12;
        /** 原始列有、结构化为空 → 漏抽扣分 */
        private int weightPartial = 6;
    }

    /** 格式规则 */
    @Data
    public static class FormatRule {
        /** Record 属性名，如 age / gender */
        private String field;
        /** regex / enum */
        private String type = "regex";
        /** regex 表达式（type=regex） */
        private String expr;
        /** enum 允许值（type=enum） */
        private List<String> values = new ArrayList<>();
        private int weight = 5;
        /** 展示名，如"年龄" */
        private String label;
        /** 失败原因前缀 */
        private String reason;
        /** 正则规则的「人话」说明（regex 类型专用）：面向用户的说明里只输出它，不输出正则本身 */
        private String hint;
    }

    /**
     * 一致性规则（批S 泛化）：当【触发类型】的实体命中【触发值】时，若病历记录了【期望类型】的实体，
     * 则其须命中【期望值】之一，否则记冲突。类型取自 {@link EntityTypes}；期望值只能来自对应词典。
     * 期望类型实体缺失 → 不适用（不判）。
     */
    @Data
    public static class ConsistencyRule {
        /** 规则名，如"证候-中药" */
        private String name;
        /** 触发类型（实体类型 key，如 pattern） */
        private String triggerType;
        /** 触发值（命中任一，contains） */
        private List<String> triggerValues = new ArrayList<>();
        /** 期望类型（实体类型 key，如 herb/formula） */
        private String expectType;
        /** 期望值（命中任一即可；取自对应词典） */
        private List<String> expectValues = new ArrayList<>();
        private int weight = 10;
    }

    /** 术语标准化 */
    @Data
    public static class Standardization {
        private boolean enabled = true;
        /** 术语类型：disease/pattern/symptom/herb/formula */
        private List<String> elementTypes = new ArrayList<>();
        /** 每个未命中词典的实体扣分 */
        private int weightEach = 1;
        /** 该项扣分上限 */
        private int cap = 5;
    }

    @Data
    public static class Thresholds {
        private int qualified = 90;
        private int invalid = 60;
        private int seriousFullMissing = 3;
    }

    /** 出厂默认（按当前数据集调优的通用示例，可被 qc-rules.json 覆盖） */
    public static QcRuleSet defaults() {
        QcRuleSet r = new QcRuleSet();

        // 完整性 6 要素（症状/疾病/证候/舌象/脉象/中药），取自实体类型目录
        for (String key : List.of("symptom", "disease", "pattern", "tongue", "pulse", "herb")) {
            EntityTypes.EntityType t = EntityTypes.byKey(key);
            r.completeness.elements.add(el(t.label(), t.structuredKey(), t.fallback()));
        }

        // 格式
        QcRuleSet.FormatRule age = format("age", "regex", "^\\d+(\\.\\d+)?(岁|个月|月|天)?$",
                List.of(), "年龄", "年龄格式不正确");
        age.setHint("须为数字，可带 岁/个月/月/天 单位");
        r.format.add(age);
        r.format.add(format("gender", "enum", null, List.of("男", "女"), "性别", "性别非 男/女"));

        // 一致性（证候 → 中药 / 方剂；值取自词典，当前数据无方剂则方剂规则不触发）
        // 证候 → 中药
        r.consistency.add(rule("肝气郁结-中药", "pattern", List.of("肝气郁结", "横逆犯胃", "胃失和降"),
                "herb", List.of("柴胡", "白芍")));
        r.consistency.add(rule("胃热炽盛-中药", "pattern", List.of("胃热炽盛", "耗伤津液"),
                "herb", List.of("石膏", "知母")));
        r.consistency.add(rule("脾肾阳虚-中药", "pattern", List.of("脾肾阳虚", "水湿内停"),
                "herb", List.of("附子", "肉桂")));
        r.consistency.add(rule("寒湿痹阻-中药", "pattern", List.of("寒湿痹阻"),
                "herb", List.of("独活", "桑寄生")));
        r.consistency.add(rule("肾阴亏虚-中药", "pattern", List.of("肾阴亏虚", "虚阳上亢"),
                "herb", List.of("熟地黄", "山茱萸")));
        r.consistency.add(rule("肝肾亏虚-中药", "pattern", List.of("肝肾亏虚", "筋骨失养"),
                "herb", List.of("熟地黄", "牛膝")));
        r.consistency.add(rule("肝阳上亢-中药", "pattern", List.of("肝阳上亢", "肝火上炎"),
                "herb", List.of("天麻", "钩藤")));
        r.consistency.add(rule("心脾两虚-中药", "pattern", List.of("心脾两虚", "气血不足"),
                "herb", List.of("人参", "白术")));
        r.consistency.add(rule("肺气虚寒-中药", "pattern", List.of("肺气虚寒", "卫表不固"),
                "herb", List.of("黄芪", "防风")));
        // 证候 → 方剂（经典方证对应；数据无方剂则不触发）
        r.consistency.add(rule("肝气郁结-方剂", "pattern", List.of("肝气郁结", "横逆犯胃", "胃失和降"),
                "formula", List.of("柴胡疏肝散")));
        r.consistency.add(rule("胃热炽盛-方剂", "pattern", List.of("胃热炽盛", "耗伤津液"),
                "formula", List.of("白虎加人参汤")));
        r.consistency.add(rule("脾肾阳虚-方剂", "pattern", List.of("脾肾阳虚", "水湿内停"),
                "formula", List.of("济生肾气丸")));
        r.consistency.add(rule("寒湿痹阻-方剂", "pattern", List.of("寒湿痹阻"),
                "formula", List.of("独活寄生汤")));
        r.consistency.add(rule("肾阴亏虚-方剂", "pattern", List.of("肾阴亏虚", "虚阳上亢"),
                "formula", List.of("知柏地黄丸")));

        // 术语标准化
        r.standardization.setEnabled(true);
        r.standardization.setElementTypes(new ArrayList<>(EntityTypes.dictKeys()));
        r.standardization.setWeightEach(1);
        r.standardization.setCap(5);

        r.duplicateWeight = 5;
        return r;
    }

    private static Element el(String name, String source, List<String> fallback) {
        Element e = new Element();
        e.setName(name);
        e.setSource(source);
        e.setFallback(new ArrayList<>(fallback));
        return e;
    }

    private static FormatRule format(String field, String type, String expr, List<String> values, String label, String reason) {
        FormatRule f = new FormatRule();
        f.setField(field);
        f.setType(type);
        f.setExpr(expr);
        f.setValues(new ArrayList<>(values));
        f.setLabel(label);
        f.setReason(reason);
        return f;
    }

    private static ConsistencyRule rule(String name, String triggerType, List<String> triggerValues,
                                        String expectType, List<String> expectValues) {
        ConsistencyRule c = new ConsistencyRule();
        c.setName(name);
        c.setTriggerType(triggerType);
        c.setTriggerValues(new ArrayList<>(triggerValues));
        c.setExpectType(expectType);
        c.setExpectValues(new ArrayList<>(expectValues));
        return c;
    }
}
