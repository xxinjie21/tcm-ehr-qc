package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.cache.StatsCache;
import com.tcm.ehr.domain.dto.StatsDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.StatsAllVO;
import com.tcm.ehr.domain.vo.StatsVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryTermStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板词频「下沉到 SQL 聚合」（性能审查 P0-3#3）。
 *
 * <p>替代原先那条锁「同一份 JSON 只解析一次」的请求级缓存测试：解析本身已从 JVM 里移走
 * （下沉到 SQL），被测对象不存在了，那条测试随之作废。</p>
 *
 * <p>本测试锁四件事：<b>①</b> 四类实体走一趟 {@code selectTermFreqMulti}（不是四次单类查询）；
 * <b>②</b> 证候走 {@code selectPatternFreq}（带原始列兜底的专用语句）；
 * <b>③</b> 交给 SQL 的条件**不带 ORDER BY** —— 这是本改造成立的前提，
 * {@code ORDER BY} 与 {@code GROUP BY} 不能共存，混进去就是运行期语法错；
 * <b>④</b> SQL 回来的 {@code {term, cnt}} 被正确翻译成契约形态 {@code {键名: 词, count: 次数}}。</p>
 */
@DisplayName("StatsServiceImpl：词频聚合已下沉 SQL")
class StatsServiceImplAggregateTest {

    private static final String JSON_PATH_DISEASE = "$.diseases[*]";
    private static final String JSON_PATH_HERB = "$.herbs[*]";

    /** 让缓存直通 loader（本测试只关心聚合本身） */
    @SuppressWarnings("unchecked")
    private static StatsCache passThroughCache() {
        StatsCache cache = mock(StatsCache.class);
        when(cache.wordFreq(any(), any())).thenAnswer(inv ->
                ((Supplier<StatsAllVO>) inv.getArgument(1)).get());
        return cache;
    }

    private static StatsServiceImpl svc(RecordMapper mapper) {
        StatsServiceImpl s = new StatsServiceImpl(mock(IDictionaryTermStore.class), passThroughCache());
        for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("baseMapper");
                f.setAccessible(true);
                f.set(s, mapper);
                break;
            } catch (NoSuchFieldException ignored) {
                // 往父类继续找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return s;
    }

    private static void context() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static Map<String, Object> row(String term, long cnt) {
        Map<String, Object> m = new HashMap<>();
        m.put("term", term);
        m.put("cnt", cnt);
        return m;
    }

    private static Map<String, Object> kindRow(String kind, String term, long cnt) {
        Map<String, Object> m = row(term, cnt);
        m.put("kind", kind);
        return m;
    }

    private static StatsDTO dto(String type) {
        StatsDTO dto = new StatsDTO();
        dto.setType(type);
        return dto;
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("单类统计：一条 selectTermFreq 出结果，并翻成 {键名: 词, count: 次数}")
    void singleTypeUsesOneSqlCall() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectTermFreq(any(), eq(JSON_PATH_DISEASE), eq(10)))
                .thenReturn(List.of(row("耳鸣", 53L), row("不寐", 50L)));
        context();

        StatsVO vo = svc(mapper).stats(dto("disease"));

