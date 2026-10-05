package com.tcm.ehr.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.StandardizationReportVO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次 2（2026-10-05）：未归一词表缺口 TOP-N 明细（{@code unmatched.top}）的行为约束。
 *
 * <p>为什么要有这层测试：{@code top} 是给前端「先补哪几个词」用的输入。若它把
 * 「抽取碎片」「错放的脉象舌象」也混进来，用户照着补就会把噪声写进词典 —— 比没有这个入口更糟。
 * 因此这里锁死三件事：① 只收「词表缺口」一类；② 按出现次数降序；③ 最多 15 条。</p>
 *
 * <p>实现方式：{@code unmatched(...)} 是私有方法，用反射直接调用；服务是构造注入（无无参构造），
 * 故用 Mockito 造实例（Objenesis 绕过构造），再按字段类型反射注入 ObjectMapper。
 * 词表缺口项一律用**合成长词**（不含医学关键字、长度超过碎片阈值），避免猜测体征关键字表。</p>
 */
class StandardizationUnmatchedTopTest {

    private static final String LONG_PREFIX = "测试词甲乙丙丁戊";

    @Test
    void topKeepsOnlyDictionaryGapOrderedByCountCappedAtFifteen() throws Exception {
        StandardizationReportServiceImpl svc = Mockito.mock(StandardizationReportServiceImpl.class);
        injectObjectMapper(svc);

        // 16 个不同的长词（=> 词表缺口），出现次数依次 16,15,...,1；另加 1 个短碎片（应被排除）
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            records.add(recordWithRepeats(LONG_PREFIX + String.format("%02d", i), 16 - i));
        }
        records.add(recordWithRepeats("短", 9));

        StandardizationReportVO.UnmatchedBreakdown b =
                invokeUnmatched(svc, records, Collections.emptySet());

        Map<String, Integer> top = b.getTop();
        assertNotNull(top, "top 不应为 null");
        assertEquals(15, top.size(), "上限应为 15（16 个缺口词只保留 15 个）");

        List<String> keys = new ArrayList<>(top.keySet());
        assertEquals(LONG_PREFIX + "00", keys.get(0), "应按次数降序，最高频在最前");
        assertEquals(16, top.get(LONG_PREFIX + "00"), "最高频项次数应为 16");
        assertEquals(2, top.get(LONG_PREFIX + "14"), "第 15 名次数应为 2");
        assertFalse(top.containsKey(LONG_PREFIX + "15"), "第 16 名应被上限截掉");

        assertFalse(top.containsKey("短"), "抽取碎片不得进入可行动清单");
        assertTrue(b.getTotal() > 0, "总未归一条数应照常统计（碎片计入 total）");
        assertTrue(b.getFragment() > 0, "碎片应计入 fragment 分项");
        assertEquals(136, b.getDictionaryGap(), "缺口分项应为 16+15+...+1 = 136");
    }

    // ---------- 辅助 ----------

    private static Record recordWithRepeats(String content, int times) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < times; i++) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"content\":\"").append(content).append("\",\"normLevel\":null}");
        }
        Record r = new Record();
        r.setStructuredData("{\"symptoms\":[" + items + "]}");
        return r;
    }

    /** 服务用 ObjectMapper 解析 structuredData；按类型找到并注入，免依赖字段名。 */
    private static void injectObjectMapper(StandardizationReportServiceImpl svc) throws Exception {
        for (Class<?> c = StandardizationReportServiceImpl.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (ObjectMapper.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    f.set(svc, tools.jackson.databind.json.JsonMapper.builder().build());
                    return;
                }
            }
        }
        throw new IllegalStateException("未找到 ObjectMapper 字段");
    }

    private static StandardizationReportVO.UnmatchedBreakdown invokeUnmatched(
            StandardizationReportServiceImpl svc, List<Record> records, Set<String> symptomTerms) throws Exception {
        Method m = StandardizationReportServiceImpl.class
                .getDeclaredMethod("unmatched", List.class, Set.class);
        m.setAccessible(true);
        return (StandardizationReportVO.UnmatchedBreakdown) m.invoke(svc, records, symptomTerms);
    }
}
