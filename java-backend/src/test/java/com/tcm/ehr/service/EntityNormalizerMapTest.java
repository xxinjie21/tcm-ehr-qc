package com.tcm.ehr.service;

import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.common.utils.EntityNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code EntityNormalizer.normalizeMap}：人工修正写回前先归一。
 *
 * <p><b>要解决的缺陷</b>：复核员新输入/改写的词只带 {@code content}、没有
 * {@code normLevel}，而 {@code QcScorer.countUnnormalized} 把「无 normLevel」
 * 算作未标准化 → <b>人工修正反而扣分</b>（旧 ReviewServiceTest 里期望 96 就是
 * 「100 − 术语未标准化 4」，等于把缺陷写进了断言）。</p>
 */
class EntityNormalizerMapTest {

    private EntityNormalizer normalizerReturning(int level) {
        EsTermNormalizer term =
                mock(EsTermNormalizer.class);
        when(term.normalize(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new EsTermNormalizer.NormalizeResult(
                        inv.getArgument(2), level));
        return new EntityNormalizer(term, new tools.jackson.databind.ObjectMapper());
    }

    private static Map<String, Object> entity(String content) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("content", content);
        e.put("sourceText", content);
        // 刻意不给 normLevel —— 模拟「复核员新输入的词」
        return e;
    }

    @Test
    @DisplayName("人工输入的词被补上 normLevel，不再算未标准化")
    void newlyTypedTermsGetNormLevel() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patternList", List.of(entity("肝郁气滞")));

