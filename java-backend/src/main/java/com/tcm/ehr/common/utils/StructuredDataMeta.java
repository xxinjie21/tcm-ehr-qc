package com.tcm.ehr.common.utils;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化数据落库前的元信息打点。
 *
 * <p>在 {@code structured_data} JSON 顶层写入保留键 {@code _meta}，记录该份结构化数据
 * <b>依据哪一版术语词典</b>产生，供前端展示"参照词典版本 xxx"（可追溯）。</p>
 *
 * <p><b>为什么安全</b>：所有下游消费者（{@code StatsServiceImpl} / {@code QcScorer}）都按
 * <b>指定 key</b> 取实体（如 {@code data.get("patternList")}），不遍历全 key，故 {@code _meta}
 * 不会被当成实体或词频统计进去；前端 {@code StructuredDataCard} 也按固定分区渲染。</p>
 */
public final class StructuredDataMeta {

    /** 保留键 */
    public static final String META_KEY = "_meta";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private StructuredDataMeta() {
    }

    /** 人工修改标记的字段名（true） */
    public static final String KEY_MANUAL = "manuallyEdited";
    /** 人工修改的操作人 */
    public static final String KEY_EDITED_BY = "editedBy";
    /** 人工修改的时间 */
    public static final String KEY_EDITED_AT = "editedAt";

    /**
     * 给结构化数据 JSON 打上词典版本元信息（自动流程：抽取 / 清洗）。
     *
     * @param json 结构化数据 JSON（可能为空）
     * @param dictVersion 词典版本串；空则不打点
     * @param dictTermCount 这次归一实际覆盖的词典词条数（可空）；用于在页面上把
     *                      版本串翻译成「依据 N 条词条」，否则用户只看到一个无从解读的哈希
     * @return 打点后的 JSON；解析/序列化失败时原样返回，绝不因打点失败而丢数据
     */
    public static String stamp(ObjectMapper mapper, String json, String dictVersion,
                               Integer dictTermCount) {
        // 1. 没内容或没版本号就不打点，原样返回
        if (json == null || json.isBlank() || dictVersion == null || dictVersion.isBlank()) {
            return json;
        }
        try {
            // 2. 解析后塞 _meta（版本 + 打点时刻 + 词条数），再序列化回去
            Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("dictVersion", dictVersion);
            meta.put("dictCapturedAt", LocalDateTime.now().withNano(0).format(TS));
            if (dictTermCount != null) {
                meta.put("dictTermCount", dictTermCount);
            }
            data.put(META_KEY, meta);
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            // 3. 打点失败原样返回：宁可没有版本信息，也不能弄丢结构化数据本身
            return json;
        }
    }

    /** 兼容旧调用点（不带词条数） */
    public static String stamp(ObjectMapper mapper, String json, String dictVersion) {
        return stamp(mapper, json, dictVersion, null);
    }

    /**
     * 给结构化数据 JSON 打上<b>人工修改</b>标记（人工流程：复核提交 / 手工改结构化数据）。
     *
     * <p><b>标记一律由后端写</b>，前端传不进来 —— 否则用户可以伪称或漏称，
     * 这个标记就失去可信度；而它唯一的用途正是「评估模型准确率时排除人工补过的数据」，
     * 一旦可自报就等于没有。</p>
     *
     * <p>与 {@link #stamp} 的区别：本方法<b>替换</b>整个 {@code _meta}（只留人工修改三要素），
     * 而 stamp 只写版本相关字段。人工修改后旧的版本戳本就失效（内容已被改），
     * 继续显示「依据某一版词典」是误导。</p>
     *
     * @param json     结构化数据 JSON
     * @param editedBy 操作人（当前登录用户名）
     * @return 打点后的 JSON；失败原样返回
     */
    public static String stampManual(ObjectMapper mapper, String json, String editedBy) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(KEY_MANUAL, true);
            meta.put(KEY_EDITED_BY, editedBy);
            meta.put(KEY_EDITED_AT, LocalDateTime.now().withNano(0).format(TS));
            data.put(META_KEY, meta);
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            // 打点失败不能丢数据：宁可没有人工修改标记
            return json;
        }
    }

    /**
     * 读出这份结构化数据是否含人工修改。
     *
     * <p>清洗归一会重跑标准化，把人工改成非标准词的术语又归一回标准词 ——
     * 方案 A 决定<b>跳过</b>人工修改过的病历，所以这个判断同时用于「清洗跳过」与「列表标记」。</p>
     *
     * @param mapper 用于解析的 ObjectMapper（由调用方注入，避免每次 new）
     * @param json   structured_data 列的原文
     * @return 含人工修改返回 true；解析失败一律返回 false（宁可漏标，不可误标）
     */
    public static boolean isManuallyEdited(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Object metaObj = data.get(META_KEY);
            if (!(metaObj instanceof Map<?, ?> meta)) {
                return false;
            }
            return asBoolean(meta.get(KEY_MANUAL));
        } catch (Exception e) {
            return false;
        }
    }

    /** JSON 里的 true / 1 / "true" 都算真（不同序列化器可能写出数字或字符串） */
    private static boolean asBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }
}
