package com.tcm.ehr.common.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 质控规则集。
 *
 * 默认值由 {@link #defaults()} 给出（按当前数据集调优的通用示例）。<b>运行时权威是 DB</b>：
 * 每组织一行（qc_rules.rules_json），无行的组织回退默认；读取、字段级深合并
 * （缺的用默认补、多的忽略）与告警都由 QcRuleStore 负责。
 *
 * ⚠️ 原注释写的「运行时可被 {@code data/qc-rules.json} 覆盖」<b>已作废</b>：那个文件
 * 从来不存在，其兜底读路径已在批次 25.a 删除（QcRuleStore 类头有同样说明）。
 *
 * 一致性 / 格式规则声明它依赖的病历要素，判定时若该要素在数据中缺失，则视为"不适用"、
 * 跳过而不误判；完整性要素相反——缺失即按两档扣分（真缺失扣满档，原始病历有记录但未结构化扣半档），
 * 详见 QcScorer。
 */
@Data
public class QcRuleSet {

    /**
     * 性别枚举白名单 —— <b>唯一权威</b>。
     *
     * 此前同一份「男 / 女」在三处各写一遍：默认规则（{@link #defaults()}）、
     * 前端下拉目录（{@code QcRuleDescriber.catalogFormats()}）、前端表单校验
     * （Records.vue）。改一处忘另两处就会出现「界面能选、提交被判不合规」这类偏差。
     * 现统一取这里；前端两份由 {@code GET /api/qc/rules} 的 catalog 下发，不各自硬编码。
     *
     * 含 {@code 未知的性别}：真实病历里存在该值（性别不明），《病历书写基本规范》首页也允许，
     * 若不在白名单会被质控格式规则误判为「性别非 男/女」扣分（实测 31 条）。
     */
    public static final List<String> GENDER_VALUES = List.of("男", "女", "未知的性别");

    /**
     * 性别校验正则（供 DTO 的 {@code @Pattern} 用；长度上限对齐 {@code records.gender VARCHAR(10)}）。
     * 放行 null / 空串（由 {@code @NotBlank} 或业务层决定「是否必填」），此处只管「填了就得合法」。
     */
    public static final String GENDER_PATTERN = "^(男|女|未知的性别)$";

    /**
     * 年龄格式正则 —— <b>唯一权威</b>。默认规则（{@link #defaults()}）与前端下拉目录
     * （{@code QcRuleDescriber.catalogFormats()}）共用同一份，避免两边漂移：界面显示的校验
     * 与实际应用的规则不一致时，用户会以为「改了规则不生效」。
     *
     * 用 {@code (...)+} 允许<b>复合单位</b>：「32岁6月」「35岁2月」是真实病历的常见写法。
     * 原先的 {@code ^\d+(\.\d+)?(岁|个月|月|天)?$} 只允许单个单位后缀，实测把真实数据里
     * 92.1% 的年龄判成格式错误（32550/35354，失败形态 100% 为复合单位）。单位后缀在每组内
     * 仍必须可选，否则纯数字「45」会反过来不匹配。
     */
    public static final String AGE_PATTERN = "^(\\d+(\\.\\d+)?(岁|个月|月|天)?)+$";

    /** 年龄正则的「人话」说明（regex 类型只输出 hint、不输出正则本身） */
    public static final String AGE_HINT = "须为数字，可带 岁/个月/月/天 单位（可组合，如 32岁6月）";

    /**
     * 出厂默认「术语归一范围」类型 —— <b>唯一权威</b>。
     *
     * 取 9 类有词典的全部类型（疾病/证候/症状/中药/方剂/舌象/脉象/病因/治法）。
     * <b>病因已纳入默认归一范围</b>（2026-10-20）：病因词典 causes.json 已有 97 条词表，
     * 原先「病因由用户自建、默认不勾」的前提不再成立。
     *
     * <b>归一范围 ≠ 计分范围</b>：本清单只决定「做不做词形归一」，是否因未归一扣分由
     * standardization.elementTypes（默认仅 疾病/证候/症状/中药/方剂 5 类）与它取交集决定。
     * 故病因纳入归一后，实体能拿到标准词与 normLevel，但<b>不参与未归一扣分</b>。
     *
     * {@link #defaultNormalization()} 与 QcRuleStore 回填、EntityNormalizer 的
     * 兜底范围共用这一份，避免三处各写一张清单造成漂移。
     */
    public static final List<String> DEFAULT_NORMALIZATION_TYPES = List.of(
            "disease", "pattern", "symptom", "herb", "formula", "tongue", "pulse", "cause", "treatment");

    private Completeness completeness = new Completeness();
    private List<FormatRule> format = new ArrayList<>();
    private List<ConsistencyRule> consistency = new ArrayList<>();
    private Standardization standardization = new Standardization();
    private Normalization normalization = new Normalization();
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
        /**
         * 是否属「有独立词典」的类型 —— 仅 {@link QcRuleDescriber#catalogElements()} 下发目录时填充，
         * 供规则配置弹窗筛出术语标准化可勾选项（无词典的病因不参与标准化计分，故排除）。
         */
        private boolean dict;
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
     * 一致性规则：当【触发类型】的实体命中【触发值】时，若病历记录了【期望类型】的实体，
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
        /** 术语类型：disease/pattern/symptom/herb/formula/tongue/pulse/cause/treatment */
        private List<String> elementTypes = new ArrayList<>();
        /** 每个未命中词典的实体扣分 */
        private int weightEach = 1;
        /** 该项扣分上限 */
        private int cap = 5;
    }

    /**
     * 术语归一范围：哪几类实体参与词形归一（原文 → 标准词）。
     *
     * 与 {@link Standardization} <b>相互独立</b>：
     * 本块决定「哪些类型做归一」，standardization 决定「哪些类型计入未归一扣分」；
     * 实际计分范围取两者的<b>交集</b>（未参与归一的类型，其实体本就不该有 normLevel，
     * 若又计入扣分会被恒判「未归一」而扣满）——见 QcScorer。
     *
     * <b>空列表 = 全部不归一</b>，是用户「一个都不勾」的合法选择，不回填默认
     * （与 {@link Standardization#elementTypes} 同口径）。
     */
    @Data
    public static class Normalization {
        /** 参与归一的术语类型：9 类有词典者（可在「规则配置」里增删；是否计分另由 standardization 决定） */
        private List<String> elementTypes = new ArrayList<>();
    }

    @Data
    public static class Thresholds {
        /**
         * 合格线（复核上限）：得分 ≥ reviewUpper 判合格。
         *
         * 取值 80 由 35354 份离线仿真的「分数 ≈ 核心要素真缺失数阶梯」推导：
         * {@code weightFull=12} 时 {@code 100 − 12×缺失数} 得 {100,88,76,64,52}，
         * 80 落在「至多缺 1 项合格、缺 2~3 项待复核」的整数台阶上
         * （见 docs/质控规则分级口径优化计划.md §3）。
         *
         * 实测（35354 份，见 docs/质控标准与报告口径改造计划.md §3.10）：
         * 95 → 80 使合格率 60.7% → 84.6%、待复核 27.0% → 4.7%。
         */
        private int reviewUpper = 80;
        /** 无效线：低于此分判无效（缺 ≥4 项核心要素时 {@code 100−12×4=52 < 60}）。 */
        private int invalid = 60;
    }

    /** 出厂默认（按当前数据集调优的通用示例；运行时权威是 DB，见类头说明） */
    public static QcRuleSet defaults() {
        QcRuleSet r = new QcRuleSet();
        r.completeness = defaultCompleteness();
        r.standardization = defaultStandardization();
        r.normalization = defaultNormalization();

        // 格式
        // 年龄正则与 hint 见 AGE_PATTERN / AGE_HINT（与前端下拉目录共用，避免两边漂移）
        r.format.add(format("age", "regex", AGE_PATTERN, List.of(), "年龄", "年龄格式不正确", AGE_HINT));
        r.format.add(format("gender", "enum", null, GENDER_VALUES, "性别", "性别非 男/女/未知的性别", null));

        // 一致性：出厂默认**不含任何规则**（consistency 为空）。
        // 原内置 9 条「证候 → 中药」在真实病历上误报率 25%~80%（同一证候对应的是复方治法，
        // 不是固定两味药），实测把约 1031 次"医生用了别的药"误判成逻辑冲突、强拉待复核；
        // 权威（《中医病案质量控制中心建设与管理指南（试行）》）也把理法方药一致性列为
        // 人工/语义评审，不支持固定对照表。故默认置空，需要的组织在「规则配置」里用
        // 「触发类型 → 期望类型」自行添加（目录仍保留全部类型）。

        r.duplicateWeight = 5;
        return r;
    }

    /**
     * 内置默认完整性要素：6 要素（症状/疾病/证候/舌象/脉象/中药），取自 {@link EntityTypes}。
     *
     * 包级可见：{@code QcRuleStore.normalize()} 在「要素为空」时直接取它回填。原先是
     * {@code QcRuleSet.defaults().getCompleteness()} —— 为了拿一个子对象，把全部一致性规则、
     * 2 条格式规则与各自的 List 全构造一遍再丢掉（审查报告 §4.6）。
     *
     * <b>每次返回新对象，不共享常量</b>：Element 是可变 {@code @Data}，
     * 共享出去一旦被谁改了就是全局污染 —— 与本类 EntityTypes.BY_KEY
     * 「构建完包成不可变后才赋给 static」同一条约定。
     */
    static Completeness defaultCompleteness() {
        Completeness c = new Completeness();
        for (String key : List.of("symptom", "disease", "pattern", "tongue", "pulse", "herb")) {
            EntityTypes.EntityType t = EntityTypes.byKey(key);
            c.elements.add(el(t.label(), t.structuredKey(), t.fallback()));
        }
        return c;
    }

    /**
     * 出厂默认术语标准化配置（同上：供 {@link #defaults()} 与 {@code QcRuleStore.normalize()} 共用，
     * 每次返回新对象）。
     *
     * elementTypes 默认取 {@code 疾病/证候/症状/中药/方剂} <b>5 类</b>，每处 1 分、
     * 封顶 5 分（分段累加）。<b>可勾选范围</b>是 {@link EntityTypes#dictKeys()} 的 9 类，
     * 用户在「规则配置」里可把舌象/脉象/治法/病因也加入（勾上即计分，但需该类型同时在
     * {@link Normalization#getElementTypes()} 的归一范围内）。
     *
     * <b>为什么默认不含舌象/脉象/治法</b>：它们的子要素先由 python-nlp 按标点分段
     * （舌质/舌苔/齿痕/裂纹、脉位与脉象分开），分段本身有损；抽不到或词表未收录的段保留原文，
     * 若默认计入扣分，等于拿分段器的行为去惩罚用户。故出厂默认不含 —— 这是<b>默认值</b>，
     * 不再是写死的政策（原先由 EntityTypes.countsUnnormalized 硬门控，用户勾了也不生效，
     * 已按「以规则配置为准」移除）。
     */
    static Standardization defaultStandardization() {
        Standardization s = new Standardization();
        s.setEnabled(true);
        s.setElementTypes(new ArrayList<>(
                List.of("disease", "pattern", "symptom", "herb", "formula")));
        s.setWeightEach(1);
        s.setCap(5);
        return s;
    }

    /**
     * 出厂默认术语归一范围（同上：供 {@link #defaults()} 与 {@code QcRuleStore.normalize()} 共用，
     * 每次返回新对象）。
     *
     * elementTypes 取 {@link #DEFAULT_NORMALIZATION_TYPES}（9 类，含病因）。
     */
    static Normalization defaultNormalization() {
        Normalization n = new Normalization();
        n.setElementTypes(new ArrayList<>(DEFAULT_NORMALIZATION_TYPES));
        return n;
    }

    /**
     * 构造完整性要素；fallback 拷贝成新列表避免共享入参。
     *
     * 包级可见：{@code QcRuleDescriber.catalogElements()} 也用它构造前端下拉目录，
     * 保证「目录里的可选项」与「默认规则」逐字同构（审查报告 §2.2）。
     */
    static Element el(String name, String source, List<String> fallback) {
        Element e = new Element();
        e.setName(name);
        e.setSource(source);
        e.setFallback(new ArrayList<>(fallback));
        return e;
    }

    /**
     * 构造格式规则；values 拷贝一份避免共享入参列表。
     *
     * 包级可见：{@code QcRuleDescriber.catalogFormats()} 也用它构造前端下拉目录，
     * 保证「目录里的模板」与「默认规则」逐字同构（审查报告 §2.2）。
     */
    static FormatRule format(String field, String type, String expr, List<String> values, String label,
                             String reason, String hint) {
        FormatRule f = new FormatRule();
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