        EntityNormalizer svc = normalizerReturning(1);
        EntityNormalizer.NormStat stat = svc.normalizeMap(data, "org-A");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) data.get("patternList");
        assertEquals(1, out.size());
        assertNotNull(out.get(0).get("normLevel"),
                "归一后应带上 normLevel，否则 QcScorer 会算作未标准化而扣分");
        assertEquals(1, stat.hit());
    }

    @Test
    @DisplayName("_meta / modelAvailable / truncated 等 VO 没有的键必须原样保留")
    void keepsKeysThatVoDoesNotHave() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patternList", List.of(entity("肝郁气滞")));
        data.put("_meta", Map.of("dictVersion", "pattern:abc;"));
        data.put("modelAvailable", Boolean.TRUE);
        data.put("truncated", Boolean.FALSE);

        EntityNormalizer svc = normalizerReturning(1);
        svc.normalizeMap(data, "org-A");

        // 整体 Map↔VO 来回转换会丢这三个键，还会给未赋值属性写出 null 键
        assertTrue(data.containsKey("_meta"), "_meta 不应被归一丢掉");
        assertEquals(Boolean.TRUE, data.get("modelAvailable"));
        assertEquals(Boolean.FALSE, data.get("truncated"));
    }

    @Test
    @DisplayName("中药按 name 归一（不是 content）")
    void herbsNormalizeByName() {
        Map<String, Object> herb = new LinkedHashMap<>();
        herb.put("name", "柴胡");
        herb.put("dosage", "12g");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("herbs", List.of(herb));

        EntityNormalizer svc = normalizerReturning(1);
        svc.normalizeMap(data, "org-A");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) data.get("herbs");
        assertNotNull(out.get(0).get("normLevel"),
                "中药应以 name 为归一目标，回填后应带 normLevel");
    }

    @Test
    @DisplayName("结构化数据为 null 时直接返回空统计，不抛异常")
    void nullDataIsSafe() {
        EntityNormalizer.NormStat stat = normalizerReturning(1).normalizeMap(null, "org-A");
        assertEquals(0, stat.hit());
    }

    @Test
    @DisplayName("回写的仍是 Map 列表（不是 VO 强类型对象），否则调用方读 Map 会 ClassCastException")
    void writesBackMapsNotTypedObjects() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patternList", List.of(entity("肝郁气滞")));

        EntityNormalizer svc = normalizerReturning(1);
        svc.normalizeMap(data, "org-A");

        Object list = data.get("patternList");
        assertTrue(list instanceof List, "patternList 应仍是 List");
        for (Object item : (List<?>) list) {
            assertTrue(item instanceof Map,
                    "列表元素必须是 Map，实际是 " + item.getClass().getName()
                            + " —— 塞 VO 的 Entity/Herb 会让后续按 Map 读取的代码炸掉");
        }
    }

    @Test
    @DisplayName("inquiry（问诊子项）原样透传：不进归一、不写命中 normLevel、不丢字段")
    void inquiryPassesThroughUntouched() {
        Map<String, Object> data = new LinkedHashMap<>();
        // 问诊类整句（二便/睡眠/精神），刻意不带 normLevel（模拟裸生成）。
        // 二者本就不是症状词典标准词（ES 实测 miss），故 mock 让它们返回 level 0 = 未命中，
        // 从而不会被 {@code pullExactSymptomBack} 拉走（真实词典行为一致）。
        data.put("inquiry", List.of(entity("大便日一行"), entity("夜寐欠安")));
        // 对照组：一个真症状，应照常被归一
        data.put("symptoms", List.of(entity("头痛")));

        EsTermNormalizer term = mock(EsTermNormalizer.class);
        when(term.normalize(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String raw = inv.getArgument(2);
            boolean symptomHit = "头痛".equals(raw);  // 仅真症状精确命中
            return new EsTermNormalizer.NormalizeResult(raw, symptomHit ? 1 : 0);
        });
        EntityNormalizer svc = new EntityNormalizer(term, new tools.jackson.databind.ObjectMapper());
        svc.normalizeMap(data, "org-A");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inquiry = (List<Map<String, Object>>) data.get("inquiry");
        assertNotNull(inquiry, "inquiry 字段不应在归一后被丢弃");
        assertEquals(2, inquiry.size(), "inquiry 条数应原样保留");
        assertEquals("大便日一行", inquiry.get(0).get("content"));
        assertEquals("夜寐欠安", inquiry.get(1).get("content"));
        // inquiry 是「透传位」：绝不写命中层级的 normLevel。若 Jackson 把 null 序列化进 key，
        // 值必须是 null（等价于无 normLevel），绝不能是 1/2/3 这类命中层。
        assertNotHit(inquiry.get(0), "inquiry 不参与归一，不应写入命中层 normLevel");
        assertNotHit(inquiry.get(1), "inquiry 不参与归一，不应写入命中层 normLevel");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sym = (List<Map<String, Object>>) data.get("symptoms");
        assertEquals(Integer.valueOf(1), sym.get(0).get("normLevel"), "真症状仍应被归一");
    }

    /** 归一命中层级才允许（1/2/3）；null 或缺失都视为「未命中、不参与判定」 */
    private static void assertNotHit(Map<String, Object> entity, String msg) {
        Object normLevel = entity.get("normLevel");
        if (normLevel == null) {
            return; // 未写或 null 序列化，均为「无命中层级」≠ 被归一
        }
        assertTrue(!EsTermNormalizer.isHit(((Number) normLevel).intValue()), msg);
    }

    @Test
    @DisplayName("精确命中回拉：inquiry 里是症状词典精确标准词的拉回 symptoms，其余整句留在 inquiry")
    void pullBackExactSymptomTerms() {
        // type 感知 mock：仅「symptom 类型 + 已命中症状词」返回精确(level=1)，其余返回未命中(0)
        EsTermNormalizer term = mock(EsTermNormalizer.class);
        when(term.normalize(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String type = inv.getArgument(0);
            String raw = inv.getArgument(2);
            boolean symptomExact = type.equals("symptom")
                    && ("嗜睡".equals(raw) || "头痛".equals(raw));
            return new EsTermNormalizer.NormalizeResult(raw, symptomExact ? 1 : 0);
        });
        EntityNormalizer svc = new EntityNormalizer(term, new tools.jackson.databind.ObjectMapper());

        Map<String, Object> data = new LinkedHashMap<>();
        // 问诊整句：嗜睡 是症状词典精确标准词（应被拉回）；大便日行/夜寐欠安 是问诊整句（留在 inquiry）
        data.put("inquiry", List.of(entity("嗜睡"), entity("大便日行"), entity("夜寐欠安")));
        data.put("symptoms", List.of(entity("头痛")));

        svc.normalizeMap(data, "org-A");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inquiry = (List<Map<String, Object>>) data.get("inquiry");
        assertNotNull(inquiry, "inquiry 字段不应被丢弃");
        assertEquals(2, inquiry.size(), "嗜睡 被拉回后，inquiry 应只剩 大便日行 + 夜寐欠安 两条");
        assertEquals("大便日行", inquiry.get(0).get("content"));
        assertEquals("夜寐欠安", inquiry.get(1).get("content"));
        assertNotHit(inquiry.get(0), "问诊整句仍不参与命中，不应有 normLevel");
        assertNotHit(inquiry.get(1), "问诊整句仍不参与命中，不应有 normLevel");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sym = (List<Map<String, Object>>) data.get("symptoms");
        // 嗜睡 被拉回并归一，连同原有 头痛 一起
        assertEquals(2, sym.size(), "嗜睡 应被拉回 symptoms 参与归一");
        java.util.Set<String> contents = new java.util.HashSet<>();
        for (Map<String, Object> e : sym) {
            contents.add((String) e.get("content"));
            assertEquals(Integer.valueOf(1), e.get("normLevel"), "拉回/原有的症状都应被归一为 level=1");
        }
        assertTrue(contents.contains("嗜睡"), "嗜睡 应在症状中");
        assertTrue(contents.contains("头痛"), "原有症状 头痛 应保留");
    }
}
