package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.IDictionaryTermStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 清洗侧兜底归一的三个口径：以 sourceText 为输入（幂等）、否定不归一、命中即写 normLevel。
 * 三个反例都来自 2026-10-18 实测（心慌→心悸手抖 / 无黄汗→黄汗 / 词形相同不写 normLevel）。
 */
class GovernanceCleanIdempotentTest {

    private static Map<String, Object> sym(String content, String source) {
        return new LinkedHashMap<>(Map.of("content", content, "sourceText", source));
    }

    @Test
    void normalizesBySourceTextSoSecondPassIsIdempotent() {
        // 已归一过的实体：content=心悸（标准词）、sourceText=心慌。
        // 模拟 ES：对「心悸」召回 [心悸手抖(别名含心悸), 心悸]（这正是实测的召回顺序）
        EsTermNormalizer norm = mock(EsTermNormalizer.class);
        when(norm.normalize(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String term = inv.getArgument(2);
            if ("心慌".equals(term)) return new EsTermNormalizer.NormalizeResult("心悸", 1);
            if ("心悸".equals(term)) return new EsTermNormalizer.NormalizeResult("心悸手抖", 1);
            return new EsTermNormalizer.NormalizeResult(term, 0);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symptoms", new ArrayList<>(List.of(sym("心悸", "心慌"))));

        GovernanceServiceImpl svc = svc(norm);
        svc.normalizeForTest(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((List<?>) data.get("symptoms")).get(0);
        assertEquals("心悸", after.get("content"), "二次归一必须按 sourceText 归，不能把 content 再归一遍");
        assertEquals(1, after.get("normLevel"));
    }

    @Test
    void negatedEntityIsNotNormalized() {
        EsTermNormalizer norm = mock(EsTermNormalizer.class);
        when(norm.normalize(anyString(), anyString(), anyString()))
                .thenReturn(new EsTermNormalizer.NormalizeResult("黄汗", 2));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symptoms", new ArrayList<>(List.of(sym("无黄汗", "无黄汗"))));

        svc(norm).normalizeForTest(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((List<?>) data.get("symptoms")).get(0);
        assertEquals("无黄汗", after.get("content"), "否定语境不得归一（否则语义反转）");
        assertNull(after.get("normLevel"));
    }

    @Test
    void writesNormLevelEvenWhenTermUnchanged() {
        // 导入/手工录入的病历：content 本身就是标准词，但没有 normLevel
        EsTermNormalizer norm = mock(EsTermNormalizer.class);
        when(norm.normalize(anyString(), anyString(), anyString()))
                .thenReturn(new EsTermNormalizer.NormalizeResult("带下", 1));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symptoms", new ArrayList<>(List.of(sym("带下", "带下量多"))));

        svc(norm).normalizeForTest(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((List<?>) data.get("symptoms")).get(0);
        assertEquals(1, after.get("normLevel"), "命中词典就必须写 normLevel，否则质控永远算它未归一");
    }

    @SuppressWarnings("unchecked")
    private GovernanceServiceImpl svc(EsTermNormalizer norm) {
        GovernanceServiceImpl svc = new GovernanceServiceImpl(norm, new ObjectMapper(),
                mock(IDictionaryTermStore.class), mock(ReviewTaskMapper.class));
        ReflectionTestUtils.setField(svc, "baseMapper", mock(RecordMapper.class));
        return svc;
    }
}