        assertEquals(2, vo.getStatistics().size());
        assertEquals("耳鸣", vo.getStatistics().get(0).get("disease"));
        assertEquals(53, vo.getStatistics().get(0).get("count"), "COUNT(*) 的 Long 要收敛成 int");
        assertTrue(vo.getStatistics().get(0).get("count") instanceof Integer,
                "与改前的 Map<String,Integer> 保持同一类型");
    }

    @Test
    @DisplayName("证候统计：走专用 selectPatternFreq（带原始 pattern 列兜底），落在 distribution")
    void patternUsesDedicatedSql() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectPatternFreq(any(), eq(10))).thenReturn(List.of(row("气滞痰阻证", 50L)));
        context();

        StatsVO vo = svc(mapper).stats(dto("pattern"));

        assertEquals(1, vo.getDistribution().size());
        assertEquals("气滞痰阻证", vo.getDistribution().get(0).get("pattern"));
        // 证候不该走通用单类语句：通用语句没有原始列兜底
        verify(mapper, times(0)).selectTermFreq(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("处方统计：方剂与中药各一条 selectTermFreq")
    void prescriptionUsesTwoSqlCalls() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectTermFreq(any(), eq("$.formulaList[*]"), eq(10))).thenReturn(List.of());
        when(mapper.selectTermFreq(any(), eq(JSON_PATH_HERB), eq(10))).thenReturn(List.of(row("白芍", 300L)));
        context();

        StatsVO vo = svc(mapper).stats(dto("prescription"));

        assertTrue(vo.getFormulaStats().isEmpty(), "formulaList 空就是空，不回退原始处方串");
        assertEquals("白芍", vo.getHerbStats().get(0).get("herb"));
        verify(mapper).selectTermFreq(any(), eq("$.formulaList[*]"), eq(10));
        verify(mapper).selectTermFreq(any(), eq(JSON_PATH_HERB), eq(10));
    }

    @Test
    @DisplayName("非法类型：仍然抛 IllegalArgumentException（报错文案不变）")
    void illegalTypeStillRejected() {
        context();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc(mock(RecordMapper.class)).stats(dto("bogus")));
        assertTrue(ex.getMessage().contains("统计类型不合法"), ex.getMessage());
    }

    @Test
    @DisplayName("看板 /stats/all：四类实体只发一条 selectTermFreqMulti（一趟扫完）")
    void allSendsOneMultiQueryForFourKinds() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectOverviewOrg(anyString())).thenReturn(Map.of(
                "totalRecords", 500L, "qualifiedCount", 480L,
                "pendingReviewCount", 15L, "invalidCount", 5L));
        when(mapper.selectTermFreqMulti(any(), eq(10))).thenReturn(List.of(
                kindRow("disease", "耳鸣", 53L),
                kindRow("symptom", "不寐", 50L),
                kindRow("formula", "六味地黄丸", 7L),
                kindRow("herb", "白芍", 300L)));
        when(mapper.selectPatternFreq(any(), eq(10))).thenReturn(List.of(row("气滞痰阻证", 50L)));
        context();

        StatsAllVO vo = svc(mapper).all(null);

        assertEquals("耳鸣", vo.getDisease().getStatistics().get(0).get("disease"));
        assertEquals("不寐", vo.getSymptom().getStatistics().get(0).get("symptom"));
        assertEquals("气滞痰阻证", vo.getPattern().getDistribution().get(0).get("pattern"));
        assertEquals("六味地黄丸", vo.getPrescription().getFormulaStats().get(0).get("formula"));
        assertEquals("白芍", vo.getPrescription().getHerbStats().get(0).get("herb"));
        // 四类实体只能一条 SQL；单类语句一次都不该被调用
        verify(mapper, times(1)).selectTermFreqMulti(any(), eq(10));
        verify(mapper, times(0)).selectTermFreq(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("交给 SQL 的条件不带 ORDER BY —— ORDER BY 与 GROUP BY 不能共存（P0-3#3 前提）")
    void aggregateWrapperHasNoOrderBy() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectTermFreqMulti(any(), eq(10))).thenReturn(List.of());
        when(mapper.selectPatternFreq(any(), eq(10))).thenReturn(List.of());
        when(mapper.selectOverviewOrg(anyString())).thenReturn(Map.of("totalRecords", 0L));
        context();

        svc(mapper).all(null);

        ArgumentCaptor<QueryWrapper<Record>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectTermFreqMulti(captor.capture(), eq(10));
        String sql = captor.getValue().getSqlSegment().toUpperCase();
        assertFalse(sql.contains("ORDER BY"),
                "聚合条件里混进 ORDER BY 会让拼出的 SQL 语法错：" + sql);
        assertTrue(sql.contains("ORG_ID"), "数据域仍必须叠加：" + sql);
    }

    @Test
    @DisplayName("单类统计的条件同样不带 ORDER BY")
    void singleTypeWrapperHasNoOrderBy() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectTermFreq(any(), anyString(), anyInt())).thenReturn(List.of());
        context();

        svc(mapper).stats(dto("disease"));

        ArgumentCaptor<QueryWrapper<Record>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectTermFreq(captor.capture(), eq(JSON_PATH_DISEASE), eq(10));
        assertFalse(captor.getValue().getSqlSegment().toUpperCase().contains("ORDER BY"));
    }

    @Test
    @DisplayName("JSON 路径白名单：非白名单路径直接拒绝（${} 是文本替换，不能放外部输入进 SQL）")
    void jsonPathWhitelistRejectsUnknownPath() throws Exception {
        context();
        StatsServiceImpl svc = svc(mock(RecordMapper.class));
        Method m = StatsServiceImpl.class.getDeclaredMethod(
                "termFreq", QueryWrapper.class, String.class, String.class);
        m.setAccessible(true);

        // 白名单内的放行（这里只需走到 mapper，用 mock 返回空列表即可）
        assertTrue(((List<?>) m.invoke(svc, new QueryWrapper<Record>(), JSON_PATH_DISEASE, "disease")).isEmpty());

        // 白名单外的（含明显的注入形态）必须被拒
        for (String bad : List.of("$.x[*]'; DROP TABLE records; --", "$._meta", "$.unknownList[*]")) {
            java.lang.reflect.InvocationTargetException ite = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> m.invoke(svc, new QueryWrapper<Record>(), bad, "disease"));
            assertTrue(ite.getCause() instanceof IllegalArgumentException,
                    "非白名单路径必须抛 IllegalArgumentException，实际: " + ite.getCause());
        }
    }
}
