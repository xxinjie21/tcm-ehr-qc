package com.tcm.ehr.common.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 诊疗逻辑一致性规则（批B·2.3，单源规则表；/api/qc/check/logic 与 QcScorer 共用）。
 *
 * <p>固定写死（v1 边界，不做规则表迁移/可视化配置）：</p>
 * <ul>
 *   <li>风寒感冒 → 辛温解表 → 麻黄汤 / 桂枝汤</li>
 *   <li>风热感冒 → 辛凉解表 → 银翘散 / 桑菊饮</li>
 *   <li>暑湿感冒 → 清暑祛湿 → 新加香薷饮</li>
 *   <li>舌脉：舌红 × 脉沉迟；舌淡 × 脉数</li>
 * </ul>
 * <p><b>未覆盖证候不判冲突</b>（不误杀）。</p>
 */
public final class LogicChecker {

    private LogicChecker() {
    }

    /** 规则条目（供图谱"证候→治法/方剂"规则子图复用；单源规则表） */
    public record Rule(String pattern, Set<String> treatments, Set<String> formulas) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("风寒感冒", Set.of("辛温解表"), Set.of("麻黄汤", "桂枝汤")),
            new Rule("风热感冒", Set.of("辛凉解表"), Set.of("银翘散", "桑菊饮")),
            new Rule("暑湿感冒", Set.of("清暑祛湿"), Set.of("新加香薷饮"))
    );

    /** 规则表只读视图（图谱复用，避免规则漂移） */
    public static List<Rule> rules() {
        return RULES;
    }

    public static final String TYPE_TREATMENT = "证候-治法";
    public static final String TYPE_FORMULA = "证候-方剂";
    public static final String TYPE_TONGUE_PULSE = "舌脉";

    /**
     * 检查证候-治法/方剂 与 舌脉冲突。
     *
     * @return 冲突描述列表（空表示一致）
     */
    public static List<String> check(List<String> patterns, List<String> treatments,
                                     List<String> formulas, List<String> tongues, List<String> pulses) {
        List<String> conflicts = new ArrayList<>();
        for (Rule rule : RULES) {
            boolean patternHit = patterns.stream().anyMatch(p -> matches(p, rule.pattern()));
            if (!patternHit) {
                continue;
            }
            if (!treatments.isEmpty() && treatments.stream().noneMatch(t -> matchesAny(t, rule.treatments()))) {
                conflicts.add(TYPE_TREATMENT + "不匹配：" + rule.pattern() + " × " + String.join("/", treatments));
            }
            if (!formulas.isEmpty() && formulas.stream().noneMatch(f -> matchesAny(f, rule.formulas()))) {
                conflicts.add(TYPE_FORMULA + "不匹配：" + rule.pattern() + " × " + String.join("/", formulas));
            }
        }
        // 舌象 × 脉象
        if (containsAny(tongues, "舌红") && containsAny(pulses, "脉沉迟")) {
            conflicts.add(TYPE_TONGUE_PULSE + "冲突：舌红 × 脉沉迟");
        }
        if (containsAny(tongues, "舌淡") && containsAny(pulses, "脉数")) {
            conflicts.add(TYPE_TONGUE_PULSE + "冲突：舌淡 × 脉数");
        }
        return conflicts;
    }

    private static boolean matches(String text, String term) {
        return text != null && term != null && (text.contains(term) || term.contains(text));
    }

    private static boolean matchesAny(String text, Set<String> terms) {
        return terms.stream().anyMatch(t -> matches(text, t));
    }

    private static boolean containsAny(List<String> list, String keyword) {
        return list.stream().anyMatch(s -> s != null && s.contains(keyword));
    }
}
