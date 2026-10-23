package com.tcm.ehr.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体类型目录：结构化解析 / 术语词典 / 质控规则 三模块的单一来源。
 *
 *
 * 唯一权威是 {@link #all()}：固定 9 类（对齐 NLP 可抽取的实体）。
 * 9 类均有独立词典（疾病/证候/症状/中药/方剂/舌象/脉象/病因/治法）——病因词典
 * （causes.json）与其余 8 类同时到位，故 {@code dictKeys()} 现为 9 类。
 * 不落盘、不做用户自定义——词典范围限定在 NLP 能提取的实体。
 *
 * {@code dictKeys()} 的 9 类与 python-nlp 的 LABEL_FIELD 5 类都只是
 * {@code all()} 的过滤视图，不是并列清单：前者按 dict 标志筛（25.b 防漂移测试钉住），
 * 后者是模型能输出的 NER 标签集（病名/症状/证型/方剂/本草），维度不同、不要求等长。
 *
 *
 * ⚠️ 两类范围都不再由本目录硬编码，全部交给组织级规则集（用户在「规则配置」里改）：
 * <ul>
 *   <li>归一范围：QcRuleSet.normalization.elementTypes 决定哪些类型做词形归一
 *       （出厂默认 9 类，含病因 —— 但病因默认只归一、不计分）；</li>
 *   <li>扣分范围：QcRuleSet.standardization.elementTypes 决定哪些类型计入
 *       「未归一扣分」（出厂默认 5 类），实际按它与归一范围取交集计分。</li>
 * </ul>
 * 本目录只声明类型元数据。
 */
public final class EntityTypes {

    /**
     * @param key           类型标识（disease/pattern/symptom/herb/formula/tongue/pulse/cause/treatment）
     * @param label         中文名（疾病/证候/症状/中药/方剂/舌象/脉象/病因/治法）
     * @param structuredKey structured_data 中的字段名（diseases/patternList/…）
     * @param dict          是否有独立术语词典（可归一）
     * @param fileName      词典文件名（无词典为 null）
     * @param fallback      无结构化结果时的原始列回退字段（Record 属性名）
     * @param order         展示顺序
     */
    public record EntityType(String key, String label, String structuredKey, boolean dict,
                             String fileName, List<String> fallback, int order) {
    }

    // rawFields = 该类型的「专属原文来源列」。空列表不等于「抽不出来」：
    // 模型是对 NlpTextComposer 拼出的**整段文本**做 NER，只要方剂/治法的字样出现在
    // 任一已拼入的列里，模型的「方剂」标签仍会命中（python-nlp 的 LABEL_FIELD 有此标签）。
    // 空列表只表示「没有哪一列是专门给它准备的」——所以下面这两个别当成坏配置去补源：
    // 本数据集 prescription 列是纯中药清单（无方剂名）、也没有「治以…」文本，
    // 方剂/治法恒空是数据如此，不是接线问题。
    private static final List<EntityType> ALL = List.of(
            new EntityType("disease", "疾病", "diseases", true, "diseases.json",
                    List.of("tcmDiagnosis", "westernDiagnosis"), 1),
            new EntityType("pattern", "证候", "patternList", true, "patterns.json",
                    List.of("pattern"), 2),
            new EntityType("symptom", "症状", "symptoms", true, "symptoms.json",
                    List.of("chiefComplaint", "selfReport", "presentIllness"), 3),
            new EntityType("herb", "中药", "herbs", true, "herbs.json",
                    List.of("prescription"), 4),
            new EntityType("formula", "方剂", "formulaList", true, "formulas.json",
                    List.of(), 5),
            // 舌象/脉象/治法自批次 20 起有词典（此前 dict=false）：
            // 子要素先由 python-nlp 按标点分段抽成独立段（舌质/舌苔/齿痕/裂纹、
            // 脉位与脉象分开），再归一；抽不到或词表未收录的段保留原文。
            // 是否把这三类计入「未归一扣分」由规则集的 standardization.elementTypes 决定
            // （出厂默认不含它们，理由见 QcRuleSet.defaultStandardization 的 javadoc）。
            new EntityType("tongue", "舌象", "tongueList", true, "tongues.json",
                    List.of("tongue"), 6),
            new EntityType("pulse", "脉象", "pulseList", true, "pulses.json",
                    List.of("pulse"), 7),
            new EntityType("cause", "病因", "causeList", true, "causes.json",
                    List.of(), 8),
            new EntityType("treatment", "治法", "treatmentList", true, "treatments.json",
                    List.of(), 9)
    );

    // P5.4：构建期用临时可变表，构建完即包成 unmodifiable 后赋给 final 字段，
    // 避免全局共享的 static map 被外部代码意外修改（单一来源被破坏难排查）。
    private static final Map<String, EntityType> BY_KEY;
    private static final Map<String, EntityType> DICT_BY_STRUCTURED;

    /**
     * {@link #all()} / {@link #dictKeys()} 的缓存视图（审查报告 §4.7）。
     *
     * 两者都只是上面那张 9 类常量表的过滤视图，内容在类加载后不再变化；原先每次调用
     * 都新建一份集合（{@code all()} 在单次标准化报表里被调 4 次）。缓存为不可变视图，
     * 调用方一律只读。
     *
     * DICT_KEYS 用 {@code unmodifiableSet(LinkedHashSet)} 而不是
     * Set.copyOf：后者的迭代顺序未定义（按每次 JVM 启动的 SALT 打乱），会破坏
     * 「顺序稳定」这条契约 —— {@code QcRuleSet.defaults()} 的 elementTypes 顺序
     * 与 EntityTypesDictionaryRegistrationTest 都依赖它。
     */
    private static final List<EntityType> ALL_VIEW;
    private static final Set<String> DICT_KEYS;

    static {
        Map<String, EntityType> byKey = new LinkedHashMap<>();
        Map<String, EntityType> dictByStructured = new LinkedHashMap<>();
        List<EntityType> sorted = new ArrayList<>(ALL);
        sorted.sort((a, b) -> Integer.compare(a.order(), b.order()));
        for (EntityType t : sorted) {
            byKey.put(t.key(), t);
            if (t.dict()) {
                dictByStructured.put(t.structuredKey(), t);
            }
        }
        BY_KEY = java.util.Collections.unmodifiableMap(byKey);
        DICT_BY_STRUCTURED = java.util.Collections.unmodifiableMap(dictByStructured);

        ALL_VIEW = List.copyOf(byKey.values());
        Set<String> dictKeys = new LinkedHashSet<>();
        for (EntityType t : byKey.values()) {
            if (t.dict()) {
                dictKeys.add(t.key());
            }
        }
        DICT_KEYS = java.util.Collections.unmodifiableSet(dictKeys);
    }

    private EntityTypes() {
    }

    /** 全部类型（按 order） */
    public static List<EntityType> all() {
        return ALL_VIEW;
    }

    /**
     * 有词典的类型 key 集合（疾病/证候/症状/中药/方剂/舌象/脉象/病因/治法，顺序稳定，共 9 类）。
     *
     * 这 9 类即「规则配置」弹窗里术语归一 / 术语标准化可勾选的范围；实际生效范围
     * 由规则集的 normalization.elementTypes（归一）与 standardization.elementTypes
     * （未归一扣分）决定。本方法给「可选项」，规则集给「已选项」。
     */
    public static Set<String> dictKeys() {
        return DICT_KEYS;
    }

    /** 按类型 key 取类型定义；key 为 null 或未登记时返回 null，调用方需自行兜底 */
    public static EntityType byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** 结构化字段名 → 有词典的类型（无词典/未知返回 null） */
    public static EntityType dictTypeByStructuredKey(String structuredKey) {
        return structuredKey == null ? null : DICT_BY_STRUCTURED.get(structuredKey);
    }

    /** 词典文件名；非词典类型返回 null */
    public static String fileNameOf(String key) {
        EntityType t = byKey(key);
        return t == null ? null : t.fileName();
    }
}
