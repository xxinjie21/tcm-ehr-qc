package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.TermIndexUnavailableException;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * level 是否落在「命中」区间（精确 ~ 模糊）。
     *
     * <p>{@code EntityNormalizer} 有 3 处重复写 {@code level >= LEVEL_EXACT && level <= LEVEL_FUZZY}
     * （其中 2 处取反判定「未命中」），收敛到此处一处，避免层级区间改动时漏改（审查报告 §2.11）。</p>
     */
    public static boolean isHit(int level) {
        return level >= LEVEL_EXACT && level <= LEVEL_FUZZY;
    }

    private final IEsTermIndexService esTermIndexService;

    /**
     * 预热召回缓存（批次12 · 12b）：把一批术语一次问完 ES。
     *
     * <p>12b 的问题是「逐术语往返」：同一次解析里同一个术语会被反复归一，每次都发一次 ES 往返。
     * 调用方在逐条归一**之前**调一次本方法，{@code searchBatch} 会用 {@code _msearch} 一次往返
     * 查完这批术语，并**把结果写进与单条 search 完全相同的缓存键** ——
     * 于是随后的逐条归一全部命中缓存，不再逐个往返。</p>
     *
     * <p>只预热、不改判定：命中与否仍由三级规则与 Dice 阈值决定。
     * 预取失败不影响正确性（逐条归一照常走 ES），所以这里只记 debug 日志。</p>
     */
    public void prefetch(String type, String orgId, List<String> raws) {
        if (raws == null || raws.isEmpty()) {
            return;
        }
        // 用与 normalize 逐字一致的 cleanTerm 规整后再预取：否则缓存键是「带标点的原文」，
        // 而 normalize 查的是「规整后的术语」，两者对不上 —— 预取白做，逐条又各自回源 ES。
        List<String> cleaned = new ArrayList<>(raws.size());
        for (String raw : raws) {
            cleaned.add(cleanTerm(raw));
        }
        try {
            esTermIndexService.searchBatch(type, orgId == null ? "" : orgId, cleaned, RECALL_SIZE);
        } catch (Exception e) {
            log.debug("[归一] 预取失败（不影响逐条归一）：{}", e.getMessage());
        }
    }

    @Value("${elasticsearch.score-threshold:0.8}")
    private double scoreThreshold;

    /**
     * 归一结果。
     *
     * @param standardTerm 命中的标准词（未命中时=原文）
     * @param level 命中层级：{@link #LEVEL_EXACT}=精确 / {@link #LEVEL_CONTAIN}=包含 /
     *               {@link #LEVEL_FUZZY}=模糊 / {@link #LEVEL_NONE}=未命中
     */
    public record NormalizeResult(String standardTerm, int level) {
    }

    /**
     * 把单个术语归一到标准词：ES 召回候选 → 三级判定（精确 / 双向包含 / Dice ≥ 阈值）。
     *
     * <p>没召回、或三级都不中，即判「未命中」（standardTerm 回填原文、level=0）；
     * 但 ES 本身不可用时<b>不是</b>未命中而是抛异常，由上层返回 503 —— 两者不可混为一谈。</p>
     *
     * @param type 实体类型 key（见 {@link EntityTypes}），决定查哪本词典
     * @param term 待归一的原文
     * @return 命中层级、标准词
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
        // 1. 归一原文：trim + 标点规整（合并重复标点、去首尾标点）。
        //    「咽痛。。」/「咽痛。」这类带游离或重复标点的候选，规整成「咽痛」才能精确命中；
        //    规整口径与导出/清洗共用 TextUtil，只影响匹配与回填，不改库里原文。
        String input = cleanTerm(term);
        if (input.isEmpty()) {
            return new NormalizeResult(term, LEVEL_NONE);
        }

        // 2. ES 召回 -> 判定（命中路径只需在候选集内比较）
        List<TermEntry> recalled = recall(type, orgId, input);
        if (!recalled.isEmpty()) {
            NormalizeResult hit = judge(recalled, input);
            if (hit != null) {
                log.debug("[归一] {} ES命中({}候选): {} -> {}", type, recalled.size(), input, hit.standardTerm());
                return hit;
            }
        }

        // 3. 未召回或三级都不中 → 判未命中，standardTerm 回填规整后的原文
        return new NormalizeResult(input, LEVEL_NONE);
    }

    /**
     * 术语输入规整：trim → 合并重复标点 / 半角转全角 / 去标点两侧空白 → 去首尾标点。
     *
     * <p>与 {@code GovernanceServiceImpl} 导出标准化共用 {@link TextUtil} 的同一套标点口径，
     * 保证「归一怎么对，导出的文本就怎么整」。纯标点（如「。。」）归一为空串，
     * 由调用方按未命中处理。</p>
     */
    private static String cleanTerm(String term) {
        if (term == null) {
            return "";
        }
        return TextUtil.stripPunctuationBoundary(TextUtil.normalizePunctuation(term.trim()));
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
        // 与 normalize 同口径先规整标点：扫描的整段文本（处方/诊断）常常句尾带「。」或重复标点，
        // 规整后再比子串，命中口径与逐术语归一一致。
        String input = cleanTerm(text);
        if (input.isEmpty()) {
            return out;
        }
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
                return new NormalizeResult(e.getStandardTerm(), LEVEL_EXACT);
            }
            if (e.getAliases() != null && e.getAliases().contains(input)) {
                return new NormalizeResult(e.getStandardTerm(), 1);
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
            return new NormalizeResult(bestContains.getStandardTerm(), LEVEL_CONTAIN);
        }

        // 三级·模糊：字符Dice相似度 ≥ 阈值，取最高分
        // 3. 模糊：标准词与各别名都算一遍，取最高分。
        //    输入的去重字符集在这里算**一次**、全部候选复用（P1-16 收口）：原先每个 pair 都
        //    重算一遍 |A|，而 a 恒是同一个 input —— 候选一多就是「同一份输入被重复去重上百次」。
        //    只在走到模糊级时才算（一二级命中会提前 return），不为没走到的分支白付成本。
        char[] inputDistinct = distinctChars(input);
        TermEntry bestFuzzy = null;
        double bestScore = 0;
        for (TermEntry e : entries) {
            double s = dicePrepared(inputDistinct, e.getStandardTerm());
            if (e.getAliases() != null) {
                for (String a : e.getAliases()) {
                    s = Math.max(s, dicePrepared(inputDistinct, a));
                }
            }
            if (s > bestScore) {
                bestScore = s;
                bestFuzzy = e;
            }
        }
        // 4. 最高分仍不到阈值就算未命中，给 null
        if (bestFuzzy != null && bestScore >= scoreThreshold) {
            return new NormalizeResult(bestFuzzy.getStandardTerm(), LEVEL_FUZZY);
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

    /**
     * 字符集合 Dice 系数：2*|A∩B| / (|A|+|B|)，A/B 为两串的<b>去重字符集</b>。
     *
     * <p><b>P1-16 / S3：口径一字未改，只把「临时对象」去掉。</b>
     * 原实现每次 {@code new HashSet} 两个、再把每个字符装箱塞进去 —— 单次归一要算约 75 次
     * Dice（约 50 个候选 × 标准词 + 别名），4 万病历 × 每病历约 30 个实体就是<b>上亿次</b>
     * 临时集合与 Character 装箱。单条不痛，批量全压在 GC 上。</p>
     *
     * <p><b>为什么不用位图</b>（方案里写的是字符集位图）。先说清楚：这不是「位图在任何输入下都更慢」。
     * 位图要成立，必须有一个<b>共享的字符坐标</b>，而代价全在这一步：</p>
     * <ul>
     *   <li>覆盖 BMP 的位图 = 65536 位 = <b>8KB/串</b>；</li>
     *   <li>按词典实际字种压成紧凑字母表（约 6000 字 → 94 个 long = 752B/串），每次比对
     *       仍要 94 次 AND+popcount；而中文术语只有 2~6 个字，逐字符比是 3L²（L=3 时 27 次
     *       字符比较）—— <b>L² &lt; 字母表字数</b> 时位图反而不划算；</li>
     *   <li>而且要让位图真的「临时对象归零」，它必须<b>常驻缓存</b>：全量 30k 词条约 68MB，
     *       只缓存 ES 召回缓存涉及的那批串也要 ~9MB，还得再挂一条版本失效通道；
     *       不缓存、每次现建 {@code new long[94]} 的话，分配量比原来的 HashSet 还大 ——
     *       正好把要修的东西修反。</li>
     * </ul>
     * <p>结论按<b>实测数据形状</b>取：术语是 2~6 个汉字，位图在 CPU 上不占优、在内存上明显更差，
     * 还多一条会静默改变结果的失效面，故改成<b>零分配的原地比较</b>。
     * 唯一的代价是输入很长时（抽取器给出整段 span）逐字符比会退化 —— 所以输入侧的去重字符集
     * 由 {@link #judge} 算一次往下传，不再按 pair 重算。</p>
     *
     * <p>包级可见是为了让「与改造前逐条一致」这条性质能被同步测试直接钉住。</p>
     */
    static double dice(String a, String b) {
        // 1. 任一为空直接 0（不除零）
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        return dicePrepared(distinctChars(a), b);
    }

    /**
     * 预置输入的 Dice：{@code inputDistinct} 已是去重后的输入字符集，其长度即 |A|。
     *
     * <p>拆出来是为了让「一次归一里对全部候选复用同一份输入字符集」成为可能 ——
     * {@link #judge} 每轮只算一次，而不是每个候选重算一遍。口径与 {@link #dice} 完全相同。</p>
     */
    private static double dicePrepared(char[] inputDistinct, String b) {
        // 1. 输入去重后为空、或候选串为空 → 0（不除零）
        if (inputDistinct.length == 0 || b == null || b.isEmpty()) return 0;
        // 2. |B|：b 里「首次出现」的字符个数（indexOf 从头找，等于首次出现位置）
        int nb = 0;
        for (int i = 0; i < b.length(); i++) {
            if (b.indexOf(b.charAt(i)) == i) nb++;
        }
        // 3. |A∩B|：输入的去重字符里，能在 b 里找到的那些
        int inter = 0;
        for (char c : inputDistinct) {
            if (b.indexOf(c) >= 0) inter++;
        }
        return 2.0 * inter / (inputDistinct.length + nb);
    }

    /**
     * 取一串里的去重字符（保持首次出现的顺序）。
     *
     * <p>O(L²) 但 L 是个位数，且调用方一轮只调一次 —— 换来的是后面每对比较都不必再碰输入侧。</p>
     */
    private static char[] distinctChars(String s) {
        char[] buf = new char[s.length()];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean dup = false;
            for (int j = 0; j < n; j++) {
                if (buf[j] == c) {
                    dup = true;
                    break;
                }
            }
            if (!dup) buf[n++] = c;
        }
        return Arrays.copyOf(buf, n);
    }
}
