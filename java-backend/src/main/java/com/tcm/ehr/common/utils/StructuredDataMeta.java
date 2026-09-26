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

    /**
     * 给结构化数据 JSON 打上词典版本元信息。
     *
     * @param json 结构化数据 JSON（可能为空）
     * @param dictVersion 词典版本串；空则不打点
     * @return 打点后的 JSON；解析/序列化失败时原样返回，绝不因打点失败而丢数据
     */
    public static String stamp(ObjectMapper mapper, String json, String dictVersion) {
        if (json == null || json.isBlank() || dictVersion == null || dictVersion.isBlank()) {
            return json;
        }
        try {
            Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("dictVersion", dictVersion);
            meta.put("dictCapturedAt", LocalDateTime.now().withNano(0).format(TS));
            data.put(META_KEY, meta);
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            return json;
        }
    }
}
