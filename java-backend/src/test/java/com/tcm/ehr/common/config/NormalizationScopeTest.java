package com.tcm.ehr.common.config;

import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.mapper.QcRuleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 术语归一的「范围」口径：
 *
 * <ul>
 *   <li>出厂默认范围 = 8 类（不含病因）；</li>
 *   <li>{@link QcRuleStore} 无行时回退默认（不回退成 null / 空）；</li>
 *   <li>{@code QcScorer} 的未归一扣分范围 = 质控勾选 ∩ 归一勾选（未开归一的类型不计）。</li>
 * </ul>
 */
class NormalizationScopeTest {

    private static Map<String, Object> ent(String content, Integer level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", content);
        if (level != null) {
            m.put("normLevel", level);
        }
        return m;
    }

    @Test
    @DisplayName("出厂默认归一范围是 8 类，且不含病因")
    void defaultNormalizationExcludesCause() {
        QcRuleSet r = QcRuleSet.defaults();
        assertNotNull(r.getNormalization(), "defaults() 必须带 normalization 块");
        Set<String> types = Set.copyOf(r.getNormalization().getElementTypes());
        assertEquals(8, types.size());
        assertTrue(types.contains("disease"));
        assertTrue(types.contains("treatment"));
        assertFalse(types.contains("cause"), "病因默认不参与归一");
    }

    @Test
    @DisplayName("组织无规则行时，归一范围回退到出厂默认（不回退成空）")
    void storeFallsBackToDefaultScope() {
        QcRuleStore store = new QcRuleStore(new ObjectMapper(), mock(QcRuleMapper.class));
        assertEquals(Set.copyOf(QcRuleSet.DEFAULT_NORMALIZATION_TYPES), store.normalizationTypes(null));
    }

    @Test
    @DisplayName("未归一扣分只算「质控勾选 ∩ 归一勾选」：范围外的类型不计")
    void qcCountsOnlyTypesInNormalizationScope() {
        QcRuleSet r = QcRuleSet.defaults();
        // 质控同时勾了症状与病因；但病因不在默认归一范围（8 类不含病因）
        r.getStandardization().setElementTypes(new ArrayList<>(List.of("symptom", "cause")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symptoms", List.of(ent("腰酸痛", null)));
        data.put("causeList", List.of(ent("湿热", null)));

        var ded = QcScorer.score(data, null, false, r).getDeductions().stream()
                .filter(d -> d.getType().equals("术语未标准化")).findFirst().orElseThrow();
        assertEquals("有 1 个实体未命中标准词典（症状 1）", ded.getReason(),
                "病因未纳入归一范围，其未命中不应计入");
    }

    @Test
    @DisplayName("归一范围为空（全部不归一）时，未归一扣分项整体不产生")
    void qcCountsNothingWhenNormalizationEmpty() {
        QcRuleSet r = QcRuleSet.defaults();
        r.getNormalization().setElementTypes(new ArrayList<>());
        r.getStandardization().setElementTypes(new ArrayList<>(List.of("symptom")));
        Map<String, Object> data = Map.of("symptoms", List.of(ent("腰酸痛", null)));
        assertTrue(QcScorer.score(data, null, false, r).getDeductions().stream()
                .noneMatch(d -> d.getType().equals("术语未标准化")));
    }
}
