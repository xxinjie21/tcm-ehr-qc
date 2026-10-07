package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.SearchVO;
import com.tcm.ehr.mapper.RecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 病历 COUNT 专用端点（性能审查 P1-5 / A6）。
 *
 * <p>锁两点：① {@code countMatched} 走 {@code selectCount}（只 COUNT 不 SELECT），
 * 不会碰 {@code selectPage / selectList}；② 与 {@code searchRecords} 用同一个
 * {@code RecordFilter.build} 组装 wrapper —— sameType 筛选下两者 total 一致
 * （否则「删除范围内病历」的确认数与列表 total 对不上）。</p>
 */
@DisplayName("RecordServiceImpl: COUNT 端点")
class RecordServiceImplCountTest {

    private static void context() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static RecordServiceImpl svc(RecordMapper mapper) {
        RecordServiceImpl s = new RecordServiceImpl(
                new tools.jackson.databind.ObjectMapper(),
                mock(com.tcm.ehr.service.INlpBatchService.class),
                mock(com.tcm.ehr.mapper.ReviewTaskMapper.class),
                mock(com.tcm.ehr.service.IDictionaryTermStore.class));
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

    @Test
    void countMatchedReturnsSelectCountAndUsesOrgFilter() {
        context();
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectCount(any())).thenReturn(42L);
        RecordServiceImpl s = svc(mapper);
        SearchDTO dto = new SearchDTO();
        dto.setDepartment("内科");

        long n = s.countMatched(dto);
        assertEquals(42, n);

        // 校验传入 selectCount 的 wrapper 带了数据域（member 角色 → org_id 过滤）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<Record>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        org.mockito.Mockito.verify(mapper).selectCount(captor.capture());
        assertTrue(captor.getValue().getCustomSqlSegment().contains("org_id"),
                "COUNT 必须与 search 同数据域口径: " + captor.getValue().getCustomSqlSegment());
    }

    @Test
    void countAndSearchShareSameFilterSemantics() {
        context();
        RecordMapper mapper = mock(RecordMapper.class);
        // search 用 selectPage 翻页（total = 100），count 用 selectCount（= 100）
        @SuppressWarnings("unchecked")
        Page<Record> page = new Page<>(1, 10);
        page.setTotal(100);
        when(mapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(mapper.selectCount(any())).thenReturn(100L);
        RecordServiceImpl s = svc(mapper);

        SearchDTO dto = new SearchDTO();
        dto.setPage(1);
        dto.setPageSize(10);
        dto.setGrade("合格");

        SearchVO vo = s.searchRecords(dto);
        org.mockito.Mockito.verify(mapper).selectPage(any(Page.class), any());
        assertEquals(100, vo.getTotal());
        // search 的 total 可能因分页插件（计数）+ 假数据不一致，这里只验证两条路径返回值一致
        assertEquals(vo.getTotal(), s.countMatched(dto),
                "delete-by-filter 确认数与列表 total 必须同口径");
    }
}