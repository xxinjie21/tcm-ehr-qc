package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.TermIndexUnavailableException;
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
 * ES 术语归一（精确-包含-模糊三级匹配）。
 *
 * <p><b>分工</b>：ES 负责<b>召回</b>（倒排索引 + IK 分词 + fuzziness，只求不漏），
 * 命中的<b>判定</b>仍在 Java 侧按三级规则完成——①精确 ②双向包含 ③字符 Dice ≥ 阈值。
 * 判定不下推 ES 的原因：Dice 是字符集合相似度，ES 的 fuzzy 是编辑距离，两者在边界上不等价，
 * 下推会改变既有阈值语义（如「咽喉痛」vs「咽痛」=0.8 命中、vs「腰部冷痛」=0.29 拒绝）。</p>
 *
 * <p><b>为什么没有内存兜底</b>（2026-09-23 用户决定）：原先 ES 未召回时会再对内存全量词典判一次
 * （{@code DictionaryStore}），本意是让「ES 漏召回」不至于漏判。但代价是<b>ES 的角色变得不可判定</b>：
 * 谁也不知道某次命中是 ES 给的还是兜底给的，ES 到底有没有在工作、需不需要维护，从外部看不出来；
 * 而 ES 挂掉时归一依然「有结果」，故障被静默掩盖。现在改成<b>ES 是唯一权威</b>：
 * 没召回就是未命中，ES 不可用则抛 {@link TermIndexUnavailableException} 让上层返回 503 ——
 * 宁可明确报「归一不可用」，也不要把「服务挂了」表现成「词典里没收录这个词」。</p>
 *
 * <p>取舍代价（已知并接受）：归一现在硬依赖 ES；ES 不可用时解析与清洗链路会失败而不是降级。
 * 词典仍然是 {@code data/dictionaries/*.json}（启动与导入时 rebuild 进 ES），
 * 所以恢复办法就是让 ES 恢复 —— 不需要再往内存里塞第二份。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsTermNormalizer {

    /** ES 召回候选上限：召回宁可宽，判定才从严 */
    private static final int RECALL_SIZE = 50;

    private final IEsTermIndexService esTermIndexService;

    @Value("${elasticsearch.score-threshold:0.8}")
    private double scoreThreshold;

    /**
     * 归一结果。
     *
     * @param standardTerm 命中的标准词（未命中时=原文）
     * @param source       术语来源（未命中为 ""）
     * @param level        命中层级：1=精确 / 2=包含 / 3=模糊 / 0=未命中
     * @param code         国标代码（词典收录则有，否则 null）
     */
    public record NormalizeResult(String standardTerm, String source, int level, String code) {
    }

    public NormalizeResult normalize(String type, String term) {
        String input = term == null ? "" : term.trim();
        if (input.isEmpty()) {
            return new NormalizeResult(term, "", 0, null);
        }

        // ES 召回 -> 判定（命中路径只需在候选集内比较）
        List<TermEntry> recalled = recall(type, input);
        if (!recalled.isEmpty()) {
            NormalizeResult hit = judge(recalled, input);
            if (hit != null) {
                log.debug("[归一] {} ES命中({}候选): {} -> {}", type, recalled.size(), input, hit.standardTerm());
                return hit;
            }
        }

        return new NormalizeResult(input, "", 0, null);
    }

    /**
     * ES 检索候选。**ES 不可用时直接抛出，不静默降级** —— 归一已改为以 ES 索引为唯一权威，
     * 静默返回空列表等于把「索引挂了」伪装成「词典没这个词」。
     *
     * <p>这里 catch {@code Exception} 而<b>不是</b> {@code IOException}：ES 客户端连不上时
     * 抛的是 {@code ElasticsearchException}（unchecked，内部包着 ExecutionException /
     * ConnectException），按声明类型只抓 IOException 会漏掉它，异常就绕到这里冒到兜底处理器
     * 变成 500「系统异常」—— 实测踩过。索引查不动（无论什么原因）在语义上都等于索引不可用。</p>
     */
    private List<TermEntry> recall(String type, String input) {
        try {
            return esTermIndexService.search(type, input, RECALL_SIZE);
        } catch (Exception e) {
            log.error("[归一] {} ES 检索失败，归一不可用：{}", type, e.getMessage());
            throw new TermIndexUnavailableException(
                    "术语索引暂时不可用，无法完成术语归一，请稍后重试或联系管理员", e);
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
                return new NormalizeResult(e.getStandardTerm(), e.getSource(), 1, e.getCode());
            }
            if (e.getAliases() != null && e.getAliases().contains(input)) {
                return new NormalizeResult(e.getStandardTerm(), e.getSource(), 1, e.getCode());
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
            return new NormalizeResult(bestContains.getStandardTerm(), bestContains.getSource(), 2,
                    bestContains.getCode());
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
            return new NormalizeResult(bestFuzzy.getStandardTerm(), bestFuzzy.getSource(), 3,
                    bestFuzzy.getCode());
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
