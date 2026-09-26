package com.tcm.ehr.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体类型目录（批S）：结构化解析 / 术语词典 / 质控规则 三模块的<b>单一来源</b>。
 *
 * <p>固定 9 类（对齐 NLP 可抽取的实体）；其中 {@code dict=true} 的 5 类有独立术语词典
 * （疾病/证候/症状/中药/方剂），其余 4 类（舌象/脉象/病因/治法）仅结构化展示、不建词典。
 * <b>不落盘、不做用户自定义</b>——词典范围限定在 NLP 能提取的实体。</p>
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
            new EntityType("tongue", "舌象", "tongueList", false, null,
                    List.of("tongue"), 6),
            new EntityType("pulse", "脉象", "pulseList", false, null,
                    List.of("pulse"), 7),
            new EntityType("cause", "病因", "causeList", false, null,
                    List.of(), 8),
            new EntityType("treatment", "治法", "treatmentList", false, null,
                    List.of(), 9)
    );

    private static final Map<String, EntityType> BY_KEY = new LinkedHashMap<>();
    private static final Map<String, EntityType> DICT_BY_STRUCTURED = new LinkedHashMap<>();

    static {
        List<EntityType> sorted = new ArrayList<>(ALL);
        sorted.sort((a, b) -> Integer.compare(a.order(), b.order()));
        for (EntityType t : sorted) {
            BY_KEY.put(t.key(), t);
            if (t.dict()) {
                DICT_BY_STRUCTURED.put(t.structuredKey(), t);
            }
        }
    }

    private EntityTypes() {
    }

    /** 全部类型（按 order） */
    public static List<EntityType> all() {
        return new ArrayList<>(BY_KEY.values());
    }

    /** 有词典的类型（疾病/证候/症状/中药/方剂，按 order） */
    public static List<EntityType> dictTypes() {
        List<EntityType> out = new ArrayList<>();
        for (EntityType t : BY_KEY.values()) {
            if (t.dict()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 有词典的类型 key 集合（顺序稳定） */
    public static Set<String> dictKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (EntityType t : BY_KEY.values()) {
            if (t.dict()) {
                out.add(t.key());
            }
        }
        return out;
    }

    /** 按类型 key 取类型定义；key 为 {@code null} 或未登记时返回 {@code null}，调用方需自行兜底 */
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
