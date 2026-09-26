package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.EntityTypes;
import com.tcm.ehr.common.config.QcRuleSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 诊疗逻辑一致性评估。
 *
 * <p>规则来自 {@link QcRuleSet#getConsistency()}：当【触发类型】实体命中【触发值】时，
 * 若病历记录了【期望类型】实体，则其须命中【期望值】之一，否则记冲突；期望类型实体缺失 → 不适用。</p>
 */
public final class LogicChecker {

    private LogicChecker() {
    }

    /**
     * @param data 结构化数据（structured_data 反序列化后的 map）
     * @param rules 一致性规则
     * @return 冲突描述列表（形如"规则名：中药与证候不符"）
     */
    public static List<String> check(Map<String, Object> data, List<QcRuleSet.ConsistencyRule> rules) {
        List<String> conflicts = new ArrayList<>();
        if (rules == null || data == null) {
            return conflicts;
        }
        for (QcRuleSet.ConsistencyRule rule : rules) {
            if (rule.getTriggerType() == null || rule.getExpectType() == null
                    || rule.getTriggerValues() == null || rule.getTriggerValues().isEmpty()
                    || rule.getExpectValues() == null || rule.getExpectValues().isEmpty()) {
                continue;
            }
            List<String> triggers = listOf(data, rule.getTriggerType());
            List<String> expects = listOf(data, rule.getExpectType());
            // 触发类型无该实体 → 不适用
            if (triggers.isEmpty()) {
                continue;
            }
            boolean hit = triggers.stream().anyMatch(t -> anyContains(t, rule.getTriggerValues()));
            if (!hit) {
                continue;
            }
            // 期望类型没有记录 → 不适用（不误判）
            if (expects.isEmpty()) {
                continue;
            }
            boolean ok = expects.stream().anyMatch(e -> anyContains(e, rule.getExpectValues()));
            if (!ok) {
                String name = rule.getName() == null ? "一致性" : rule.getName();
                conflicts.add(name + "：" + labelOf(rule.getExpectType()) + "与" + labelOf(rule.getTriggerType()) + "不符");
            }
        }
        return conflicts;
    }

    /** 取某实体类型在结构化数据中的实体文本列表（herbs 取 name，其余取 content） */
    private static List<String> listOf(Map<String, Object> data, String type) {
        List<String> out = new ArrayList<>();
        EntityTypes.EntityType t = EntityTypes.byKey(type);
        if (t == null || !(data.get(t.structuredKey()) instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object v = m.get("content") != null ? m.get("content") : m.get("name");
                if (v != null && !String.valueOf(v).isBlank()) {
                    out.add(String.valueOf(v).trim());
                }
            } else if (item != null && !String.valueOf(item).isBlank()) {
                out.add(String.valueOf(item).trim());
            }
        }
        return out;
    }

    /** 类型 key → 中文名（描述冲突时用；类型不认识则原样返回 key） */
    private static String labelOf(String type) {
        EntityTypes.EntityType t = EntityTypes.byKey(type);
        return t == null ? type : t.label();
    }

    /** 文本是否包含任一关键词（contains 语义，不做分词） */
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
