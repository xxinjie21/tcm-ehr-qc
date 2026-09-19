package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批B·2.3：QcScorer 三层扣分与分级路由。
 */
class QcScorerTest {

    private Map<String, Object> fullData() {
        return Map.of(
                "pulseList", List.of(Map.of("content", "脉浮")),
                "tongueList", List.of(Map.of("content", "舌淡红")),
                "patternList", List.of(Map.of("content", "风寒感冒")),
                "treatmentList", List.of(Map.of("content", "辛温解表")),
                "formulaList", List.of(Map.of("content", "麻黄汤")),
                "herbs", List.of(Map.of("name", "麻黄", "dosage", "6g")),
                "symptoms", List.of(Map.of("content", "发热")));
    }

    private Record raw() {
        Record r = new Record();
        r.setAge("45");
        r.setGender("男");
        return r;
    }

    @Test
    void allCoreMissingInvalid() {
        ScoreResultVO vo = QcScorer.score(Map.of(), raw(), false);
        assertTrue(vo.isSerious());
        assertEquals("无效", vo.getGrade());
        assertEquals(95, 100 - vo.getScore()); // 6×15 + symptoms空 5 = 95
    }

    @Test
    void onlyPulseMissingPendingReview() {
        Map<String, Object> data = new java.util.HashMap<>(fullData());
        data.remove("pulseList");
        ScoreResultVO vo = QcScorer.score(data, raw(), false);
        assertEquals(85, vo.getScore());
        assertEquals("待复核", vo.getGrade());
    }

    @Test
    void perfectQualified() {
        ScoreResultVO vo = QcScorer.score(fullData(), raw(), false);
        assertEquals(100, vo.getScore());
        assertEquals("合格", vo.getGrade());
    }

    @Test
    void coreMissingThreeInvalid() {
        Map<String, Object> data = new java.util.HashMap<>(fullData());
        data.remove("treatmentList");
        data.remove("formulaList");
        data.remove("herbs");
        ScoreResultVO vo = QcScorer.score(data, raw(), false);
        assertTrue(vo.isSerious());
        assertEquals("无效", vo.getGrade());
    }

    @Test
    void bothLogicConflictsSerious() {
        Map<String, Object> data = new java.util.HashMap<>(fullData());
        data.put("patternList", List.of(Map.of("content", "风寒感冒")));
        data.put("treatmentList", List.of(Map.of("content", "辛凉解表")));
        data.put("formulaList", List.of(Map.of("content", "银翘散")));
        ScoreResultVO vo = QcScorer.score(data, raw(), false);
        assertTrue(vo.isSerious());
        assertEquals("无效", vo.getGrade());
        assertTrue(vo.getLogicConflicts().stream().anyMatch(c -> c.startsWith(LogicChecker.TYPE_TREATMENT)));
        assertTrue(vo.getLogicConflicts().stream().anyMatch(c -> c.startsWith(LogicChecker.TYPE_FORMULA)));
    }
}
