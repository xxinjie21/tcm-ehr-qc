package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ES 术语归一（README 核心算法：精确-包含-模糊三级匹配）。
 *
 * <p><b>分工</b>：ES 负责<b>召回</b>（倒排索引 + IK 分词 + fuzziness，只求不漏），
 * 命中的<b>判定</b>仍在 Java 侧按三级规则完成——①精确 ②双向包含 ③字符 Dice ≥ 阈值。
 * 判定不下推 ES 的原因：Dice 是字符集合相似度，ES 的 fuzzy 是编辑距离，两者在边界上不等价，
 * 下推会改变既有阈值语义（如「咽喉痛」vs「咽痛」=0.8 命中、vs「腰部冷痛」=0.29 拒绝）。</p>
 *
 * <p><b>为什么还要内存兜底</b>：ES 召回本身是近似检索，理论上存在「内存全量能命中、ES 未召回」
 * 的边界情况。为了做到<b>行为与改造前完全一致</b>，ES 未召回或未命中时会再用内存全量判一次；
 * ES 不可用时也直接走内存。代价是「未命中」路径要付一次内存遍历，命中路径只需在候选集内判定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsTermNormalizer {

    /** ES 召回候选上限：召回宁可宽，判定才从严 */
    private static final int RECALL_SIZE = 50;

    private final DictionaryStore store;
    private final IEsTermIndexService esTermIndexService;

    @Value("${elasticsearch.score-threshold:0.8}")
    private double scoreThreshold;

    public record NormalizeResult(String standardTerm, String source) {
    }

    public NormalizeResult normalize(String type, String term) {
        String input = term == null ? "" : term.trim();
        if (input.isEmpty()) {
            return new NormalizeResult(term, "");
        }

        // ① ES 召回 -> 判定（命中路径只需在候选集内比较）
        List<TermEntry> recalled = recall(type, input);
        if (!recalled.isEmpty()) {
            NormalizeResult hit = judge(recalled, input);
            if (hit != null) {
                log.debug("[归一] {} ES命中({}候选): {} -> {}", type, recalled.size(), input, hit.standardTerm());
                return hit;
            }
        }

        // ② 兜底：内存全量判定，保证「ES 漏召回」不会导致归一结果与改造前不一致
        List<TermEntry> all = store.get(type);
        if (!all.isEmpty()) {
            NormalizeResult hit = judge(all, input);
            if (hit != null) {
                log.debug("[归一] {} 内存命中({}条): {} -> {}", type, all.size(), input, hit.standardTerm());
                return hit;
            }
        }

        return new NormalizeResult(input, "");
    }

    /** ES 检索候选；ES 不可用/索引缺失时返回空列表，交由上层回退内存 */
    private List<TermEntry> recall(String type, String input) {
        try {
            return esTermIndexService.search(type, input, RECALL_SIZE);
        } catch (Exception e) {
            log.warn("[归一] {} ES 检索失败，回退内存词典：{}", type, e.getMessage());
            return List.of();
        }
    }

    /**
     * 既有三级判定（改造前后逐字保持，勿改顺序与比较符）。
     *
     * @return 命中结果；三级都不中返回 {@code null}
     */
    private NormalizeResult judge(List<TermEntry> entries, String input) {
        // 一级·精确：标准词/别名完全相等
        for (TermEntry e : entries) {
            if (e.getStandardTerm().equals(input)) {
                return new NormalizeResult(e.getStandardTerm(), e.getSource());
            }
            if (e.getAliases() != null && e.getAliases().contains(input)) {
                return new NormalizeResult(e.getStandardTerm(), e.getSource());
            }
        }

        // 二级·包含（双向）：输入包含标准词/别名 或 标准词/别名包含输入，多命中取最短标准词
        TermEntry bestContains = null;
        for (TermEntry e : entries) {
            boolean hit = e.getStandardTerm().contains(input)
                    || input.contains(e.getStandardTerm())
                    || containsAnyAlias(e, input);
            if (hit && (bestContains == null
                    || e.getStandardTerm().length() < bestContains.getStandardTerm().length())) {
                bestContains = e;
            }
        }
        if (bestContains != null) {
            return new NormalizeResult(bestContains.getStandardTerm(), bestContains.getSource());
        }

        // 三级·模糊：字符Dice相似度 ≥ 阈值，取最高分
        TermEntry bestFuzzy = null;
        double bestScore = 0;
        for (TermEntry e : entries) {
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
            return new NormalizeResult(bestFuzzy.getStandardTerm(), bestFuzzy.getSource());
        }

        return null;
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
