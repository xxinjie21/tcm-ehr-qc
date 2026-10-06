package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.RawRecordVO;
import com.tcm.ehr.mapper.RecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 原始病历详情：**不可访问时归「不存在」** 与 **26 个字段的完整映射**
 * （批次 13 第一阶段，续删除相关用例之后）。
 *
 * <p>两类断言都不是形式主义：</p>
 * <ol>
 * <li><b>不可访问 ⇒ 返回 null（上层转 404），而不是 403。</b>
 *     代码注释写明了理由：把「存在但看不到」与「不存在」合并，攻击者就无法用响应差异
 *     探测别组的病历 ID。若日后有人「顺手」改成抛 ForbiddenException，这条测试会红。</li>
 * <li><b>26 个字段逐个映射。</b>拆类时最容易出的错就是「搬走了但少搬一个字段」——
 *     那不会报错，只会让详情页少显示一项。这里把每个字段都设成可区分的值再逐个断言。</li>
 * </ol>
 *
 * <p>本类与删除类用例共用同一条测试链路：反射注入 ServiceImpl 的 baseMapper，
 * 并用 RequestContextHolder 提供数据域上下文。</p>
 */
class RecordServiceImplRawDetailTest {

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

    private static void context(String orgId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", orgId);
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("别组的病历 ⇒ 返回 null（归「不存在」），不用 403 —— 避免被用来探测病历 ID")
    void returnsNullForOtherOrgRecord() {
        RecordMapper mapper = mock(RecordMapper.class);
        Record other = new Record();
        other.setId("r-other");
        other.setOrgId("org-B");          // 与请求上下文 org-A 不同组
        when(mapper.selectById(any())).thenReturn(other);
        context("org-A");
        try {
            assertNull(svc(mapper).getRawRecord("r-other"),
                    "不可访问必须与不存在同一种表现，否则能被用来枚举别组 ID");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("病历不存在 ⇒ 返回 null")
    void returnsNullForMissingRecord() {
        RecordMapper mapper = mock(RecordMapper.class);
        when(mapper.selectById(any())).thenReturn(null);
        context("org-A");
        try {
            assertNull(svc(mapper).getRawRecord("nope"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("可访问 ⇒ 21 个原始字段 + 结构化数据 + 评分三件套全部映射，一个都不能少")
    void mapsAllFields() {
        RecordMapper mapper = mock(RecordMapper.class);
        Record r = new Record();
        r.setId("r1");
        r.setOrgId("org-A");
        r.setRegistrationNo("REG-1");
        r.setOutpatientNo("OUT-1");
        r.setGender("男");
        r.setAge("41");
        r.setVisitCount(2);
        r.setWesternDiagnosis("高血压");
        r.setTcmDiagnosis("眩晕");
        r.setPresentIllness("现病史X");
        r.setChiefComplaint("主诉X");
        r.setSelfReport("自述X");
        r.setInspection("望X");
        r.setPulse("脉X");
        r.setTongue("舌X");
        r.setPhysicalExam("查体X");
        r.setPattern("证候X");
        r.setPrescription("处方X");
        r.setFollowUp("随访X");
        r.setTreatmentEffect("显效");
        r.setDepartment("内科");
        r.setDoctorId("doc-1");
        r.setVisitTime(LocalDateTime.of(2026, 10, 5, 9, 30));
        r.setStructuredData("{\"symptoms\":[]}");
        r.setScore(95);
        r.setGrade("甲");
        r.setStatus("已清洗");
        when(mapper.selectById(any())).thenReturn(r);
        context("org-A");
        try {
            RawRecordVO vo = svc(mapper).getRawRecord("r1");
            assertEquals("r1", vo.getId());
            assertEquals("REG-1", vo.getRegistrationNo());
            assertEquals("OUT-1", vo.getOutpatientNo());
            assertEquals("男", vo.getGender());
            assertEquals("41", vo.getAge(), "年龄在原始字段里是字符串，映射时不要顺手转成数字");
            assertEquals(2, vo.getVisitCount());
            assertEquals("高血压", vo.getWesternDiagnosis());
            assertEquals("眩晕", vo.getTcmDiagnosis());
            assertEquals("现病史X", vo.getPresentIllness());
            assertEquals("主诉X", vo.getChiefComplaint());
            assertEquals("自述X", vo.getSelfReport());
            assertEquals("望X", vo.getInspection());
            assertEquals("脉X", vo.getPulse());
            assertEquals("舌X", vo.getTongue());
            assertEquals("查体X", vo.getPhysicalExam());
            assertEquals("证候X", vo.getPattern());
            assertEquals("处方X", vo.getPrescription());
            assertEquals("随访X", vo.getFollowUp());
            assertEquals("显效", vo.getTreatmentEffect());
            assertEquals("内科", vo.getDepartment());
            assertEquals("doc-1", vo.getDoctorId());
            assertEquals(LocalDateTime.of(2026, 10, 5, 9, 30), vo.getVisitTime());
            assertEquals("{\"symptoms\":[]}", vo.getStructuredData());
            assertEquals(95, vo.getScore());
            assertEquals("甲", vo.getGrade());
            assertEquals("已清洗", vo.getStatus());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
