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
 * 固定 9 类（对齐 NLP 可抽取的实体）。批次 20 起有词典的是 8 类
 *
 * （疾病/证候/症状/中药/方剂/舌象/脉象/治法），病因仍无词典（依赖规则词表）。
 * 不落盘、不做用户自定义——词典范围限定在 NLP 能提取的实体。
 *
 *
 * ⚠️ 新增词典类型不会改变质控扣分口径：计分只认
 *
 * QcScorer.keyOf() 里硬编码的 5 类，新类型落到 default -> null
 * 后按 0 条未命中处理（详见《多批次实施计划》批次 20）。
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
     * @param countsUnnormalized
     *        该类未归一的实体**是否计入质控的未归一扣分**（批次 14 · 14.1 把它从
     *        QcScorer 的 switch 搬到这里，成为目录里的一项声明）。
     *        <p><b>为什么舌象/脉象/治法为 false</b>：它们的子要素由 python-nlp 按标点先分段
     *        （舌质/舌苔/齿痕/裂纹、脉位与脉象分开），分段本身有损；抽不到或词表未收录的段
     *        保留原文，若计入扣分，等于拿分段器的行为去惩罚用户。故这三类（以及无词典的病因）
     *        不计入未归一扣分 —— 这是政策，不是遗漏。</p>
     * @param standardRef
     *        该类型词典的**权威出处**（如「中国药典2025年版」），用于给未标注 source 的词条
     *        填默认来源。同样由 14.1 从 DictionaryServiceImpl 的 switch 搬来：那是纯数据，
     *        留在远处的结果是「新增词典类型要记得去改两处」，而漏改不会有任何编译错误。
     *        无词典或未登记出处的类型为 null。
     */
    public record EntityType(String key, String label, String structuredKey, boolean dict,
                             String fileName, List<String> fallback, int order,
                             boolean countsUnnormalized, String standardRef) {
    }

    // rawFields = 该类型的「专属原文来源列」。空列表不等于「抽不出来」：
    // 模型是对 NlpTextComposer 拼出的**整段文本**做 NER，只要方剂/治法的字样出现在
    // 任一已拼入的列里，模型的「方剂」标签仍会命中（python-nlp 的 LABEL_FIELD 有此标签）。
    // 空列表只表示「没有哪一列是专门给它准备的」——所以下面这两个别当成坏配置去补源：
    // 本数据集 prescription 列是纯中药清单（无方剂名）、也没有「治以…」文本，
    // 方剂/治法恒空是数据如此，不是接线问题。
    private static final List<EntityType> ALL = List.of(
            // 末尾两位：countsUnnormalized（未归一是否计入质控扣分）、standardRef（词典权威出处）
            new EntityType("disease", "疾病", "diseases", true, "diseases.json",
                    List.of("tcmDiagnosis", "westernDiagnosis"), 1, true,
                    "中医临床诊疗术语 疾病"),
            new EntityType("pattern", "证候", "patternList", true, "patterns.json",
                    List.of("pattern"), 2, true,
                    "中医病证分类与代码 GB/T 15657-2021"),
            new EntityType("symptom", "症状", "symptoms", true, "symptoms.json",
                    List.of("chiefComplaint", "selfReport", "presentIllness"), 3, true,
                    "中医临床诊疗术语 症状"),
            new EntityType("herb", "中药", "herbs", true, "herbs.json",
                    List.of("prescription"), 4, true,
                    "中国药典2025年版"),
            new EntityType("formula", "方剂", "formulaList", true, "formulas.json",
                    List.of(), 5, true,
                    "中医方剂大辞典"),
            // 舌象/脉象/治法自批次 20 起有词典（此前 dict=false）：
            // 子要素先由 python-nlp 按标点分段抽成独立段（舌质/舌苔/齿痕/裂纹、
            // 脉位与脉象分开），再在这里按 GB/T 47335.1/.2-2026、GB/T 16751.3-2023
            // 归一到标准术语；抽不到或词表未收录的段保留原文，不算未归一扣分
            // —— 这条政策自批次 14 起由本行的 countsUnnormalized=false 声明，
            // 不再靠 QcScorer 里那张远处的小表维护。
            new EntityType("tongue", "舌象", "tongueList", true, "tongues.json",
                    List.of("tongue"), 6, false,
                    "中医诊断学 舌象"),
            new EntityType("pulse", "脉象", "pulseList", true, "pulses.json",
                    List.of("pulse"), 7, false,
                    "中医诊断学 脉象"),
            new EntityType("cause", "病因", "causeList", false, null,
                    List.of(), 8, false, null),
            new EntityType("treatment", "治法", "treatmentList", true, "treatments.json",
                    List.of(), 9, false,
                    "GB/T 16751.3-2023 治法")
    );

    // P5.4：构建期用临时可变表，构建完即包成 unmodifiable 后赋给 final 字段，
    // 避免全局共享的 static map 被外部代码意外修改（单一来源被破坏难排查）。
    private static final Map<String, EntityType> BY_KEY;
    private static final Map<String, EntityType> DICT_BY_STRUCTURED;

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
    }

    private EntityTypes() {
    }

    /** 全部类型（按 order） */
    public static List<EntityType> all() {
        return new ArrayList<>(BY_KEY.values());
    }

    /** 有词典的类型 key 集合（疾病/证候/症状/中药/方剂/舌象/脉象/治法，顺序稳定） */
    public static Set<String> dictKeys() {
        // 从 9 类里筛出**有词典的 8 类**并取 key；LinkedHashSet 保住 order。
        // ⚠️ 别把这里读成「计分也用 8 类」：质控计分只认 QcScorer.keyOf() 里硬编码的 5 类
        // （疾病/证候/症状/中药/方剂），舌象/脉象/治法落 default → 按 0 条未命中处理。
        // 口径差异是有意的（见本类头部说明与《多批次实施计划》批次 20）。
        Set<String> out = new LinkedHashSet<>();
        for (EntityType t : BY_KEY.values()) {
            if (t.dict()) {
                out.add(t.key());
            }
        }
        return out;
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
