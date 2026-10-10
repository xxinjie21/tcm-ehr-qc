package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.cache.StatsCache;
import com.tcm.ehr.domain.po.Record;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 看板扩展 {@code extra()} 的三块统计已下沉 SQL 聚合（性能审查 P2-10）。
 *
 * <p>原实现把命中病历（4 万行 × 5 列投影）整份物化进堆，再对同一份 List 独立遍历三遍
 * （月 / 科室 / 分数）。改造后三块各自一条 {@code GROUP BY}，回传行数从「病历数」降到
 * 「月份数 / 科室数 / 分档数」。</p>
 *
 * <p>本测试锁四件事：<b>①</b> 不再整表拉行（{@code selectList} 一次都不调）；
 * <b>②</b> 三条聚合各调一次；<b>③</b> 交给 SQL 的条件**不带 ORDER BY**（与 {@code GROUP BY}
 * 不能共存，混进去就是运行期语法错）；<b>④</b> SQL 回来的分组行被翻译成与改造前**逐位一致**
 * 的契约形态（趋势升序 + 截断、科室空白归一 + 降序、分数分档 + 空桶补 0）。</p>
 */
@DisplayName("StatsServiceImpl：看板扩展三块统计已下沉 SQL")
class StatsExtraAggregateTest {

    private static final String ORG = "org-A";

