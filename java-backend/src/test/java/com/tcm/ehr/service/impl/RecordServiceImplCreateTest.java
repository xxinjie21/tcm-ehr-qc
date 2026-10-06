package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.dto.CreateRecordDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.CreateRecordVO;
import com.tcm.ehr.mapper.RecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单条新增病历（批次 13 第一阶段，续删除/详情之后）。
 *
 * <p>原先清单里记着「createRecord 需 ES 替身」，实际读实现后是**错的**：它没有 ES 依赖，
 * 只是「两个必填校验 → 组装 21 字段 + 初始状态 + 去重哈希 → insert → 回传 id」。故本类不需要
 * 任何额外替身（这张清单本身也因此被修正了一次：先读实现，再判断可测性）。</p>
 *
 * <p>锁住三件事：① 必填校验在**碰 mapper 之前**拒绝；② 组装出的实体带全字段、初始
 * {@code status=pending}、且算了 {@code text_hash}（去重兜底与导入同口径）；③ 回传的 id
 * 就是落库那条的 id。</p>
 */
class RecordServiceImplCreateTest {

    private static RecordServiceImpl svc(RecordMapper mapper) {
        RecordServiceImpl s = new RecordServiceImpl(
                mock(tools.jackson.databind.ObjectMapper.class),
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

    private static void context() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static CreateRecordDTO dto() {
        CreateRecordDTO d = new CreateRecordDTO();
        d.setRegistrationNo("REG-1");
        d.setOutpatientNo("OUT-1");
        d.setGender("女");
        d.setAge("38");
        d.setChiefComplaint("失眠3月");
        d.setDepartment("内科");
        d.setVisitTime(LocalDateTime.of(2026, 10, 5, 10, 0));
        return d;
    }

    @Test
    @DisplayName("必填校验发生在碰 mapper 之前：空 dto / 缺登记号 / 缺门诊号 都拒绝且不落库")
    void validatesRequiredFieldsBeforeTouchingMapper() {
        RecordMapper mapper = mock(RecordMapper.class);
        assertEquals("请填写登记号",
                assertThrows(IllegalArgumentException.class, () -> svc(mapper).createRecord(null)).getMessage());

        CreateRecordDTO noReg = dto();
        noReg.setRegistrationNo("  ");
        assertEquals("请填写登记号",
                assertThrows(IllegalArgumentException.class, () -> svc(mapper).createRecord(noReg)).getMessage());

        CreateRecordDTO noOut = dto();
        noOut.setOutpatientNo("");
        assertEquals("请填写门诊号",
                assertThrows(IllegalArgumentException.class, () -> svc(mapper).createRecord(noOut)).getMessage());

        verify(mapper, never()).insert(any(Record.class));
    }

    @Test
    @DisplayName("成功路径：落库实体带全字段 + 初始 status=pending + 算了 text_hash，并回传同一个 id")
    void insertsFullRecordWithInitialStatus() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.insert(any(Record.class))).thenReturn(1);
        context();
        try {
            CreateRecordVO vo = svc(mapper).createRecord(dto());
            assertNotNull(vo.getId(), "必须回传新病历 ID");

            ArgumentCaptor<Record> captor = ArgumentCaptor.forClass(Record.class);
            verify(mapper).insert(captor.capture());
            Record r = captor.getValue();

            assertEquals(vo.getId(), r.getId(), "回传的 id 必须是落库那条的 id");
            assertEquals("org-A", r.getOrgId(), "入本组");
            assertEquals("REG-1", r.getRegistrationNo());
            assertEquals("OUT-1", r.getOutpatientNo());
            assertEquals("女", r.getGender());
            assertEquals("38", r.getAge(), "年龄在原始字段里是字符串，不要顺手转成数字");
            assertEquals("失眠3月", r.getChiefComplaint());
            assertEquals("内科", r.getDepartment());
            assertEquals(LocalDateTime.of(2026, 10, 5, 10, 0), r.getVisitTime());

            assertEquals("pending", r.getStatus(),
                    "新入库病历必须写 pending —— 留空等于多出第四个看不见的状态值");
            assertNotNull(r.getTextHash(), "去重哈希与导入同口径，必须算");
            assertFalse(r.getTextHash().isBlank());
            assertTrue(r.getId() != null && !r.getId().isBlank(), "主键由服务端生成");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("两条不同内容的病历，去重哈希不同（顺序/内容参与哈希）")
    void textHashDiffersByContent() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.insert(any(Record.class))).thenReturn(1);
        context();
        try {
            RecordServiceImpl s = svc(mapper);
            s.createRecord(dto());
            CreateRecordDTO other = dto();
            other.setChiefComplaint("头晕1周");
            s.createRecord(other);

            ArgumentCaptor<Record> captor = ArgumentCaptor.forClass(Record.class);
            verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
            String h1 = captor.getAllValues().get(0).getTextHash();
            String h2 = captor.getAllValues().get(1).getTextHash();
            assertFalse(h1.equals(h2), "内容不同则哈希不同，否则唯一键拦不住重复");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
