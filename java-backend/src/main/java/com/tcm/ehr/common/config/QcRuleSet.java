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
    }

    /**
     * 一致性规则：证候命中关键词时，若病历记录了相应要素，则须与期望集合相符，否则冲突。
     * 三类期望可任选（非空的才校验）；对应要素缺失 → 不适用。
     */
    @Data
    public static class ConsistencyRule {
        /** 规则名，如"证候-中药" */
        private String name;
        /** 证候关键词（命中任一即触发） */
        private List<String> patternAny = new ArrayList<>();
        /** 期望中药（命中任一即可） */
        private List<String> expectHerbs = new ArrayList<>();
        /** 期望舌象（命中任一即可） */
        private List<String> expectTongue = new ArrayList<>();
        /** 期望脉象（命中任一即可） */
        private List<String> expectPulse = new ArrayList<>();
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

        // 完整性 5 要素
        r.completeness.elements.add(el("症状", "symptoms", List.of("chiefComplaint", "selfReport", "presentIllness")));
        r.completeness.elements.add(el("证候", "patternList", List.of("pattern")));
        r.completeness.elements.add(el("舌象", "tongueList", List.of("tongue")));
        r.completeness.elements.add(el("脉象", "pulseList", List.of("pulse")));
        r.completeness.elements.add(el("中药", "herbs", List.of("prescription")));

        // 格式
        r.format.add(format("age", "regex", "^\\d+(\\.\\d+)?(岁|个月|月|天)?$", List.of(), "年龄", "年龄格式不正确"));
        r.format.add(format("gender", "enum", null, List.of("男", "女"), "性别", "性别非 男/女"));

        // 一致性（证候 → 期望中药/舌象/脉象；按当前数据集配对）
        r.consistency.add(rule("肝气郁结-横逆犯胃", List.of("肝气郁结", "横逆犯胃", "胃失和降"),
                List.of("柴胡", "白芍", "枳壳", "香附"), List.of("舌质淡红"), List.of("脉弦细")));
        r.consistency.add(rule("胃热炽盛-耗伤津液", List.of("胃热炽盛", "耗伤津液"),
                List.of("石膏", "知母", "麦冬"), List.of("舌质红"), List.of("脉滑数")));
        r.consistency.add(rule("脾肾阳虚-水湿内停", List.of("脾肾阳虚", "水湿内停"),
                List.of("附子", "肉桂", "白术", "茯苓"), List.of("舌质淡胖"), List.of("脉沉细")));
        r.consistency.add(rule("寒湿痹阻-经络不通", List.of("寒湿痹阻"),
                List.of("独活", "桑寄生", "杜仲", "牛膝"), List.of("舌质淡"), List.of("脉沉紧而迟")));
        r.consistency.add(rule("肾阴亏虚-虚阳上亢", List.of("肾阴亏虚", "虚阳上亢"),
                List.of("熟地黄", "山茱萸", "山药"), List.of("舌红少苔"), List.of("脉细数")));
        r.consistency.add(rule("肝肾亏虚-筋骨失养", List.of("肝肾亏虚", "筋骨失养"),
                List.of("熟地黄", "杜仲", "牛膝"), List.of("舌质淡"), List.of("脉沉细")));
        r.consistency.add(rule("肝阳上亢-肝火上炎", List.of("肝阳上亢", "肝火上炎"),
                List.of("天麻", "钩藤", "石决明"), List.of("舌质红"), List.of("脉弦劲有力")));
        r.consistency.add(rule("肝郁气滞-痰气互结", List.of("肝郁气滞", "痰气互结"),
                List.of("柴胡", "玄参", "夏枯草", "浙贝母"), List.of("舌质红"), List.of("脉弦数")));
        r.consistency.add(rule("心脾两虚-气血不足", List.of("心脾两虚", "气血不足"),
                List.of("人参", "白术", "茯苓", "当归"), List.of("舌质淡"), List.of("脉细")));
        r.consistency.add(rule("肺气虚寒-卫表不固", List.of("肺气虚寒", "卫表不固"),
                List.of("黄芪", "白术", "防风"), List.of("舌质淡"), List.of("脉浮缓")));

        // 术语标准化
        r.standardization.setEnabled(true);
        r.standardization.setElementTypes(new ArrayList<>(List.of("disease", "pattern", "symptom", "herb", "formula")));
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

    private static ConsistencyRule rule(String name, List<String> patterns, List<String> herbs,
                                        List<String> tongue, List<String> pulse) {
        ConsistencyRule c = new ConsistencyRule();
        c.setName(name);
        c.setPatternAny(new ArrayList<>(patterns));
        c.setExpectHerbs(new ArrayList<>(herbs));
        c.setExpectTongue(new ArrayList<>(tongue));
        c.setExpectPulse(new ArrayList<>(pulse));
        return c;
    }
}
