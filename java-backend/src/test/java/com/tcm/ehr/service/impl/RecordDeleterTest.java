package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.DeleteRecordsVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A4（性能审查 P1-4）：deleteByFilter keyset 分批「边取边删」。
 *
 * <p>锁三点：① 每页只拉 {@code id, visit_time} 两列（不再 selectList 全部 id + 全行），
 * 且每页 LIMIT {@code DELETE_CHUNK}；② 第一页无锚点、第二页带 {@code (visit_time,id)}
 * keyset 锚点（不漏行）；③ 每页取到即「先子表后主表」删除，总删除数正确。</p>
 */
@DisplayName("RecordDeleter：范围删除游标分批（A4）")
class RecordDeleterTest {

    private static final String ORG = "org-del-1";
    private RecordMapper mapper;
    private ReviewTaskMapper reviewTaskMapper;
    private RecordDeleter deleter;

    @BeforeEach
    void setUp() {
        mapper = mock(RecordMapper.class);
        reviewTaskMapper = mock(ReviewTaskMapper.class);
        deleter = new RecordDeleter(mapper, reviewTaskMapper);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", ORG);
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static Record rec(String id, LocalDateTime vt) {
        Record r = new Record();
        r.setId(id);
        r.setOrgId(ORG);
        r.setVisitTime(vt);
        return r;
    }

    private static List<Record> page(int start, int n, LocalDateTime base) {
        List<Record> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(rec("d" + (start + i), base.plusSeconds(n - i)));
        }
        return out;
    }

    @Test
    @DisplayName("单页范围删除：只拉 id+visit_time、一次删除、总数正确")
    void singlePageDeletesAndProjectsOnlyNeededColumns() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        when(mapper.selectList(any())).thenReturn(List.of(rec("a", t), rec("b", t.plusSeconds(1)), rec("c", t.plusSeconds(2))));
        when(mapper.deleteBatchIds(any())).thenReturn(3);

        FiltersDTO f = new FiltersDTO();
        f.setDepartment("内科");
        DeleteRecordsVO vo = deleter.deleteByFilter(f);
        assertEquals(3, vo.getDeletedCount());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<Record>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        QueryWrapper<Record> w = captor.getValue();
        assertTrue(w != null && w.getSqlSelect() != null && w.getSqlSelect().contains("visit_time"),
                "必须只投影 id + visit_time（不含全行）: " + (w == null ? "null" : w.getSqlSelect()));
        assertTrue(w.getCustomSqlSegment().contains("LIMIT 500"), "每页必须 LIMIT DELETE_CHUNK: " + w.getCustomSqlSegment());

        // 先子表后主表：删除顺序正确
        verify(reviewTaskMapper).delete(any());
        verify(mapper).deleteBatchIds(any());
    }

    @Test
    @DisplayName("两页扫描：第二页带 (visit_time,id) keyset 锚点，不漏行")
    void twoPagesSecondCarriesKeysetAnchor() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        when(mapper.selectList(any())).thenReturn(page(0, 500, t), page(500, 200, t.minusDays(1)));
        when(mapper.deleteBatchIds(any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        FiltersDTO f = new FiltersDTO();
        f.setGrade("合格");
        DeleteRecordsVO vo = deleter.deleteByFilter(f);
        assertEquals(700, vo.getDeletedCount(), "两批 500+200 应全部删除");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<Record>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, times(2)).selectList(captor.capture());
        QueryWrapper<Record> w1 = captor.getAllValues().get(0);
        QueryWrapper<Record> w2 = captor.getAllValues().get(1);
        assertTrue(!w1.getCustomSqlSegment().contains("visit_time <"),
                "第一页不应带锚点: " + w1.getCustomSqlSegment());
        assertTrue(w2.getCustomSqlSegment().contains("visit_time <")
                        && w2.getCustomSqlSegment().contains("id >"),
                "第二页必须带 (visit_time,id) 锚点: " + w2.getCustomSqlSegment());
    }

    @Test
    @DisplayName("范围无匹配：selectList 空 → 不删、不碰子表")
    void noMatchDeletesNothing() {
        when(mapper.selectList(any())).thenReturn(List.of());
        FiltersDTO f = new FiltersDTO();
        f.setDepartment("不存在");
        DeleteRecordsVO vo = deleter.deleteByFilter(f);
        assertEquals(0, vo.getDeletedCount());
        verify(mapper, times(0)).deleteBatchIds(any());
        verify(reviewTaskMapper, times(0)).delete(any());
    }
}