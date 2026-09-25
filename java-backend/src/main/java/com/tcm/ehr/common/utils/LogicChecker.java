package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.QcRuleSet;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊疗逻辑一致性评估（批B·2.3，批Q 改为按配置的规则执行）。
 *
 * <p>规则来自 {@link QcRuleSet#getConsistency()}：证候命中关键词时，若病历记录了相应要素
 * （中药/舌象/脉象），则须与期望集合相符，否则记为冲突；<b>对应要素缺失则跳过（不适用）</b>。</p>
 */
public final class LogicChecker {

    private LogicChecker() {
    }

    /**
     * @return 冲突描述列表（形如"规则名：xxx与证候不符"）
     */
    public static List<String> check(List<String> patterns, List<String> tongues, List<String> pulses,
                                     List<String> herbs, List<QcRuleSet.ConsistencyRule> rules) {
        List<String> conflicts = new ArrayList<>();
        if (rules == null || patterns == null || patterns.isEmpty()) {
            return conflicts;
        }
        for (QcRuleSet.ConsistencyRule rule : rules) {
            if (rule.getPatternAny() == null || rule.getPatternAny().isEmpty()) {
                continue;
            }
            boolean hit = patterns.stream().anyMatch(p -> anyContains(p, rule.getPatternAny()));
            if (!hit) {
                continue;
            }
            String name = rule.getName() == null ? "一致性" : rule.getName();
            if (!rule.getExpectHerbs().isEmpty() && herbs != null && !herbs.isEmpty()
                    && herbs.stream().noneMatch(h -> anyContains(h, rule.getExpectHerbs()))) {
                conflicts.add(name + "：中药与证候不符");
            }
            if (!rule.getExpectTongue().isEmpty() && tongues != null && !tongues.isEmpty()
                    && tongues.stream().noneMatch(t -> anyContains(t, rule.getExpectTongue()))) {
                conflicts.add(name + "：舌象与证候不符");
            }
            if (!rule.getExpectPulse().isEmpty() && pulses != null && !pulses.isEmpty()
                    && pulses.stream().noneMatch(p -> anyContains(p, rule.getExpectPulse()))) {
                conflicts.add(name + "：脉象与证候不符");
            }
        }
        return conflicts;
    }

    private static boolean anyContains(String text, List<String> keys) {
        if (text == null) {
            return false;
        }
        for (String k : keys) {
            if (k != null && !k.isBlank() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
