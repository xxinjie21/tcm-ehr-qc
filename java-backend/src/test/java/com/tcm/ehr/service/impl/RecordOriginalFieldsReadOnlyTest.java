package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryTermStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「原始病历文本只读」这条不变式的守护测试（批次 18 工作项 1）。
 *
 * 背景：病历的 21 个原始字段是质控与科研分析的**事实来源**。一旦被程序改写，
 * 所有基于它的统计结论随之失效。因此修改接口只允许写 structuredData，
 * 携带任何原始字段一律拒绝（错误语义 code=400）。
 *
 * 为什么必须有这个测试：这条约束此前只存在于 RecordServiceImpl 的实现里，
 * **一个断言都没有**。它属于「不抛异常、只是悄悄失效」的那类约定 ——
 * 一次重构（把校验挪走、或改用 DTO 收参时漏了字段检查）就会让原始文本变得可写，
 * 且不会有任何测试变红。属于开发指南 §六「关键路径必须可测」要求覆盖的范围。
 *
 * 三个断言面：
 * ① 21 个原始字段逐个被拒（防止将来往 ORIGINAL_FIELDS 漏加新字段）
 * ② 只带 structuredData 时能通过且真的落库（防止把接口改死，或静默不写）
 * ③ 拒绝发生时**没有发生任何写库**（防止「拒了但已经改了一半」）
 */
class RecordOriginalFieldsReadOnlyTest {

    /**
     * RecordServiceImpl.ORIGINAL_FIELDS 的 21 个字段。
     * 这里独立抄一份而不是反射读内部集合：字段名写错时测试应当失败，
     * 若反射读，字段名打错反而会跟着一起「通过」，等于没测。
     */
    private static final List<String> ORIGINAL_FIELDS = List.of(
            "registrationNo", "outpatientNo", "gender", "age", "visitCount",
            "westernDiagnosis", "tcmDiagnosis", "presentIllness", "chiefComplaint", "selfReport",
            "inspection", "pulse", "tongue", "physicalExam", "pattern", "prescription",
            "followUp", "treatmentEffect", "department", "doctorId", "visitTime");

    private RecordMapper recordMapper;
    private RecordServiceImpl svc;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        Record existing = new Record();
        existing.setId("r-1");
        existing.setOrgId("org-A");
        existing.setStatus("已完成");
        when(recordMapper.selectById("r-1")).thenReturn(existing);

        // termStore 用来给写回的结构化数据打词典版本戳（与解析链路同口径）
        IDictionaryTermStore termStore = mock(IDictionaryTermStore.class);
        when(termStore.effectiveDictVersion(anyString())).thenReturn("v-test");
        when(termStore.effectiveTermCount(anyString())).thenReturn(0);
        svc = new RecordServiceImpl(new ObjectMapper(), null, null, termStore);
        // baseMapper 来自 ServiceImpl 父类，构造器不接，只能反射塞进去
        ReflectionTestUtils.setField(svc, "baseMapper", recordMapper);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_USER_ID, "u-1");
        req.setAttribute(RequestUtils.ATTR_USERNAME, "alice");
        req.setAttribute(RequestUtils.ATTR_ROLE, "用户");
        req.setAttribute(RequestUtils.ATTR_ORG_ID, "org-A");
        req.setAttribute(RequestUtils.ATTR_ORG_ROLE, RequestUtils.ORG_ROLE_MEMBER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private Map<String, Object> bodyWith(String field) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("structuredData", Map.of("symptoms", List.of()));
        if (field != null) {
            body.put(field, "篡改后的值");
        }
        return body;
    }

    @Test
    @DisplayName("21 个原始字段逐个都不允许修改（这条正是被守护的约定）")
    void everyOriginalFieldIsRejected() {
        for (String field : ORIGINAL_FIELDS) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> svc.updateRecord("r-1", bodyWith(field)),
                    "原始字段 " + field + " 必须被拒绝 —— 它是质控与科研分析的事实来源，"
                            + "一旦可写，所有基于它的统计结论都不可信");
            assertEquals("原始字段不允许修改", e.getMessage(),
                    "拒绝原因要明确告知是原始字段，而不是含糊的「参数错误」");
        }
    }

    @Test
    @DisplayName("携带原始字段被拒时没有发生任何写库（防止拒了但已改了一半）")
    void rejectedUpdateWritesNothing() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateRecord("r-1", bodyWith("prescription")));

        verify(recordMapper, never()).updateStructuredData(anyString(), anyString());
    }

    @Test
    @DisplayName("只带 structuredData 能改成功并真的落库（防止把接口改死或静默不写）")
    void structuredDataAloneIsAccepted() {
        assertDoesNotThrow(() -> svc.updateRecord("r-1", bodyWith(null)));

        verify(recordMapper).updateStructuredData(org.mockito.ArgumentMatchers.eq("r-1"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("不带 structuredData 的空更新被拒（防止把空更新当成成功）")
    void emptyUpdateIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateRecord("r-1", new LinkedHashMap<>()));

        verify(recordMapper, never()).updateStructuredData(anyString(), anyString());
    }

    @Test
    @DisplayName("病历不存在时拒绝，而不是静默成功")
    void missingRecordIsRejected() {
        when(recordMapper.selectById("不存在")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> svc.updateRecord("不存在", bodyWith(null)));
    }
}