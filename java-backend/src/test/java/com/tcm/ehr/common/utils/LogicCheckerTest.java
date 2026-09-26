package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.QcRuleSet;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LogicChecker 一致性规则（批B·2.3 旧版「3 证候 + 2 舌脉」已随批S 泛化为「类型 → 类型」，
 * 故本测试按新签名重写）。
 *
 * <p>判定链：触发类型实体命中触发值（包含匹配）→ 若期望类型<b>有</b>记录，则须命中期望值之一，
 * 否则记冲突；<b>期望类型没有记录 → 不适用、不判</b>（这条最容易出错，是本次重写的重点用例）。</p>
 *
 * <p>用例大多自建规则，不依赖 {@link QcRuleSet#defaults()} 的内置值 ——
 * 内置规则是按当前演示数据集调的，拿证候名硬凑会在规则改动时集体失效。
 * 只有最后两个用例专门验证「默认规则本身」的行为。</p>
 */
class LogicCheckerTest {

    private static QcRuleSet.ConsistencyRule rule(String name, String triggerType, List<String> triggerValues,
                                                 String expectType, List<String> expectValues) {
        QcRuleSet.ConsistencyRule r = new QcRuleSet.ConsistencyRule();
        r.setName(name);
        r.setTriggerType(triggerType);
        r.setTriggerValues(triggerValues);
        r.setExpectType(expectType);
        r.setExpectValues(expectValues);
        return r;
    }

    /** 只造 patternList（content）与 herbs（name）两个实体列表 */
    private static Map<String, Object> data(List<String> patterns, List<String> herbs) {
        Map<String, Object> m = new HashMap<>();
        m.put("patternList", patterns.stream().map(p -> Map.of("content", p)).toList());
        m.put("herbs", herbs.stream().map(h -> Map.of("name", h)).toList());
        return m;
    }

    private static final List<QcRuleSet.ConsistencyRule> COLD_RULE = List.of(
            rule("证候-中药", "pattern", List.of("风寒感冒"), "herb", List.of("麻黄")));

    @Test
    void triggerHitAndExpectMatched_noConflict() {
        List<String> conflicts = LogicChecker.check(data(List.of("风寒感冒"), List.of("麻黄")), COLD_RULE);

        assertTrue(conflicts.isEmpty(), () -> "实际：" + conflicts);
    }

    /** 冲突文案由后端拼装，前端直接展示 —— 格式锁定为「规则名：期望类型与触发类型不符」 */
    @Test
    void triggerHitAndExpectMismatch_conflict() {
        List<String> conflicts = LogicChecker.check(data(List.of("风寒感冒"), List.of("桂枝")), COLD_RULE);

        assertEquals(List.of("证候-中药：中药与证候不符"), conflicts);
    }

    @Test
    void triggerTypeAbsent_notApplicable() {
        assertTrue(LogicChecker.check(data(List.of(), List.of("桂枝")), COLD_RULE).isEmpty());
    }

    /** 触发类型的实体在，但没命中触发值 → 不适用 */
    @Test
    void triggerValueNotHit_notApplicable() {
        assertTrue(LogicChecker.check(data(List.of("风热感冒"), List.of("桂枝")), COLD_RULE).isEmpty());
    }

    /** 期望类型一个实体都没有 → 不适用；此时即便触发命中也不得误判 */
    @Test
    void expectTypeAbsent_notApplicable() {
        assertTrue(LogicChecker.check(data(List.of("风寒感冒"), List.of()), COLD_RULE).isEmpty());
    }

    /** 触发值的键整个不存在（不是空列表）同样不适用 */
    @Test
    void triggerKeyAbsent_notApplicable() {
        Map<String, Object> onlyHerbs = new HashMap<>();
        onlyHerbs.put("herbs", List.of(Map.of("name", "桂枝")));

        assertTrue(LogicChecker.check(onlyHerbs, COLD_RULE).isEmpty());
    }

    /** 触发与期望都按「包含」匹配，不是相等：实体「肝气郁结证」也能命中触发值「肝气郁结」 */
    @Test
    void matchingUsesContainsNotEquals() {
        List<QcRuleSet.ConsistencyRule> rules = List.of(
                rule("证候-中药", "pattern", List.of("肝气郁结"), "herb", List.of("柴胡", "白芍")));

        List<String> conflicts = LogicChecker.check(data(List.of("肝气郁结证"), List.of("桂枝")), rules);

        assertEquals(1, conflicts.size(), () -> "实际：" + conflicts);
    }

    /** 规则字段不完整（缺期望值）→ 跳过，不因配置残缺就报冲突 */
    @Test
    void incompleteRule_skipped() {
        List<QcRuleSet.ConsistencyRule> rules = List.of(
                rule("半成品规则", "pattern", List.of("风寒感冒"), "herb", List.of()));

        assertTrue(LogicChecker.check(data(List.of("风寒感冒"), List.of("麻黄")), rules).isEmpty());
    }

    @Test
    void nullArgs_returnEmpty() {
        assertTrue(LogicChecker.check(null, COLD_RULE).isEmpty());
        assertTrue(LogicChecker.check(data(List.of("风寒感冒"), List.of("桂枝")), null).isEmpty());
    }

    // ---------------------------------------------------------------- 默认规则本身

    /** 默认规则覆盖不到的证候不判冲突（旧用例用的是「肝肾亏虚」，而它恰好在内置触发值里，已换掉） */
    @Test
    void defaultRules_uncoveredPattern_noConflict() {
        List<String> conflicts = LogicChecker.check(
                data(List.of("风寒感冒"), List.of("麻黄")), QcRuleSet.defaults().getConsistency());

        assertTrue(conflicts.isEmpty(), () -> "实际：" + conflicts);
    }

    /** 默认规则命中且期望值不符 → 冲突，规则名取自内置清单 */
    @Test
    void defaultRules_coveredPattern_conflict() {
        // 「脾肾阳虚」的期望中药是 附子/肉桂
        List<String> conflicts = LogicChecker.check(
                data(List.of("脾肾阳虚"), List.of("桂枝")), QcRuleSet.defaults().getConsistency());

        assertEquals(1, conflicts.size(), () -> "实际：" + conflicts);
        assertTrue(conflicts.get(0).startsWith("脾肾阳虚-中药"), () -> "实际：" + conflicts);
    }
}
