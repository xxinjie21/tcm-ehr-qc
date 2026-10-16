package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *   <li><b>分级</b>：分数 &lt;60 → 无效；有逻辑冲突 → 待复核；≥80 → 合格；其余待复核
 *       （核心要素真缺失只作扣分项，不再有独立的「真缺失 ≥3 判无效」计数条）。</li>
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
    }

    @Test
    void onlyPulseMissingQualified() {
        Map<String, Object> data = new HashMap<>(fullData());
        data.remove("pulseList"); // 原始列也没填脉象 → 真缺失 -12

        ScoreResultVO vo = QcScorer.score(data, raw(), false, QcRuleSet.defaults());

        assertEquals(88, vo.getScore()); // 100 - 12
        assertEquals("合格", vo.getGrade()); // 88 ≥ 80（缺 1 项仍合格）
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
        // 本条只验「漏抽扣 6 而不是 12」，分级随阈值走（94 ≥ 80 ⇒ 合格）
        assertEquals("合格", vo.getGrade());
    }

    @Test
    void allCoreMissingInvalid() {
        ScoreResultVO vo = QcScorer.score(Map.of(), raw(), false, QcRuleSet.defaults());

        assertEquals("无效", vo.getGrade()); // 28 < 60
        assertEquals(28, vo.getScore()); // 100 - 6×12
    }

    /** 缺 3 项真缺失 = 64 分：只作扣分，落在「待复核」而非「无效」（旧口径按 真缺失≥3 判无效） */
    @Test
    void coreMissingThreePendingReview() {
        Map<String, Object> data = new HashMap<>(fullData());
        data.remove("herbs");
        data.remove("tongueList");
        data.remove("pulseList"); // 恰好 3 项真缺失

        ScoreResultVO vo = QcScorer.score(data, raw(), false, QcRuleSet.defaults());

        assertEquals(64, vo.getScore()); // 100 - 3×12
        assertEquals("待复核", vo.getGrade()); // 60 ≤ 64 < 80
    }

    /** 缺 4 项真缺失 = 52 分：跨过低无效线，判「无效」 */
    @Test
    void coreMissingFourInvalid() {
        Map<String, Object> data = new HashMap<>(fullData());
        data.remove("herbs");
        data.remove("tongueList");
        data.remove("pulseList");
        data.remove("patternList"); // 4 项真缺失

        ScoreResultVO vo = QcScorer.score(data, raw(), false, QcRuleSet.defaults());

        assertEquals(52, vo.getScore()); // 100 - 4×12
        assertEquals("无效", vo.getGrade()); // 52 < 60
    }

    /** 分级边界闭合：score == reviewUpper 判合格、score == invalid 判待复核、invalid-1 判无效 */
    @Test
    void gradeBoundariesAreInclusive() {
        // 满分 100：合格线抬到 100 → 恰好达标 ⇒ 合格
        QcRuleSet ok = QcRuleSet.defaults();
        ok.getThresholds().setReviewUpper(100);
        assertEquals("合格", QcScorer.score(fullData(), raw(), false, ok).getGrade());
        // 抬到 101 → 100 < 101 ⇒ 待复核
        QcRuleSet near = QcRuleSet.defaults();
        near.getThresholds().setReviewUpper(101);
        assertEquals("待复核", QcScorer.score(fullData(), raw(), false, near).getGrade());

        // 缺 1 项 = 88 分。为单独验证无效线，抬高合格线使 88 不落合格：
        // invalid=88 → 恰好不低于 ⇒ 待复核；invalid=89 → 88 < 89 ⇒ 无效
        Map<String, Object> data = new HashMap<>(fullData());
        data.remove("pulseList");
        QcRuleSet low = QcRuleSet.defaults();
        low.getThresholds().setReviewUpper(100);
        low.getThresholds().setInvalid(88);
        assertEquals("待复核", QcScorer.score(data, raw(), false, low).getGrade());
        QcRuleSet lower = QcRuleSet.defaults();
        lower.getThresholds().setReviewUpper(100);
        lower.getThresholds().setInvalid(89);
        assertEquals("无效", QcScorer.score(data, raw(), false, lower).getGrade());
    }

    /**
     * 证候命中「脾肾阳虚」的默认规则有两条（→中药、→方剂），且都未命中期望值 → 2 条冲突。
     * 有冲突即落待复核 —— 即便分数 80 已 ≥ 合格线，冲突仍把它拉回待复核。
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

    /**
     * 反漂移：{@link QcScorer#missingElements} 与 score() 的完整性扣分必须同口径。
     *
     * <p>AI 解读 / 助手现在复用 missingElements。这两者一旦分家，就会重现
     * 「同一份病历质控说缺、AI 说齐全」（审查报告 H1 / G1）—— 所以用一个用例把
     * 「要素清单 + 两档归属」钉死在两侧一致上。</p>
     */
    @Test
    void missingElementsAgreesWithCompletenessDeductions() {
        Map<String, Object> data = new HashMap<>(fullData());
        data.remove("pulseList"); // 结构化为空、原始列也空 → 真缺失
        Record raw = raw();
        raw.setTongue("舌淡红");
        data.remove("tongueList"); // 结构化为空、原始列有记录 → 漏抽

        QcRuleSet rules = QcRuleSet.defaults();
        QcScorer.Missing miss = QcScorer.missingElements(data, raw, rules);
        ScoreResultVO vo = QcScorer.score(data, raw, false, rules);

        assertEquals(Set.of("脉象"), new HashSet<>(miss.full()));
        assertEquals(Set.of("舌象"), new HashSet<>(miss.partial()));

        assertEquals(itemsWithPoints(vo, 12), new HashSet<>(miss.full()), "真缺失应两侧一致");
        assertEquals(itemsWithPoints(vo, 6), new HashSet<>(miss.partial()), "漏抽应两侧一致");
    }

    /** 核心字段缺失里扣指定分值（12 = 真缺失 / 6 = 漏抽）的要素名 */
    private static Set<String> itemsWithPoints(ScoreResultVO vo, int points) {
        return vo.getDeductions().stream()
                .filter(d -> "核心字段缺失".equals(d.getType()) && d.getPoints() == points)
                .map(ScoreResultVO.Deduction::getItem)
                .collect(Collectors.toSet());
    }

    /**
     * 未归一扣分必须随数量<b>分段递增</b>（2026-10-05 修的那一处）。
     *
     * <p>原先实现是 {@code Math.min(cap, miss * weightEach)}：未归一数达到 cap（默认 5）之后
     * 恒定只扣 5 分，再糟也不多扣 —— 实测 51.6% 的病历都停在这一档，这一项因此失去区分度。</p>
     *
     * <p>断言方式刻意用「5 条 vs 10 条的分数差」而不是某个绝对值：两组只差症状条数，
     * 其余扣分完全一致，差值就是这项扣分的变化量，且不依赖扣分明细的类型字符串
     * （那种断言一改文案就碎）。cap=5 时 5 条扣 5、10 条扣 6，故差值应为 1 分。</p>
     */
    @Test
    void unmatchedDeductionGrowsAfterCap() {
        Map<String, Object> five = new HashMap<>(fullData());
        five.put("symptoms", unmatchedSymptoms(5));
        Map<String, Object> ten = new HashMap<>(fullData());
        ten.put("symptoms", unmatchedSymptoms(10));

        int s5 = QcScorer.score(five, raw(), false, QcRuleSet.defaults()).getScore();
        int s10 = QcScorer.score(ten, raw(), false, QcRuleSet.defaults()).getScore();

        assertEquals(1, s5 - s10,
                "未归一条数超过 cap 后应每满一档多扣一档：否则大量未归一时扣分触顶、分数没有区分度");
    }

    /**
     * 年龄格式规则必须接受真实病历的<b>复合单位</b>写法。
     *
     * <p>原先的正则 {@code ^\d+(\.\d+)?(岁|个月|月|天)?$} 只允许<b>单个</b>单位后缀，
     * 实测把真实数据里 92.1% 的年龄（32550/35354）判成格式错误 —— 失败形态 100% 是
     * 「32岁6月」这类复合单位。这里把「合法写法一律不判错」钉死。</p>
     */
    @Test
    void compoundAgeUnitPassesFormatRule() {
        for (String age : List.of("45", "50岁", "32岁6月", "35岁2月", "6个月", "3天", "1.5岁", "32岁6个月")) {
            Record raw = new Record();
            raw.setAge(age);
            raw.setGender("男");

            ScoreResultVO vo = QcScorer.score(fullData(), raw, false, QcRuleSet.defaults());

            assertTrue(vo.getDeductions().stream().noneMatch(d -> "格式错误".equals(d.getType())),
                    () -> "年龄「" + age + "」不该被判格式错误：" + vo.getDeductions());
        }
    }

    /** 反例：真正非法的年龄写法仍须被拒 —— 放宽不能放到「什么都放行」 */
    @Test
    void illegalAgeStillRejected() {
        Record raw = new Record();
        raw.setAge("约50岁");
        raw.setGender("男");

        ScoreResultVO vo = QcScorer.score(fullData(), raw, false, QcRuleSet.defaults());

        assertTrue(vo.getDeductions().stream().anyMatch(d -> "格式错误".equals(d.getType())),
                () -> "「约50岁」应判格式错误：" + vo.getDeductions());
    }

    /** 造 n 条「无 normLevel」的症状实体（无 normLevel = 未命中词典 = 计未归一） */
    private static List<Map<String, Object>> unmatchedSymptoms(int n) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(java.util.Map.of("content", "未归一症状" + i));
        }
        return out;
    }
}
