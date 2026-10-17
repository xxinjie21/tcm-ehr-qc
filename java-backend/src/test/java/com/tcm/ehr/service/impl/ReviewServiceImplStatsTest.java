package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewStatsVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.IReviewWriteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复核概览统计（性能审查 P1-5 / A6）：一次三点 COUNT，替代三次 pageSize=1 列表查询。
 *
 * <p>锁两点：① 三个数分别命中「待复核 / 已完成 / 待复核超期」的 COUNT（按调用顺序
 * 5 / 3 / 2）；② 每个 COUNT 都带着与列表相同的兜底条件（is_obsolete=0 + 数据域 org），
 * 超期桶额外带 deadline_time — 与 {@code listTasks} 同 wrapper 组装口径。</p>
 */
@DisplayName("ReviewServiceImpl: 复核概览 COUNT")
class ReviewServiceImplStatsTest {

    private static ReviewServiceImpl svc(ReviewTaskMapper mapper) {
        ReviewServiceImpl s = new ReviewServiceImpl(
                mock(RecordMapper.class),
                mock(EntityNormalizer.class),
                mock(IReviewWriteService.class));
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

    @Test
    void countStatsReturnsThreeBucketsWithSameWrapperGuards() {
        context();
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        // 按 countStats 调用顺序：待复核 → 已完成 → 待复核超期
        when(mapper.selectCount(any())).thenReturn(5L, 3L, 2L);

        ReviewServiceImpl s = svc(mapper);
        ReviewStatsVO vo = s.countStats(null);

        assertEquals(5, vo.getPending());
        assertEquals(3, vo.getDone());
        assertEquals(2, vo.getOverdue());

        // 记录每次 COUNT 的 wrapper，核对兜底条件与超期桶
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<ReviewTask>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, org.mockito.Mockito.times(3)).selectCount(captor.capture());
        List<QueryWrapper<ReviewTask>> wrappers = captor.getAllValues();
        assertEquals(3, wrappers.size());

        for (QueryWrapper<ReviewTask> w : wrappers) {
            String sql = w.getCustomSqlSegment();
            // MP 渲染的是 #{ew.paramNameValuePairs.MPGENVALn} 占位符，这里只断言列名存在
            assertTrue(sql.contains("is_obsolete"), "每个桶都要过滤未失效任务: " + sql);
            assertTrue(sql.contains("org_id"), "每个桶都要带数据域过滤: " + sql);
        }
        assertTrue(wrappers.get(0).getCustomSqlSegment().contains("status"),
                "待复核桶必须带状态过滤: " + wrappers.get(0).getCustomSqlSegment());
        assertTrue(wrappers.get(2).getCustomSqlSegment().contains("deadline_time"),
                "超期桶必须加 deadline_time < now: " + wrappers.get(2).getCustomSqlSegment());
    }

    @Test
    void countStatsUsesSelectCountNotPagedSelect() {
        context();
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L, 1L, 1L);
        ReviewServiceImpl s = svc(mapper);
        s.countStats(null);
        // 三个桶各走一次 COUNT（selectCount），不得有任何 selectPage ——
        // 防止回退到「只取 total 也走全表排序」的旧路径
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(3)).selectCount(any());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .selectPage(any(), any());
    }

    /**
     * 范围过滤必须落到关联病历上（record_id IN 子查询），且统计与列表同一 wrapper：
     * review_tasks 表没有科室 / 时间列，任务的范围只能由关联病历定义。
     * 子查询里不再叠加 org 条件 —— 数据域由 review_tasks.org_id 那层负责，
     * 否则管理员「看全部」会被子查询里的 org 过滤抵消。
     *
     * <p>countStats 三个桶（待复核 / 已完成 / 超期）各查一次 COUNT，因此是 3 次
     * selectCount —— 三个桶都必须带上同一段范围子查询（同一 wrapper 组装口径）。</p>
     */
    @Test
    void scopeNarrowsTasksByLinkedRecordSubquery() {
        context();
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        ReviewServiceImpl s = svc(mapper);

        FiltersDTO scope = new FiltersDTO();
        scope.setDepartment("中医内科");
        scope.setDateRange(List.of("2026-10-01", "2026-10-31"));
        scope.setImportRange(List.of("2026-10-17", "2026-10-17"));
        s.countStats(scope);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<ReviewTask>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, org.mockito.Mockito.times(3)).selectCount(captor.capture());
        List<QueryWrapper<ReviewTask>> wrappers = captor.getAllValues();
        for (QueryWrapper<ReviewTask> w : wrappers) {
            String sql = w.getCustomSqlSegment();
            assertTrue(sql.contains("record_id IN"), "范围必须经关联病历子查询收窄: " + sql);
            assertTrue(sql.contains("visit_time"), "接诊时间区间要进子查询: " + sql);
            assertTrue(sql.contains("create_time"), "导入时间区间要进子查询: " + sql);
            // 时间补全到分秒（与 RecordFilter.applyRange 同口径）：起 00:00:00 / 止 23:59:59
            List<Object> params = new ArrayList<>(w.getParamNameValuePairs().values());
            assertTrue(params.contains("2026-10-01 00:00:00"), "接诊起点要含首日零点: " + params);
            assertTrue(params.contains("2026-10-31 23:59:59"), "接诊终点要含末日末秒: " + params);
            assertTrue(params.contains("2026-10-17 00:00:00"), "导入起点要含首日零点: " + params);
            assertTrue(params.contains("2026-10-17 23:59:59"), "导入终点要含末日末秒: " + params);
            // department 经 {n} 占位符绑定（值走参数，不拼进 SQL 文本）
            assertTrue(params.contains("中医内科"), "科室要走参数绑定: " + params);
            assertTrue(!sql.contains("中医内科"), "科室不得出现在 SQL 文本里（防注入）: " + sql);
        }
    }

    /** 只给一端时间 = 当没给（与 RecordFilter 同口径），三个桶都不得产生半区间子查询 */
    @Test
    void halfRangeIsIgnored() {
        context();
        ReviewTaskMapper mapper = mock(ReviewTaskMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        ReviewServiceImpl s = svc(mapper);

        FiltersDTO scope = new FiltersDTO();
        scope.setImportRange(List.of("2026-10-17", ""));
        s.countStats(scope);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<ReviewTask>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, org.mockito.Mockito.times(3)).selectCount(captor.capture());
        for (QueryWrapper<ReviewTask> w : captor.getAllValues()) {
            String sql = w.getCustomSqlSegment();
            assertTrue(!sql.contains("record_id IN"), "半区间不得产生子查询: " + sql);
        }
    }
}