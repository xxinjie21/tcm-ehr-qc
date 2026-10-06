package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.DeleteRecordsVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按 ID 删除的**执行**行为（批次 13 第一阶段，续守卫用例之后）。
 *
 * <p>锁两条从代码注释读出来、却在拆分时最容易走样的规则：</p>
 * <ol>
 * <li><b>先删子表再删主表</b>：{@code review_tasks.record_id} 有外键指向 records.id，顺序反了会撞约束
 *     （见方法注释「顺序不能反」）。这里用 {@link InOrder} 断言真实调用顺序，而不是只看结果。</li>
 * <li><b>请求体里的 id 不可信</b>：只删「按数据域查得到」的那些 —— 查不到的静默剔除、不报错
 *     （一条 id 属不属于本组是授权问题，不是业务错误）。</li>
 * </ol>
 *
 * <p>刻意不去断言 {@code DELETE_CHUNK} 的<b>具体值</b>：那是实现细节，改了不该让测试红。
 * 断言的是不变量 —— 删除总数等于过滤后的可达数量。</p>
 */
class RecordServiceImplDeleteExecutionTest {

    private static RecordServiceImpl svc(RecordMapper mapper, ReviewTaskMapper reviewTaskMapper) {
        RecordServiceImpl s = new RecordServiceImpl(
                mock(tools.jackson.databind.ObjectMapper.class),
                mock(com.tcm.ehr.service.INlpBatchService.class),
                reviewTaskMapper,
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

    private static Record record(String id) {
        Record r = new Record();
        r.setId(id);
        r.setOrgId("org-A");
        return r;
    }

    @Test
    @DisplayName("先删子表(review_tasks)再删主表(records) —— 外键顺序不能反")
    void deletesChildrenBeforeParents() {
        RecordMapper mapper = mock(RecordMapper.class);
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        // 数据域过滤后只剩两条可达
        when(mapper.selectList(any())).thenReturn(List.of(record("r1"), record("r2")));
        when(mapper.deleteBatchIds(anyList())).thenReturn(2);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        try {
            DeleteRecordsDTO dto = new DeleteRecordsDTO();
            dto.setIds(List.of("r1", "r2", "r-other-org"));   // 第三个不可达
            DeleteRecordsVO vo = svc(mapper, reviewTaskMapper).deleteRecords(dto);

            assertEquals(2, vo.getDeletedCount(), "只应删除数据域内可达的两条");

            InOrder order = inOrder(reviewTaskMapper, mapper);
            order.verify(reviewTaskMapper).delete(any());
            order.verify(mapper).deleteBatchIds(anyList());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<String>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(mapper).deleteBatchIds(captor.capture());
            assertEquals(List.of("r1", "r2"), captor.getValue(),
                    "传给主表删除的必须只是数据域过滤后的 id，不含不可达项");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("全部 id 都不可达 ⇒ 一条都不删，也不碰子表")
    void deletesNothingWhenAllIdsInaccessible() {
        RecordMapper mapper = mock(RecordMapper.class);
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());   // 数据域内查不到任何一条

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        try {
            DeleteRecordsDTO dto = new DeleteRecordsDTO();
            dto.setIds(List.of("r-other-org"));
            DeleteRecordsVO vo = svc(mapper, reviewTaskMapper).deleteRecords(dto);

            assertEquals(0, vo.getDeletedCount());
            verify(mapper, never()).deleteBatchIds(anyList());
            verify(reviewTaskMapper, never()).delete(any());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
