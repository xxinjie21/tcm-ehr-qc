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
                        inv.getArgument(2), "测试词典", level, null));
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
}