package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.TermIndexUnavailableException;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
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

    // ---- 归一命中层级（NormalizeResult.level）----
    // 抽成具名常量是因为下游要按层级分类计数（AiServiceImpl 的精确/包含/模糊三档统计），
    // 那里原来写的是裸字面量 1/2/3：改判定顺序时编译不报错、统计会静默错位。
    // 不新建枚举类 —— level 随 record 一起在 JSON 里流转，改成枚举会动契约字段表示。
    /** 未命中词典（standardTerm 回填原文） */
    public static final int LEVEL_NONE = 0;
    /** 一级：标准词或别名精确相等 */
    public static final int LEVEL_EXACT = 1;
    /** 二级：双向包含（"咽喉痛" 与 "咽痛"） */
    public static final int LEVEL_CONTAIN = 2;
    /** 三级：Dice 相似度达阈值 */
    public static final int LEVEL_FUZZY = 3;

    private final IEsTermIndexService esTermIndexService;

    @Value("${elasticsearch.score-threshold:0.8}")
    private double scoreThreshold;

    /**
     * 归一结果。
     *
     * @param standardTerm 命中的标准词（未命中时=原文）
     * @param source 术语来源（未命中为 ""）
     * @param level 命中层级：{@link #LEVEL_EXACT}=精确 / {@link #LEVEL_CONTAIN}=包含 /
     *               {@link #LEVEL_FUZZY}=模糊 / {@link #LEVEL_NONE}=未命中
     * @param code 国标代码（词典收录则有，否则 null）
     */
    public record NormalizeResult(String standardTerm, String source, int level, String code) {
    }

    /**
     * 把单个术语归一到标准词：ES 召回候选 → 三级判定（精确 / 双向包含 / Dice ≥ 阈值）。
     *
     * <p>没召回、或三级都不中，即判「未命中」（standardTerm 回填原文、level=0）；
     * 但 ES 本身不可用时<b>不是</b>未命中而是抛异常，由上层返回 503 —— 两者不可混为一谈。</p>
     *
     * @param type 实体类型 key（见 {@link EntityTypes}），决定查哪本词典
     * @param term 待归一的原文
     * @return 命中层级、标准词、来源与国标代码
     * @throws TermIndexUnavailableException ES 索引不可用
     */
    public NormalizeResult normalize(String type, String term) {
        return normalize(type, "", term);
    }

    /**
     * 组织级归一：ES 召回候选时限定「基础层 + 当前组织」。
     *
     * <p>请求路径传 {@code RequestUtils.currentOrgId()}，批任务传任务行上的组织快照
     * （worker 线程没有 RequestContext）。</p>
     *
     * @param type  实体类型 key
     * @param orgId 组织号；空串 = 只查基础层
     * @param term  待归一的原文
     */
    public NormalizeResult normalize(String type, String orgId, String term) {
        // 1. 归一原文（null 视作空串）
        String input = term == null ? "" : term.trim();
        if (input.isEmpty()) {
            return new NormalizeResult(term, "", LEVEL_NONE, null);
        }

        // ES 召回 -> 判定（命中路径只需在候选集内比较）
        List<TermEntry> recalled = recall(type, orgId, input);
        if (!recalled.isEmpty()) {
            NormalizeResult hit = judge(recalled, input);
            if (hit != null) {
                log.debug("[归一] {} ES命中({}候选): {} -> {}", type, recalled.size(), input, hit.standardTerm());
                return hit;
            }
        }

        // 2. 未召回或三级都不中 → 判未命中，standardTerm 回填原文
        return new NormalizeResult(input, "", LEVEL_NONE, null);
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
    private List<TermEntry> recall(String type, String orgId, String input) {
        // 1. 检索失败必须抛出：静默返回空列表等于把「索引挂了」伪装成「词典没这个词」
        try {
            return esTermIndexService.search(type, orgId == null ? "" : orgId, input, RECALL_SIZE);
        } catch (Exception e) {
            log.error("[归一] {} ES 检索失败，归一不可用：{}", type, e.getMessage());
            throw new TermIndexUnavailableException(
                    "术语索引暂时不可用，无法完成术语归一，请稍后重试或联系管理员", e);
        }
    }

    /**
     * 扫描一段文本里命中的<b>全部</b>标准词（§九 9.2 第 ③ 步）。
     *
     * <p>与 {@link #normalize} 的区别只在<b>二级·包含</b>这一层：{@code normalize} 多命中时
     * <b>取最短</b>（"胃痛" 优先于 "胃脘痛"），那是给「单个术语找标准词」用的；
     * 本方法要的是「这段文本里有哪几个已收录的标准词」，取最短会把
     * {@code "天麻10g，菊花10g"} 压成一条。故这里把二级判定换成 contains 谓词、
     * <b>收集全部命中</b>，不做「取最短」。</p>
     *
     * <p>用途：批量解析链路用它找出「抽取时被截断的残词」——
     * 归一失败的 content 若正好是某个命中词的<b>真子串</b>（如 "天" ⊂ "天麻"），
     * 说明抽取器把长词切短了，此时把完整词 append 回去。
     * 注意判据必须是「真子串」而不是「长度为 1」这类启发式。</p>
     *
     * @param type 实体类型 key（见 {@link EntityTypes}），决定查哪本词典
     * @param text 待扫描的文本（通常是处方或诊断整段）
     * @return 命中的标准词集合（不含空串与原文自身）；ES 不可用时抛
     *         {@link TermIndexUnavailableException}，与 {@link #normalize} 同口径
     */
    public Set<String> scan(String type, String text) {
        return scan(type, "", text);
    }

    /** 组织级扫描：限定「基础层 + 当前组织」。见 {@link #normalize(String, String, String)}。 */
    public Set<String> scan(String type, String orgId, String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String input = text.trim();
        // 1. 复用同一套召回（同样不静默降级：ES 挂了就该报 503，不是「没命中」）
        for (TermEntry e : recall(type, orgId, input)) {
            String std = e.getStandardTerm();
            if (std == null || std.isBlank() || std.equals(input)) {
                // 与原文完全相同的不算「被截断的残词」，跳过
                continue;
            }
            if (input.contains(std) || containsAnyAlias(e, input)) {
                out.add(std);
            }
            if (e.getAliases() != null) {
                for (String a : e.getAliases()) {
                    if (a != null && !a.isBlank() && !a.equals(input) && input.contains(a)) {
                        out.add(std);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 既有三级判定（改造前后逐字保持，勿改顺序与比较符）。
     *
     * @return 命中结果；三级都不中返回 {@code null}
     */
    private NormalizeResult judge(List<TermEntry> entries, String input) {
        // 一级·精确：标准词/别名完全相等
        // 1. 精确优先：命中即返回，不给后面的宽松规则机会
        for (TermEntry e : entries) {
            if (e.getStandardTerm().equals(input)) {
                return new NormalizeResult(e.getStandardTerm(), e.getSource(), LEVEL_EXACT, e.getCode());
            }
            if (e.getAliases() != null && e.getAliases().contains(input)) {
                return new NormalizeResult(e.getStandardTerm(), e.getSource(), 1, e.getCode());
            }
        }

        // 二级·包含（双向）：输入包含标准词/别名 或 标准词/别名包含输入，多命中取最短标准词
        // 2. 双向包含：多命中时取最短标准词（"胃痛" 优先于 "胃脘痛"）
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
            return new NormalizeResult(bestContains.getStandardTerm(), bestContains.getSource(), LEVEL_CONTAIN,
                    bestContains.getCode());
        }

        // 三级·模糊：字符Dice相似度 ≥ 阈值，取最高分
        // 3. 模糊：标准词与各别名都算一遍，取最高分
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
        // 4. 最高分仍不到阈值就算未命中，给 null
        if (bestFuzzy != null && bestScore >= scoreThreshold) {
            return new NormalizeResult(bestFuzzy.getStandardTerm(), bestFuzzy.getSource(), LEVEL_FUZZY,
                    bestFuzzy.getCode());
        }

        return null;
    }

    private boolean containsAnyAlias(TermEntry e, String input) {
        // 1. 没别名就不参与包含判定
        if (e.getAliases() == null) return false;
        // 2. 任一别名双向包含即算命中
        for (String a : e.getAliases()) {
            if (a.contains(input) || input.contains(a)) return true;
        }
        return false;
    }

    /** 字符集合Dice系数：2*|A∩B| / (|A|+|B|) */
    private double dice(String a, String b) {
        // 1. 任一为空直接 0（不除零）
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        // 2. 各自去重成字符集合
        Set<Character> sa = new HashSet<>();
        for (char c : a.toCharArray()) sa.add(c);
        Set<Character> sb = new HashSet<>();
        for (char c : b.toCharArray()) sb.add(c);
        // 3. 数交集大小
        int inter = 0;
        for (Character c : sa) {
            if (sb.contains(c)) inter++;
        }
        return 2.0 * inter / (sa.size() + sb.size());
    }
}
