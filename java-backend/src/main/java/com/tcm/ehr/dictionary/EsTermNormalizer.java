package com.tcm.ehr.dictionary;

import com.tcm.ehr.dictionary.TermEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * ES术语归一三级匹配（README核心算法：精确-包含-模糊）
 * ①精确：标准词/别名完全相等
 * ②包含：双向包含（输入包含别名 或 别名包含输入），多命中取最短标准词
 * ③模糊：字符Dice相似度 ≥ scoreThreshold（"咽喉痛"vs"咽痛"=0.8命中；vs"腰部冷痛"=0.29拒绝）
 * 未命中返回原词、source置空
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsTermNormalizer {

    private final DictionaryStore store;

    @Value("${elasticsearch.score-threshold:0.8}")
    private double scoreThreshold;

    public record NormalizeResult(String standardTerm, String source) {
    }

    public NormalizeResult normalize(String type, String term) {
        String input = term == null ? "" : term.trim();
        if (input.isEmpty()) {
            return new NormalizeResult(term, "");
        }

        // 一级·精确：标准词/别名完全相等
        for (TermEntry e : store.get(type)) {
            if (e.getStandardTerm().equals(input)) {
                return new NormalizeResult(e.getStandardTerm(), e.getSource());
            }
            if (e.getAliases() != null && e.getAliases().contains(input)) {
                return new NormalizeResult(e.getStandardTerm(), e.getSource());
            }
        }

        // 二级·包含（双向）：输入包含标准词/别名 或 标准词/别名包含输入，多命中取最短标准词
        TermEntry bestContains = null;
        for (TermEntry e : store.get(type)) {
            boolean hit = e.getStandardTerm().contains(input)
                    || input.contains(e.getStandardTerm())
                    || containsAnyAlias(e, input);
            if (hit && (bestContains == null
                    || e.getStandardTerm().length() < bestContains.getStandardTerm().length())) {
                bestContains = e;
            }
        }
        if (bestContains != null) {
            log.debug("[归一] {} 包含命中: {} -> {}", type, input, bestContains.getStandardTerm());
            return new NormalizeResult(bestContains.getStandardTerm(), bestContains.getSource());
        }

        // 三级·模糊：字符Dice相似度 ≥ 阈值，取最高分
        TermEntry bestFuzzy = null;
        double bestScore = 0;
        for (TermEntry e : store.get(type)) {
            double s = dice(input, e.getStandardTerm());
            if (e.getAliases() != null) {
                for (String a : e.getAliases()) {
                    s = Math.max(s, dice(input, a));
                }
            }
            if (s > bestScore) {
                bestScore = s;
                bestFuzzy = e;
            }
        }
        if (bestFuzzy != null && bestScore >= scoreThreshold) {
            log.debug("[归一] {} 模糊命中({}): {} -> {}", type, bestScore, input, bestFuzzy.getStandardTerm());
            return new NormalizeResult(bestFuzzy.getStandardTerm(), bestFuzzy.getSource());
        }

        return new NormalizeResult(input, "");
    }

    private boolean containsAnyAlias(TermEntry e, String input) {
        if (e.getAliases() == null) return false;
        for (String a : e.getAliases()) {
            if (a.contains(input) || input.contains(a)) return true;
        }
        return false;
    }

    /** 字符集合Dice系数：2*|A∩B| / (|A|+|B|) */
    private double dice(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        Set<Character> sa = new HashSet<>();
        for (char c : a.toCharArray()) sa.add(c);
        Set<Character> sb = new HashSet<>();
        for (char c : b.toCharArray()) sb.add(c);
        int inter = 0;
        for (Character c : sa) {
            if (sb.contains(c)) inter++;
        }
        return 2.0 * inter / (sa.size() + sb.size());
    }
}
