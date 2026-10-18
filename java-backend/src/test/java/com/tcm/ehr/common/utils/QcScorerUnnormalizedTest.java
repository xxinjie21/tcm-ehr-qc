package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.ScoreResultVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 未归一口径：normLevel=0 也是「未命中」，不能因为写了 0 就免罚。 */
class QcScorerUnnormalizedTest {

    private static Map<String, Object> ent(String content, Integer level) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("content", content);
        if (level != null) m.put("normLevel", level);
        return m;
    }

    @Test
    void levelZeroCountsAsUnnormalized() {
        Map<String, Object> data = Map.of("symptoms", List.of(ent("腰酸痛", 0), ent("痛经", null)));
        ScoreResultVO vo = QcScorer.score(data, null, false, null);
        assertTrue(vo.getDeductions().stream().anyMatch(d -> d.getType().equals("术语未标准化")),
                "normLevel=0 也必须计入未命中：" + vo.getDeductions());
        assertTrue(vo.getDeductions().stream()
                        .filter(d -> d.getType().equals("术语未标准化")).findFirst().orElseThrow()
                        .getReason().contains("2"),
                "两条未命中都要算进 reason");
    }

    @Test
    void hitLevelsAreNotPenalized() {
        Map<String, Object> data = Map.of("symptoms", List.of(ent("带下", 1), ent("腹痛", 2), ent("脉弦细", 3)));
        assertTrue(QcScorer.score(data, null, false, null).getDeductions().stream()
                .noneMatch(d -> d.getType().equals("术语未标准化")));
    }

    @Test
    void reasonBreaksDownByType() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("symptoms", List.of(ent("腰酸痛", null)));
        data.put("herbs", List.of(ent("细胞角蛋白", null)));
        assertEquals("有 2 个实体未命中标准词典（症状 1 / 中药 1）",
                QcScorer.score(data, null, false, null).getDeductions().stream()
                        .filter(d -> d.getType().equals("术语未标准化")).findFirst().orElseThrow().getReason());
    }
}
