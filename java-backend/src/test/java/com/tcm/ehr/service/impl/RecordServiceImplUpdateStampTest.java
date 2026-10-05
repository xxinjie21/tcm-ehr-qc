package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.DictionaryTermStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写回结构化数据时的**可追溯性戳**（批次 13 第一阶段，续守卫用例之后）。
 *
 * <p>锁住方法注释里写明、但极易在重构中丢掉的一条：落库的 JSON 必须带
 * <b>当前词典版本与词条数</b>（与解析链路同口径），这样才说得清「这份结构化数据依据哪一版词典」。</p>
 *
 * <p>断言方式刻意选了「包含版本串」而不是比对整段 JSON：戳的具体形状属实现细节，
 * 换了包装方式不该让测试红，但**戳有没有打上**必须红。</p>
 */
class RecordServiceImplUpdateStampTest {

    private static final String DICT_VERSION = "dict-v-9";

    private static RecordServiceImpl svc(RecordMapper mapper, DictionaryTermStore termStore) {
        RecordServiceImpl s = new RecordServiceImpl(
                new tools.jackson.databind.ObjectMapper(),   // 用真实序列化器：本用例要验的就是落库内容
                mock(com.tcm.ehr.service.INlpBatchService.class),
                mock(com.tcm.ehr.mapper.ReviewTaskMapper.class),
                termStore);
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

    private static Record accessibleRecord() {
        Record r = new Record();
        r.setId("r1");
        r.setOrgId("org-A");
        return r;
    }

    private static void context() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("写回成功 ⇒ 落库的 JSON 带当前词典版本戳（说得出依据哪一版词典）")
    void stampsDictionaryVersionOnWrite() {
        RecordMapper mapper = mock(RecordMapper.class);
        DictionaryTermStore termStore = mock(DictionaryTermStore.class);
        when(mapper.selectById(any())).thenReturn(accessibleRecord());
        when(termStore.effectiveDictVersion(anyString())).thenReturn(DICT_VERSION);
        when(termStore.effectiveTermCount(anyString())).thenReturn(72);
        context();
        try {
            svc(mapper, termStore).updateRecord("r1",
                    Map.of("structuredData", Map.of("symptoms", java.util.List.of())));

            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(mapper).updateStructuredData(eq("r1"), json.capture());
            assertTrue(json.getValue().contains(DICT_VERSION),
                    "落库 JSON 必须带词典版本戳，实际：" + json.getValue());
            assertTrue(json.getValue().contains("symptoms"),
                    "原始结构化内容不能被戳覆盖掉，实际：" + json.getValue());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("缺 structuredData ⇒ 拒绝，且不落库")
    void rejectsMissingStructuredData() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectById(any())).thenReturn(accessibleRecord());
        context();
        try {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> svc(mapper, mock(DictionaryTermStore.class))
                            .updateRecord("r1", Map.of("somethingElse", "x")));
            assertEquals("未提供结构化数据", ex.getMessage());
            verify(mapper, org.mockito.Mockito.never()).updateStructuredData(anyString(), anyString());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("body 为 null ⇒ 同样按「未提供结构化数据」拒绝，不 NPE")
    void rejectsNullBodyWithoutNpe() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectById(any())).thenReturn(accessibleRecord());
        context();
        try {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> svc(mapper, mock(DictionaryTermStore.class)).updateRecord("r1", null));
            assertEquals("未提供结构化数据", ex.getMessage());
            assertFalse(false);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