    private static StatsServiceImpl svc(RecordMapper mapper) {
        StatsServiceImpl s = new StatsServiceImpl(mock(IDictionaryTermStore.class), mock(StatsCache.class));
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
        req.setAttribute("currentOrgId", ORG);
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    /** 一个分组行；值类型刻意混用 Long / Integer / BigDecimal —— MySQL 的 COUNT 与 SUM 返回类型不同 */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 只桩三块聚合，不桩 selectList —— 一旦回退成整表拉行，Mockito 会给出 null 并在此炸开 */
    private static RecordMapper mapper(List<Map<String, Object>> trend,
                                       List<Map<String, Object>> dept,
                                       List<Map<String, Object>> score) {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectTrendByMonth(any())).thenReturn(trend);
        when(mapper.selectDeptRates(any())).thenReturn(dept);
        when(mapper.selectScoreCounts(any())).thenReturn(score);
        return mapper;
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("三块各一条聚合 SQL，且不再整表拉行；条件不带 ORDER BY 但仍带数据域")
    void threeAggregatesAndNoRowFetch() {
        RecordMapper mapper = mapper(List.of(), List.of(), List.of());
        context();

        svc(mapper).extra(null);

        verify(mapper, times(0)).selectList(any());
        verify(mapper, times(1)).selectTrendByMonth(any());
        verify(mapper, times(1)).selectDeptRates(any());
        verify(mapper, times(1)).selectScoreCounts(any());

        ArgumentCaptor<QueryWrapper<Record>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectTrendByMonth(captor.capture());
        String sql = captor.getValue().getSqlSegment().toUpperCase();
        assertFalse(sql.contains("ORDER BY"),
                "聚合条件里混进 ORDER BY 会让拼出的 SQL 语法错：" + sql);
        assertTrue(sql.contains("ORG_ID"), "数据域仍必须叠加：" + sql);
    }

    @Test
    @DisplayName("趋势：SQL 行翻成 TrendPoint，月份升序，合格率按 1 位小数")
    void trendIsTranslatedAndSortedAscending() {
        // 故意乱序给：升序由实现保证（SQL 已 ORDER BY，Java 侧 TreeMap 再兜一层）
        RecordMapper mapper = mapper(
                List.of(row("month", "2026-11", "total", 4L, "qualified", 1L, "pendingReview", 3L),
                        row("month", "2026-10", "total", 3L, "qualified", 3L, "pendingReview", 0L)),
                List.of(), List.of());
        context();

        StatsVO vo = svc(mapper).extra(null);

        assertEquals(2, vo.getTrend().size());
        assertEquals("2026-10", vo.getTrend().get(0).getMonth());
        assertEquals(3, vo.getTrend().get(0).getTotal());
        assertEquals(3, vo.getTrend().get(0).getQualified());
        assertEquals(100.0, vo.getTrend().get(0).getQualifiedRate(), 1e-9);
        assertEquals(0, vo.getTrend().get(0).getPendingReview());
        assertEquals("2026-11", vo.getTrend().get(1).getMonth());
        assertEquals(25.0, vo.getTrend().get(1).getQualifiedRate(), 1e-9);
        assertFalse(vo.isTrendTruncated());
    }

    @Test
    @DisplayName("趋势：visit_time 为空的 NULL 分组被丢弃，不产生空月份桶")
    void trendDropsNullMonthGroup() {
        RecordMapper mapper = mapper(
                List.of(row("month", null, "total", 7L, "qualified", 0L, "pendingReview", 0L),
                        row("month", "2026-10", "total", 1L, "qualified", 1L, "pendingReview", 0L)),
                List.of(), List.of());
        context();

        StatsVO vo = svc(mapper).extra(null);

        assertEquals(1, vo.getTrend().size(), "NULL 分组不能变成一个月份");
        assertEquals("2026-10", vo.getTrend().get(0).getMonth());
    }

    @Test
    @DisplayName("趋势：超 12 个月只留最近 12 个，并置 trendTruncated")
    void trendTruncatesToLastTwelveMonths() {
        // 跨年造 13 个真实月份：2025-12 与 2026-01..2026-12
        List<String> months = new ArrayList<>();
        months.add("2025-12");
        for (int m = 1; m <= 12; m++) {
            months.add(String.format("2026-%02d", m));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long i = 1;
        for (String month : months) {
            rows.add(row("month", month, "total", i, "qualified", i, "pendingReview", 0L));
            i++;
        }
        RecordMapper mapper = mapper(rows, List.of(), List.of());
        context();

        StatsVO vo = svc(mapper).extra(null);

        assertEquals(12, vo.getTrend().size());
        assertTrue(vo.isTrendTruncated(), "被截断时必须告知前端");
        assertEquals("2026-01", vo.getTrend().get(0).getMonth(), "应丢掉最早的 2025-12");
        assertEquals("2026-12", vo.getTrend().get(11).getMonth());
    }

    @Test
    @DisplayName("科室：NULL / 空串 / 纯空白归一到「未填科室」并累加，整体按总数降序")
    void departmentBlankVariantsCollapseIntoUnfilled() {
        RecordMapper mapper = mapper(List.of(), List.of(
                row("department", "内科", "total", 4L, "qualified", 4L),
                row("department", "  内科  ", "total", 3L, "qualified", 0L),
                row("department", null, "total", 2L, "qualified", 1L),
                row("department", "", "total", 1L, "qualified", 0L),
                row("department", "   ", "total", 5L, "qualified", 5L)), List.of());
        context();

        StatsVO vo = svc(mapper).extra(null);

        assertEquals(2, vo.getDepartmentRates().size(),
                "空白的三种形态必须合并成一个桶，而不是各占一行");
        // 降序：内科 7 条 > 未填科室 8 条？ 实为 未填科室 8 > 内科 7
        assertEquals("未填科室", vo.getDepartmentRates().get(0).getDepartment());
        assertEquals(8, vo.getDepartmentRates().get(0).getTotal(), "2 + 1 + 5 必须累加");
        assertEquals(6, vo.getDepartmentRates().get(0).getQualified(), "1 + 0 + 5 必须累加");
        assertEquals("内科", vo.getDepartmentRates().get(1).getDepartment());
        assertEquals(7, vo.getDepartmentRates().get(1).getTotal(), "4 + 3（首尾空格已 trim）必须累加");
        assertEquals(4, vo.getDepartmentRates().get(1).getQualified());
        assertEquals(57.1, vo.getDepartmentRates().get(1).getQualifiedRate(), 1e-9);
    }

    @Test
    @DisplayName("分数：按原分档边界归桶，空桶补 0 且顺序固定，无分数的分组被丢弃")
    void scoreBucketsAreComputedAndNullDropped() {
        RecordMapper mapper = mapper(List.of(), List.of(), List.of(
                row("score", null, "cnt", 9L),
                row("score", 95, "cnt", 1L),
                row("score", 90, "cnt", 2L),
                row("score", 80, "cnt", 3L),
                row("score", 60, "cnt", 4L),
                row("score", 59, "cnt", 5L),
                // MySQL 的 SUM 走 DECIMAL，这里顺带钉住 BigDecimal 也能被读成计数
                row("score", 100, "cnt", new BigDecimal("6"))));
        context();

        StatsVO vo = svc(mapper).extra(null);

        assertEquals(5, vo.getScoreDistribution().size(), "固定 5 档，空桶也要在");
        assertEquals("90+", vo.getScoreDistribution().get(0).getBucket());
        assertEquals(9, vo.getScoreDistribution().get(0).getCount(), "1 + 2 + 6（含 BigDecimal）");
        assertEquals("80-89", vo.getScoreDistribution().get(1).getBucket());
        assertEquals(3, vo.getScoreDistribution().get(1).getCount());
        assertEquals("70-79", vo.getScoreDistribution().get(2).getBucket());
        assertEquals(0, vo.getScoreDistribution().get(2).getCount(), "空桶补 0");
        assertEquals("60-69", vo.getScoreDistribution().get(3).getBucket());
        assertEquals(4, vo.getScoreDistribution().get(3).getCount(), "60 属 60-69 档（边界含）");
        assertEquals("60以下", vo.getScoreDistribution().get(4).getBucket());
        assertEquals(5, vo.getScoreDistribution().get(4).getCount(), "59 属 60 以下档");
    }

    @Test
    @DisplayName("词典规模：读不到时按 -1 上报（既有口径不受本次改造影响）")
    void dictionaryCountsStillReported() {
        IDictionaryTermStore store = mock(IDictionaryTermStore.class);
        when(store.readEffective(any(), any())).thenThrow(new IllegalStateException("ES 挂了"));
        RecordMapper mapper = mapper(List.of(), List.of(), List.of());
        StatsServiceImpl s = new StatsServiceImpl(store, mock(StatsCache.class));
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
        context();

        StatsVO vo = s.extra(null);

        assertEquals(-1, vo.getDictionary().get("disease").intValue());
        assertEquals(-1, vo.getDictionary().get("formula").intValue());
        verify(mapper, never()).selectList(any());
    }
}
