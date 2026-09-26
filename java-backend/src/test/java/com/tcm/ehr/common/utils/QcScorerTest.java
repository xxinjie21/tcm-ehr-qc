package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QcScorer 三层扣分与分级路由（批B·2.3；批M/批Q/批S 之后按现行口径重写）。
 *
 * <p>计价口径全部来自 {@link QcRuleSet#defaults()}，测试只构造输入、不改规则：</p>
 * <ul>
 *   <li><b>完整性</b>：6 要素（症状/疾病/证候/舌象/脉象/中药）两档 —— 结构化为空且原始列也空
 *       = 真缺失 -12，原始列有 = 漏抽 -6。<b>治法与方剂已不是完整性要素</b>，缺它们不扣分
 *       （批M 把「数据源无该两列」的项退出了评分）。</li>
 *   <li><b>术语标准化</b>：实体没有 {@code normLevel} 即算未命中词典，每个 -1、上限 -5。</li>
 *   <li><b>分级</b>：真缺失 ≥3 项或分数 &lt;60 → 无效；有逻辑冲突 → 待复核；≥90 → 合格；其余待复核。</li>
 * </ul>
 *
 * <p>注意 {@link #raw()} 只填了年龄/性别，所以凡结构化为空的要素在原始列也取不到值 ——
 * 一律走「真缺失 -12」。要覆盖「漏抽 -6」得显式回填原始列，见
 * {@link #rawColumnPresentCountsAsPartialDeduction()}。</p>
 */
class QcScorerTest {

    /** 6 要素齐全、全部实体已归一、且不触发任何默认一致性规则 → 满分 100 合格 */
    private Map<String, Object> fullData() {
        return Map.of(
                "pulseList", List.of(entity("脉浮")),
                "tongueList", List.of(entity("舌淡红")),
                "patternList", List.of(entity("风寒感冒")),
                // 治法没有独立词典，不参与术语标准化
                "treatmentList", List.of(entity("辛温解表")),
                "formulaList", List.of(entity("麻黄汤")),
                "herbs", List.of(herb("麻黄")),
                "symptoms", List.of(entity("发热")),
                "diseases", List.of(entity("风寒感冒")));
    }

    /** 与 {@link #fullData()} 同构，但实体一律不带 normLevel → 5 类词典实体全算未命中 */
    private Map<String, Object> unnormalizedFullData() {
        return Map.of(
                "pulseList", List.of(Map.of("content", "脉浮")),
                "tongueList", List.of(Map.of("content", "舌淡红")),
                "patternList", List.of(Map.of("content", "风寒感冒")),
                "treatmentList", List.of(Map.of("content", "辛温解表")),
                "formulaList", List.of(Map.of("content", "麻黄汤")),
                "herbs", List.of(Map.of("name", "麻黄", "dosage", "6g")),
                "symptoms", List.of(Map.of("content", "发热")),
                "diseases", List.of(Map.of("content", "风寒感冒")));
    }

    /** normLevel=1 表示已精确命中词典标准词 */
    private static Map<String, Object> entity(String content) {
        return Map.of("content", content, "normLevel", 1);
    }

    /** 中药取 name 而不是 content（实体目录的口径） */
    private static Map<String, Object> herb(String name) {
        return Map.of("name", name, "dosage", "6g", "normLevel", 1);
    }

    /** 只填年龄/性别：两者都合规 → 格式项不扣分；其余要素的原始列为空 */
    private Record raw() {
        Record r = new Record();
        r.setAge("45");
        r.setGender("男");
        return r;
    }

    @Test
    void perfectQualified() {
        ScoreResultVO vo = QcScorer.score(fullData(), raw(), false, QcRuleSet.defaults());

        assertTrue(vo.getDeductions().isEmpty(), () -> "满分不该有扣分项：" + vo.getDeductions());
        assertEquals(100, vo.getScore());
        assertEquals("合格", vo.getGrade());
        assertFalse(vo.isSerious());
    }

    @Test
    void onlyPulseMissingPendingReview() {
        Map<String, Object> data = new HashMap<>(fullData());
        data.remove("pulseList"); // 原始列也没填脉象 → 真缺失 -12

        ScoreResultVO vo = QcScorer.score(data, raw(), false, QcRuleSet.defaults());

        assertEquals(88, vo.getScore()); // 100 - 12
        assertEquals("待复核", vo.getGrade()); // 60 ≤ 88 < 90
        assertFalse(vo.isSerious()); // 真缺失仅 1 项，未达 ≥3 的严重线
    }

    /** 结构化为空但原始列有记录 = 漏抽，扣 6 而不是 12，且不计入「真缺失」项数 */
    @Test
    void rawColumnPresentCountsAsPartialDeduction() {
        Map<String, Object> data = new HashMap<>(fullData());
        data.remove("pulseList");
        Record raw = raw();
        raw.setPulse("脉浮");

        ScoreResultVO vo = QcScorer.score(data, raw, false, QcRuleSet.defaults());

        assertEquals(94, vo.getScore()); // 100 - 6
        assertEquals("合格", vo.getGrade());
        assertFalse(vo.isSerious());
    }

    @Test
    void allCoreMissingInvalid() {
        ScoreResultVO vo = QcScorer.score(Map.of(), raw(), false, QcRuleSet.defaults());

        assertTrue(vo.isSerious()); // 6 项真缺失 ≥ 3
        assertEquals("无效", vo.getGrade());
        assertEquals(28, vo.getScore()); // 100 - 6×12
    }

    @Test
    void coreMissingThreeInvalid() {
        Map<String, Object> data = new HashMap<>(fullData());
        data.remove("herbs");
        data.remove("tongueList");
        data.remove("pulseList"); // 恰好 3 项真缺失 = 严重线

        ScoreResultVO vo = QcScorer.score(data, raw(), false, QcRuleSet.defaults());

        assertTrue(vo.isSerious());
        assertEquals("无效", vo.getGrade());
        assertEquals(64, vo.getScore()); // 100 - 3×12
    }

    /**
     * 证候命中「脾肾阳虚」的默认规则有两条（→中药、→方剂），且都未命中期望值 → 2 条冲突。
     * 有冲突即落待复核，而不是看分数够不够 90。
     */
    @Test
    void bothLogicConflictsPendingReview() {
        Map<String, Object> data = new HashMap<>(fullData());
        data.put("patternList", List.of(entity("脾肾阳虚")));
        data.put("herbs", List.of(herb("桂枝"))); // 期望 附子/肉桂
        data.put("formulaList", List.of(entity("银翘散"))); // 期望 济生肾气丸

        ScoreResultVO vo = QcScorer.score(data, raw(), false, QcRuleSet.defaults());

        assertEquals(2, vo.getLogicConflicts().size(), () -> "实际：" + vo.getLogicConflicts());
        assertTrue(vo.getLogicConflicts().stream().anyMatch(c -> c.startsWith("脾肾阳虚-中药")),
                () -> "实际：" + vo.getLogicConflicts());
        assertTrue(vo.getLogicConflicts().stream().anyMatch(c -> c.startsWith("脾肾阳虚-方剂")),
                () -> "实际：" + vo.getLogicConflicts());
        assertEquals(80, vo.getScore()); // 100 - 2×10
        assertEquals("待复核", vo.getGrade());
    }

    /** 未命中词典的实体每个扣 1 分，5 类词典实体全未命中时恰好触到 -5 的上限 */
    @Test
    void unnormalizedEntitiesDeductUpToCap() {
        ScoreResultVO vo = QcScorer.score(unnormalizedFullData(), raw(), false, QcRuleSet.defaults());

        assertEquals(95, vo.getScore());
        assertEquals("合格", vo.getGrade());
        assertTrue(vo.getDeductions().stream()
                        .anyMatch(d -> "术语未标准化".equals(d.getType()) && d.getPoints() == 5),
                () -> "应有 5 分的术语未标准化扣分：" + vo.getDeductions());
    }
}
