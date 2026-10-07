package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.service.IDictionaryTermStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 统计「解析一次、多处复用」（性能审查 P0-3）。
 *
 * <p>锁两点：① 同一 record id 的 {@code structured_data} 只 {@code readValue} 一次
 * （请求级 {@code parsedCache} 命中即复用，第二次调用返回与第一次一致的结果，
 * 不再重新解析当前 JSON）；② 不带缓存时照旧每次如实解析（缓存的「命中」与「重算」
 * 两条路径都被验证）。</p>
 */
@DisplayName("StatsServiceImpl：结构化 JSON 解析一次且共享缓存")
class StatsServiceImplParseCacheTest {

    private static final String JSON_A = "{\"symptoms\":[{\"content\":\"失眠\"}],\"patternList\":[]}";
    private static final String JSON_B = "{\"symptoms\":[{\"content\":\"心悸\"}],\"patternList\":[]}";

    private static Record rec(String id, String structured) {
        Record r = new Record();
        r.setId(id);
        r.setStructuredData(structured);
        return r;
    }

    /** 反射调用私有 extractFromStructured(Record, String, Map) */
    @SuppressWarnings("unchecked")
    private static List<String> extract(StatsServiceImpl svc, Record r, String key,
                                        Map<String, Map<String, Object>> cache) throws Exception {
        Method m = StatsServiceImpl.class.getDeclaredMethod(
                "extractFromStructured", Record.class, String.class, Map.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(svc, r, key, cache);
    }

    private static StatsServiceImpl svc() {
        return new StatsServiceImpl(new ObjectMapper(), mock(IDictionaryTermStore.class),
                mock(com.tcm.ehr.common.cache.StatsCache.class));
    }

    @Test
    void cacheSharesParsedJsonAcrossKeysAndPreventsReparse() throws Exception {
        StatsServiceImpl svc = svc();
        Record r = rec("rec-1", JSON_A);
        Map<String, Map<String, Object>> cache = new HashMap<>();

        // 1. 首次：症状走缓存路径，解析出「失眠」
        List<String> first = extract(svc, r, "symptoms", cache);
        assertEquals(List.of("失眠"), first);
        assertEquals(1, cache.size(), "解析结果应落入缓存");

        // 2. 同一 record id、但 JSON 内容已换 —— 命中缓存应返回「第一次解析的结果」，
        //    证明没有重新 readValue（若无视缓存，这里会返回「心悸」）
        Record rChanged = rec("rec-1", JSON_B);
        List<String> cached = extract(svc, rChanged, "symptoms", cache);
        assertEquals(List.of("失眠"), cached, "同 id 命中缓存：不得重新解析新 JSON");
        assertEquals(1, cache.size(), "缓存条目数不增（未重复解析）");

        // 3. 证候回退原始列需要的 patternList 也应从同一份 cache 读（不再解析）
        assertTrue(extract(svc, rChanged, "patternList", cache).isEmpty());
        assertEquals(1, cache.size());
    }

    @Test
    void withoutCacheParsesCurrentJsonAsIs() throws Exception {
        StatsServiceImpl svc = svc();
        Record r = rec("rec-1", JSON_B);
        // 不带缓存时，按当前 JSON 如实解析
        assertEquals(List.of("心悸"), extract(svc, r, "symptoms", null));
    }

    @Test
    void invalidJsonYieldsNullAndNotStored() throws Exception {
        StatsServiceImpl svc = svc();
        Record bad = rec("bad-1", "not-json{");
        Map<String, Map<String, Object>> cache = new HashMap<>();
        assertNull(extract(svc, bad, "symptoms", cache), "非法 JSON 应按「没抽到」处理（null，触发回退）");
        assertTrue(cache.isEmpty(), "解析失败不落缓存");
    }
}