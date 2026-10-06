package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.mapper.RecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 按 ID 删除 / 修改 的前置守卫（批次 13 第一阶段，续 deleteByFilter 之后）。
 *
 * <p>这些用例锁的是「坏输入不进入写路径」：空 id、病历不存在。它们都比删库本身更重要 ——
 * 删库可以重来，删错了不能。</p>
 *
 * <p>本类还打通了后续测试都要用的一条链路：<b>把 mock 的 baseMapper 反射注入到
 * ServiceImpl 的 protected 字段</b>。其余 5 个公开方法的测试都依赖它
 * （批次 13 的覆盖表已随该批次完成归档，见 git 历史）。</p>
 */
class RecordServiceImplWriteGuardTest {

    private static RecordServiceImpl svc(RecordMapper mapper) {
        RecordServiceImpl s = new RecordServiceImpl(
                mock(tools.jackson.databind.ObjectMapper.class),
                mock(com.tcm.ehr.service.INlpBatchService.class),
                mock(com.tcm.ehr.mapper.ReviewTaskMapper.class),
                mock(com.tcm.ehr.service.IDictionaryTermStore.class));
        injectBaseMapper(s, mapper);
        return s;
    }

    /** MyBatis-Plus 的 baseMapper 是 protected 字段；沿类层次找，避免绑死某个版本的位置 */
    private static void injectBaseMapper(RecordServiceImpl target, RecordMapper mapper) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("baseMapper");
                f.setAccessible(true);
                f.set(target, mapper);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("baseMapper 注入失败：" + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("在类层次里没找到 baseMapper 字段");
    }

    private static void withRequestContext() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("按 ID 删除：null / 空列表 ⇒ 拒绝（空删会静默成功，用户以为删掉了）")
    void deleteRecordsRejectsEmptyIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc(mock(RecordMapper.class)).deleteRecords(null));
        assertEquals("未选择要操作的病历", ex.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> svc(mock(RecordMapper.class)).deleteRecords(new DeleteRecordsDTO()));
    }

    @Test
    @DisplayName("按 ID 删除：空 id 集合 ⇒ 拒绝")
    void deleteRecordsRejectsBlankIdList() {
        DeleteRecordsDTO dto = new DeleteRecordsDTO();
        dto.setIds(List.of());
        assertThrows(IllegalArgumentException.class, () -> svc(mock(RecordMapper.class)).deleteRecords(dto));
    }

    @Test
    @DisplayName("修改：病历不存在 ⇒ 报「病历不存在」，不静默通过")
    void updateRecordRejectsMissingRecord() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectById(any())).thenReturn(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc(mapper).updateRecord("r-missing", Map.of("structuredData", Map.of())));
        assertEquals("病历不存在", ex.getMessage());
    }

    @Test
    @DisplayName("修改：原始 21 字段只读 —— 显式携带即拒绝，且拒绝发生在落库之前")
    void updateRecordRejectsOriginalFields() {
        RecordMapper mapper = mock(RecordMapper.class);
        Record r = new Record();
        r.setId("r1");
        r.setOrgId("org-A");
        when(mapper.selectById(any())).thenReturn(r);
        withRequestContext();
        try {
            // 归属校验要过：先确认这条记录在测试上下文里是可访问的
            assertTrue(RecordFilter.canAccess(r), "前置条件：org-A 的 member 应可访问 org-A 的病历");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> svc(mapper).updateRecord("r1", Map.of("chiefComplaint", "改一改")));
            assertEquals("原始字段不允许修改", ex.getMessage());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
