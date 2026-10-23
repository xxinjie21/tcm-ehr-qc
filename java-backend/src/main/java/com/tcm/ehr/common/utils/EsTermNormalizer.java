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
 * （DictionaryStore），本意是让「ES 漏召回」不至于漏判。但代价是<b>ES 的角色变得不可判定</b>：
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
     * <p>EntityNormalizer 有 3 处重复写 {@code level >= LEVEL_EXACT && level <= LEVEL_FUZZY}
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
     * 调用方在逐条归一**之前**调一次本方法，searchBatch 会用 _msearch 一次往返
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
     * 包含级允许的「输入比标准词更长」的码点差额（R3，默认 1）。
     *
     * <p>取 1 而非 2：差 2 会放进 {@code 知饥易饿→知饥不食}、{@code 带下量多→带下}
     * 这类语义不等价的包含（实测清洗链路的负优化来源）。差 1 只放行「加一字修饰」
     * （{@code 炒苍术→苍术}、{@code 岷当归→当归}），基本是炮制 / 品名前缀。
     * 差 2 的正确映射（若有）一律走别名登记，不放宽阈值 —— 语义等价由词典决定，不由代码猜。</p>
     */
    @Value("${elasticsearch.contain-delta:1}")
    private int containDelta;

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
     * <p>与 GovernanceServiceImpl 导出标准化共用 {@link TextUtil} 的同一套标点口径，
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
     * <p>这里 catch Exception 而<b>不是</b> IOException：ES 客户端连不上时
     * 抛的是 ElasticsearchException（unchecked，内部包着 ExecutionException /
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

    // ---- 组合短语拆解（2026-10-19，精度优先口径下的「组合拆解」）----
    // 抽取器会把两个独立维度写成一个 span：脉象「左关偏浮」= 脉位「左关」+ 脉质「浮」，
    // 舌象「舌淡苔薄」= 舌质「舌淡」+ 苔质「薄苔」。三级判定要求整串命中，因差额 > containDelta
    // 一律未命中；而把它硬映射到其中一半（旧版宽松子串）会丢另一半语义。
    // 这里的口径相反：**只有整串能被词典项完整覆盖时才拆**，拆出的每一段都是精确命中；
    // 有任何无落点的字（含否定/关系字）就整体放弃，保持未命中。
    /** 可忽略的程度修饰字（偏浮 / 略沉 / 稍弱 / 较弦 / 仍略沉 / 尚应指） */
    private static final String PULSE_MODIFIERS = "偏略稍较微甚尤仍尚";
    /** 语义反转 / 减弱字：仅相差一个即认为模糊级不可信。 */
    private static final String POLARITY_CHARS = "不无未否欠除减";
    /** 脉象组合里出现即放弃拆解的语义字：否定与减退（欠/不/无/未/乏/减/除/兼） */
    private static final String PULSE_BLOCK = "不无未否欠除减乏兼";
    /** 脉象组合里可静默消费的方位 / 深度 / 关系字（「右寸后及关前」「中取」「左略涩」「弦缓带滑」） */
    private static final String PULSE_RELATION = "脉前后中上及取双两左右下带";
    /**
     * 脉象里可整段跳过的诊法短语（不构成独立标准词）：
     * 「应指」是脉体描述，「不受按 / 不受 / 按之 / 按」是切脉手法描述（「沉取不受按」= 脉沉）。
     * 必须先于 {@link #PULSE_BLOCK} 判定 —— 「不受按」里的「不」不是对脉象的否定。
     */
    private static final String[] PULSE_SKIP_PHRASES = {"不受按", "不受", "按之", "应指", "按"};
    /** 脉质单字表（用于把「浮」补成标准词「脉浮」；与 pulses.json 的脉质条目同源） */
    private static final String PULSE_QUALITY = "浮沉迟数虚实滑涩弦紧细濡微弱洪大促结代疾动牢革散伏长短缓芤旺软小";
    /** 可带方位前缀的脉位标准词 */
    private static final String[] PULSE_POSITIONS = {"左寸", "左关", "左尺", "右寸", "右关", "右尺"};
    /**
     * 舌象拆解时允许跳过的程度 / 方位修饰字：
     * 程度（偏略稍较微甚尤）、舌质标记（质）、部位与位置（边有部根尖内外前后中底满布体面）、
     * 副词（仍尚）。实测（本库 58305 条未归一舌象）：只扩这一串就从 20640 条里救回
     * 2117 条（≈ +2.8pp），主力是「边有齿痕 / 边有齿印」→ {@code 齿痕舌}（1600+ 条）。
     * <p>注意：<b>不做</b>语义字的跳过（腻 / 厚 / 红 / 点 / 黄 / 白 …）—— 那些是词条的组成部分，
     * 跳了就变成无落点的"部分拆解"。</p>
     */
    private static final String TONGUE_SKIP = "偏略稍较微甚尤质边有部根尖内外前后中底满布仍尚体面";
    /**
     * 症状的「语境前后缀」白名单（按长度降序）：只收时间 / 频次 / 程度这类
     * 不改变症状本体的修饰语。位置词（腰部 / 下肢 / 胃脘）刻意不收 ——
     * 「下肢水肿」和「水肿」是同一症状，「腰部冷痛」与「腰痛」却不是。
     */
    private static final String[] SYMPTOM_PREFIX = {
            "活动后", "运动后", "劳累后", "晨起", "晨间", "清晨", "夜间", "夜里", "夜寐",
            "睡前", "醒后", "夜醒", "饭后", "食后", "进食后", "便前", "便后", "经前", "经期", "经后",
            "平素", "平时", "近日", "近期", "偶有", "时有", "反复", "持续", "自觉",
            "稍有", "稍感", "略有", "轻微", "明显",
            // 触发 / 频次语境（「连声咳嗽」「刷牙干呕」「寐时流涎」「蹲起时头晕」「天热汗出」）：
            // 只收**不改变症状本体**的语境词，解剖部位（咽喉 / 下肢 / 肛周）刻意不收 ——
            // 那是「该不该合并部位」的临床口径问题，得由业务侧定，不由归一引擎暗中决定。
            "连声", "刷牙", "寐时", "蹲起时", "挠抓后", "天热", "有时",
            "夏季", "冬季", "春季", "秋季", "有少量", "少量"};
    /** 症状的程度 / 频次 / 状态后缀（「瘙痒明显」「汗出彻全身」「汗出一般」「咽痒作咳」） */
    private static final String[] SYMPTOM_SUFFIX = {
            "明显", "加重", "较多", "较少", "偶有", "反复", "时作", "频作", "不已", "不止", "难忍",
            "彻全身", "可以下达", "可下达", "一般", "黄染", "湿衣", "作咳"};
    /** 症状拆解的语义阻断字：否定（不无未否）与已愈 / 缓解（欠除减）一律不剥 */
    private static final String SYMPTOM_BLOCK = "不无未否欠除减";

    /**
     * 证候拆解允许静默消费的连接 / 程度字：「兼 / 及 / 为 / 、」把两个证候拼在一起，
     * 「主」是「XX为主」的残留（「为主」整体在 {@link #PATTERN_SUFFIX} 里剥），
     * 程度字与脉象 / 舌象同表。
     */
    private static final String PATTERN_SKIP = "兼夹及为和、，,主略偏稍较甚尤微";
    /** 证候的程度后缀（按长度降序）：「厥阴为主」= 厥阴；「太阴兼少阳为主」先剥成组合再拆 */
    private static final String[] PATTERN_SUFFIX = {
            "为主兼证", "为主兼", "为主症", "为主证", "偏盛", "为主"};
    /** 证候的语义阻断字：与症状同表（否定 / 已愈一律不动） */
    private static final String PATTERN_BLOCK = "不无未否欠除减";
    /** 拆解兜底探测的最大词长（「阳明少阳风湿郁热」= 阳明 / 少阳 / 风湿 / 郁热，最长 2） */
    private static final int PROBE_MAX_LEN = 6;

    /**
     * 把「组合短语」拆成若干可独立归一的标准词。
     *
     * <p>只在整串可被词典项<b>完整覆盖</b>时返回；任何无落点的字都视为不可拆，
     * 返回空列表（调用方按原样判未命中）。当前对脉象、舌象、症状、证候四类开放 ——
     * 它们的组合都可枚举（脉位+脉质 / 舌质+苔质 / 症状并列 / 证候相兼），
     * 而「只有整串被完整覆盖才拆」这条闸门保证了不会滑向旧版「宽松子串」。</p>
     *
     * @param type  实体类型 key（pulse / tongue / symptom / pattern）
     * @param orgId 组织号；空串 = 只查基础层
     * @param term  未命中的原文
     * @return 拆出的标准词（按原文顺序、已去重）；不可拆时为空列表
     */
    public List<NormalizeResult> decompose(String type, String orgId, String term) {
        String input = cleanTerm(term);
        if (input.codePointCount(0, input.length()) < 2) {
            return List.of();
        }
        if ("pulse".equals(type)) {
            return decomposePulse(orgId, input);
        }
        if ("tongue".equals(type)) {
            return decomposeTongue(orgId, input);
        }
        if ("symptom".equals(type)) {
            return decomposeSymptom(orgId, input);
        }
        if ("pattern".equals(type)) {
            return decomposePattern(orgId, input);
        }
        return List.of();
    }

    /** 脉象：脉位 + 脉质，忽略程度修饰字与诊法短语，遇否定 / 减退字整体放弃。 */
    private List<NormalizeResult> decomposePulse(String orgId, String input) {
        // 整串召回只作参考：它按 IK 分词，常召不回拆解所需的部件（「脉偏细」召不回「脉细」）。
        // 真正的判定走逐段定点查词（termNormalizer.normalize 对单术语必然精确召回），
        // 缓存复用 RECALL_CACHE 与本地 map，只在未命中的实体上发生。
        Set<String> known = new java.util.HashSet<>();
        for (TermEntry e : recall("pulse", orgId, input)) {
            if (e.getStandardTerm() != null && !e.getStandardTerm().isBlank()) {
                known.add(e.getStandardTerm());
            }
        }
        java.util.Map<String, Boolean> knownCache = new java.util.HashMap<>();
        List<NormalizeResult> out = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            // 1. 诊法短语整段跳过（「不受按」里的「不」不是否定，必须先于阻断字判定）
            boolean skippedPhrase = false;
            for (String ph : PULSE_SKIP_PHRASES) {
                if (input.startsWith(ph, i)) {
                    i += ph.length();
                    skippedPhrase = true;
                    break;
                }
            }
            if (skippedPhrase) {
                continue;
            }
            // 2. 脉位：左/右 + 寸关尺（双/两 开头可消费但不产出，因为「关」不是标准词）
            int posLen = pulsePositionLength(input, i);
            if (posLen > 0) {
                String pos = input.substring(i, i + posLen);
                if (Arrays.asList(PULSE_POSITIONS).contains(pos)) {
                    addUnique(out, new NormalizeResult(pos, LEVEL_EXACT));
                }
                i += posLen;
                continue;
            }
            // 2.1 语义阻断字：否定 / 减退（「脉欠流利」「脉不滑」）—— 整体放弃
            if (PULSE_BLOCK.indexOf(c) >= 0) {
                return List.of();
            }
            // 2.2 「脉 / 双 / 两 / 左 / 右」与方位 / 深度 / 程度字只是连接成分，单独不产出实体
            if (PULSE_RELATION.indexOf(c) >= 0 || PULSE_MODIFIERS.indexOf(c) >= 0) {
                i++;
                continue;
            }
            // 3. 脉质：取最长连续脉质串里能被「脉+串」标准词覆盖的最长前缀
            int j = i;
            while (j < input.length() && PULSE_QUALITY.indexOf(input.charAt(j)) >= 0) {
                j++;
            }
            if (j > i) {
                String hit = null;
                for (int k = j; k > i; k--) {
                    String std = "脉" + input.substring(i, k);
                    if (isKnown("pulse", orgId, std, known, knownCache)) {
                        hit = std;
                        i = k;
                        break;
                    }
                }
                if (hit == null) {
                    return List.of();
                }
                addUnique(out, new NormalizeResult(hit, LEVEL_EXACT));
                continue;
            }
            // 4. 无落点的字 → 不可拆
            return List.of();
        }
        return out;
    }

    /** std 是否为本组织该类型的标准词：先看整串召回结果，再定点查一次（结果本地缓存）。 */
    private boolean isKnown(String type, String orgId, String std, Set<String> known,
                            java.util.Map<String, Boolean> cache) {
        Boolean c = cache.get(std);
        if (c != null) {
            return c;
        }
        boolean ok = known.contains(std)
                || normalize(type, orgId, std).level() == LEVEL_EXACT;
        cache.put(std, ok);
        return ok;
    }

    /** 脉位匹配长度：优先「左/右+寸关尺」，其次「双/两+寸关尺」，最后裸「寸关尺」。 */
    private static int pulsePositionLength(String s, int at) {
        char c = s.charAt(at);
        if (at + 1 < s.length() && (c == '左' || c == '右') && isPulsePositionChar(s.charAt(at + 1))) {
            return 2;
        }
        if (at + 1 < s.length() && (c == '双' || c == '两') && isPulsePositionChar(s.charAt(at + 1))) {
            return 2;
        }
        return isPulsePositionChar(c) ? 1 : 0;
    }

    private static boolean isPulsePositionChar(char c) {
        return c == '寸' || c == '关' || c == '尺';
    }

    /** 舌象：整串由 ≥2 个词典项覆盖（允许略过程度修饰字），按原文顺序返回标准词。 */
    private List<NormalizeResult> decomposeTongue(String orgId, String input) {
        // surface（标准词或别名）→ 标准词；单字词条不参与拆解（会把「红」拆成独立实体）
        java.util.Map<String, String> surface = surfaces(recall("tongue", orgId, input));
        List<String> seg = cover(input, 0, surface, new java.util.HashMap<>(), TONGUE_SKIP);
        if (seg == null) {
            return List.of();
        }
        // 产出：跳过修饰字（空串标记）不算实体；至少要有一个词条落点
        List<NormalizeResult> out = new ArrayList<>(seg.size());
        boolean skipped = false;
        for (String std : seg) {
            if (std.isEmpty()) {
                skipped = true;
                continue;
            }
            addUnique(out, new NormalizeResult(std, LEVEL_EXACT));
        }
        // 「舌淡苔薄」这类整串由 ≥2 个词条覆盖才值得拆；
        // 「尖红较甚」只有 1 个词条、其余全是修饰字，也可安全归到「尖红」。
        // 单个词条且没有任何修饰字时说明整串本可精确命中，走到这里必然有残字，放弃。
        if (out.isEmpty() || (out.size() < 2 && !skipped)) {
            return List.of();
        }
        return out;
    }

    /**
     * 症状：①整串由 ≥2 个词条覆盖（「腹胀便溏」→ 腹胀 + 便溏）；
     * ②剥掉白名单语境前后缀后整词精确命中（「晨起口干」→ 口干）。
     * 含否定 / 已愈字的一律不剥（「无明显口干」「口干已除」）。
     */
    private List<NormalizeResult> decomposeSymptom(String orgId, String input) {
        for (int i = 0; i < input.length(); i++) {
            if (SYMPTOM_BLOCK.indexOf(input.charAt(i)) >= 0) {
                return List.of();
            }
        }
        List<TermEntry> entries = recall("symptom", orgId, input);
        // ① 组合：整串覆盖
        java.util.Map<String, String> surface = surfaces(entries);
        List<String> seg = cover(input, 0, surface, new java.util.HashMap<>(), "");
        if (seg != null) {
            List<NormalizeResult> out = new ArrayList<>(seg.size());
            for (String std : seg) {
                addUnique(out, new NormalizeResult(std, LEVEL_EXACT));
            }
            if (out.size() >= 2) {
                return out;
            }
        }
        // ② 语境前后缀：先试「前缀+后缀」，再单独试前缀 / 后缀
        String body = null;
        String afterPrefix = stripPrefix(input);
        if (afterPrefix != null) {
            String both = stripSuffix(afterPrefix);
            body = both != null ? both : afterPrefix;
        }
        if (body == null) {
            body = stripSuffix(input);
        }
        if (body == null) {
            return List.of();
        }
        String std = exactTerm(entries, body);
        if (std == null) {
            // 整串召回同样可能漏掉剥完修饰后的部件（「晨起口干」召不回「口干」），定点补一次
            NormalizeResult r = normalize("symptom", orgId, body);
            if (r.level() == LEVEL_EXACT) {
                std = r.standardTerm();
            }
        }
        return std == null ? List.of() : List.of(new NormalizeResult(std, LEVEL_EXACT));
    }

    /**
     * 证候：①整串由 ≥2 个词条覆盖（「太阴阳明风湿郁热」→ 太阴 + 阳明 + 风湿 + 郁热，
     * 「兼 / 及 / 为主」等连接字静默消费）；②剥掉 {@link #PATTERN_SUFFIX} 后整词精确命中
     * （「厥阴为主」→ 厥阴）。含否定 / 已愈字的一律不动。
     *
     * <p>与症状同一条闸门：<b>整串被完整覆盖才拆</b>，拆出的每一段都是精确命中。
     * 证候的组合形式（相兼 / 合病）在病历里是主流写法 —— 实测 48999 条证候里
     * 23490 条未命中，其中 2959 条可拆（「阳明少阳风湿郁热」这类）、285 条可剥后缀。</p>
     */
    private List<NormalizeResult> decomposePattern(String orgId, String input) {
        for (int i = 0; i < input.length(); i++) {
            if (PATTERN_BLOCK.indexOf(input.charAt(i)) >= 0) {
                return List.of();
            }
        }
        List<TermEntry> entries = recall("pattern", orgId, input);
        java.util.Map<String, String> surface = surfaces(entries);
        List<String> seg = cover(input, 0, surface, new java.util.HashMap<>(), PATTERN_SKIP);
        if (seg == null) {
            // 整串召回按 IK 分词，可能漏掉其中的部件 ⇒ 逐段定点补一次再试
            surface = expandByProbe("pattern", orgId, input, surface);
            seg = cover(input, 0, surface, new java.util.HashMap<>(), PATTERN_SKIP);
        }
        if (seg != null) {
            List<NormalizeResult> out = new ArrayList<>(seg.size());
            for (String std : seg) {
                if (std.isEmpty()) {
                    continue;
                }
                addUnique(out, new NormalizeResult(std, LEVEL_EXACT));
            }
            if (out.size() >= 2) {
                return out;
            }
        }
        for (String suf : PATTERN_SUFFIX) {
            if (input.endsWith(suf) && input.length() > suf.length()) {
                String body = input.substring(0, input.length() - suf.length());
                String std = exactTerm(entries, body);
                if (std == null) {
                    NormalizeResult r = normalize("pattern", orgId, body);
                    if (r.level() == LEVEL_EXACT) {
                        std = r.standardTerm();
                    }
                }
                if (std != null) {
                    return List.of(new NormalizeResult(std, LEVEL_EXACT));
                }
            }
        }
        return List.of();
    }

    /** 候选词条 → {@code surface(标准词/别名) → 标准词}；单字词条一律不收（会把「红」拆成独立实体）。 */
    private static java.util.Map<String, String> surfaces(List<TermEntry> entries) {
        java.util.Map<String, String> surface = new java.util.HashMap<>();
        for (TermEntry e : entries) {
            String std = e.getStandardTerm();
            if (std == null || std.isBlank() || std.codePointCount(0, std.length()) < 2) {
                continue;
            }
            surface.putIfAbsent(std, std);
            if (e.getAliases() != null) {
                for (String a : e.getAliases()) {
                    if (a != null && a.codePointCount(0, a.length()) >= 2) {
                        surface.putIfAbsent(a, std);
                    }
                }
            }
        }
        return surface;
    }

    /**
     * 把整串里每个 ≤{@link #PROBE_MAX_LEN} 字的子串定点查一次词典，命中即补进 surface。
     *
     * <p>整串召回走 IK 分词（{@code 阳明少阳风湿郁热} 未必召回「阳明」），而拆解要求每个部件都在
     * surface 里。这里按位置逐段定点查（结果走 RECALL_CACHE），只在整串召回失败后发生。</p>
     */
    private java.util.Map<String, String> expandByProbe(String type, String orgId, String input,
                                                       java.util.Map<String, String> surface) {
        int n = input.length();
        for (int i = 0; i < n; i++) {
            for (int end = Math.min(n, i + PROBE_MAX_LEN); end > i + 1; end--) {
                String sub = input.substring(i, end);
                if (surface.containsKey(sub)) {
                    continue;
                }
                NormalizeResult r = normalize(type, orgId, sub);
                if (r.level() == LEVEL_EXACT && r.standardTerm() != null) {
                    surface.put(sub, r.standardTerm());
                }
            }
        }
        return surface;
    }

    /** 剥掉一个白名单前缀；没有可剥的返回 null。 */
    private static String stripPrefix(String s) {
        for (String p : SYMPTOM_PREFIX) {
            if (s.startsWith(p) && s.length() > p.length()) {
                return s.substring(p.length());
            }
        }
        return null;
    }

    /** 剥掉一个白名单后缀；没有可剥的返回 null。 */
    private static String stripSuffix(String s) {
        if (s == null) {
            return null;
        }
        for (String x : SYMPTOM_SUFFIX) {
            if (s.endsWith(x) && s.length() > x.length()) {
                return s.substring(0, s.length() - x.length());
            }
        }
        return null;
    }

    /** 候选里是否有标准词或别名恰好等于 body。 */
    private static String exactTerm(List<TermEntry> entries, String body) {
        for (TermEntry e : entries) {
            String std = e.getStandardTerm();
            if (std != null && std.equals(body)) {
                return std;
            }
        }
        for (TermEntry e : entries) {
            String std = e.getStandardTerm();
            if (std != null && e.getAliases() != null && e.getAliases().contains(body)) {
                return std;
            }
        }
        return null;
    }

    /** 全字覆盖切分（记忆化）：返回标准词序列，不可覆盖返回 null。 */
    private static List<String> cover(String s, int pos, java.util.Map<String, String> surface,
                                      java.util.Map<Integer, List<String>> memo, String skipChars) {
        if (pos >= s.length()) {
            return List.of();
        }
        List<String> cached = memo.get(pos);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        List<String> best = null;
        // 1. 长度从长到短，先吃长词条（「薄白苔」优先于「薄苔」+ 残字）
        for (int end = s.length(); end > pos; end--) {
            String std = surface.get(s.substring(pos, end));
            if (std == null) {
                continue;
            }
            List<String> rest = cover(s, end, surface, memo, skipChars);
            if (rest == null) {
                continue;
            }
            List<String> cand = new ArrayList<>(rest.size() + 1);
            cand.add(std);
            cand.addAll(rest);
            if (best == null || cand.size() < best.size()) {
                best = cand;
            }
        }
        // 2. 程度修饰字可跳过（不计入覆盖，用空串标记，调用方剔除）
        if (skipChars.indexOf(s.charAt(pos)) >= 0) {
            List<String> rest = cover(s, pos + 1, surface, memo, skipChars);
            if (rest != null) {
                List<String> cand = new ArrayList<>(rest.size() + 1);
                cand.add("");
                cand.addAll(rest);
                if (best == null || cand.size() < best.size()) {
                    best = cand;
                }
            }
        }
        memo.put(pos, best == null ? List.of() : best);
        return best;
    }

    private static void addUnique(List<NormalizeResult> out, NormalizeResult r) {
        for (NormalizeResult x : out) {
            if (x.standardTerm().equals(r.standardTerm())) {
                return;
            }
        }
        out.add(r);
    }

    /**
     * 扫描一段文本里命中的<b>全部</b>标准词（§九 9.2 第 ③ 步）。
     *
     * <p>与 {@link #normalize} 的区别只在<b>二级·包含</b>这一层：normalize 多命中时
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
     * 三级判定（精确 / 包含 / 模糊），2026-10-18 起加护栏 R1–R4 + R6 + R7。
     *
     * <p>规则集（依据见 {@code docs/术语归一与导出准确率提升计划.md} 节点 C，反例均来自全流程实测）：</p>
     * <ul>
     *   <li><b>R1</b> 输入码点 &lt; 2 只允许精确命中（{@code 有→有汗}、{@code 血→血分证}）。</li>
     *   <li><b>R2</b> 禁止 {@code standardTerm.contains(input)}（标准词方向的子串不认，
     *       {@code 脚气→干脚气}）；短简称走别名登记。</li>
     *   <li><b>R3</b> 允许 {@code input.contains(standardTerm/alias)}，但差额 ≤ {@link #containDelta}（默认 1）。</li>
     *   <li><b>R4</b> 禁止 {@code alias.contains(input)}（别名方向的子串不认，
     *       {@code 治疗→食疗}、{@code 反复发作→流清涕}）。</li>
     *   <li><b>R6</b> 一级判定分两轮：先跨全部候选找 {@code std==input}（取最短），再找
     *       {@code alias==input}（取最短）—— 否则更早召回的别名会抢走本该由更短标准词精确命中的输入
     *       （{@code 心悸→心悸手抖}、{@code 知饥→知饥不食}）。</li>
     *   <li><b>R7</b> 模糊级也否决「子串关系」候选（{@code 干脚气 ⊃ 脚气}、{@code 广藿香 ⊃ 藿香}
     *       的 Dice 恰好 0.8，只加 R2/R4 挡不住）；子串等价一律由别名表决定（C3）。</li>
     *   <li><b>R8</b> 单字别名只参与精确命中与召回，不参与包含（{@code 质软→脉软(别名 软)}、
     *       {@code 吐痰→呕吐(别名 吐)}、{@code 湿疹→斑疹(别名 疹)} 都是 1 字别名被差额阈值放进来的）。</li>
     * </ul>
     * <p>R5（否定语境不归一）由调用方用 {@link TermGuard} 拦截，不在本方法内。</p>
     *
     * @return 命中结果；都不中返回 {@code null}
     */
    private NormalizeResult judge(List<TermEntry> entries, String input) {
        // 1. 一级 A：标准词精确（跨全部候选，多条取最短标准词）
        //    ⚠️ 分两轮而不是「逐条目先 std 再 alias」：后者会让更早召回的**别名**抢走
        //    本该由更短标准词精确命中的输入（实测 心悸 → 心悸手抖、知饥 → 知饥不食）。
        TermEntry best = null;
        for (TermEntry e : entries) {
            if (e.getStandardTerm() != null && e.getStandardTerm().equals(input) && shorter(e, best)) {
                best = e;
            }
        }
        if (best != null) {
            return new NormalizeResult(best.getStandardTerm(), LEVEL_EXACT);
        }
        // 2. 一级 B：别名精确（同样分轮、同取最短标准词）
        for (TermEntry e : entries) {
            if (e.getStandardTerm() != null && e.getAliases() != null
                    && e.getAliases().contains(input) && shorter(e, best)) {
                best = e;
            }
        }
        if (best != null) {
            return new NormalizeResult(best.getStandardTerm(), LEVEL_EXACT);
        }
        // 3. R1：单字只允许精确命中（上面两轮已覆盖），不再参与包含/模糊
        int inputLen = input.codePointCount(0, input.length());
        if (inputLen < 2) {
            return null;
        }
        // 4. 二级·包含（R2 + R3 + R4）：只认「输入包含标准词/别名」这个方向，且差额受限于 containDelta
        TermEntry bestContains = null;
        for (TermEntry e : entries) {
            if (!containsAsPartOfInput(e, input, inputLen)) {
                continue;
            }
            if (shorter(e, bestContains)) {
                bestContains = e;
            }
        }
        if (bestContains != null) {
            return new NormalizeResult(bestContains.getStandardTerm(), LEVEL_CONTAIN);
        }
        // 5. 三级·模糊：字符 Dice ≥ 阈值，取最高分。
        //    ⚠️ R7 收紧：与输入构成「子串关系」的候选不参与打分 —— 否则 R2/R4 拦下的
        //    `脚气→干脚气`（Dice 恰好 0.8）、`藿香→广藿香`（0.8）会从模糊级复活。
        //    输入的去重字符集在这里算**一次**、全部候选复用（P1-16 收口）。
        char[] inputDistinct = distinctChars(input);
        TermEntry bestFuzzy = null;
        double bestScore = 0;
        for (TermEntry e : entries) {
            if (hasSubstringRelation(e, input)) {
                continue;
            }
            // 与输入仅差一个否定/减弱字（咽不痛/大便成形/食欲欠佳）语义正好相反，
            // Dice 仍可能 ≥0.8，模糊级必须否决；精确与包含级不受影响。
            double s = polarityOnlyDiff(input, e.getStandardTerm())
                    ? 0 : dicePrepared(inputDistinct, e.getStandardTerm());
            if (e.getAliases() != null) {
                for (String a : e.getAliases()) {
                    if (!polarityOnlyDiff(input, a)) {
                        s = Math.max(s, dicePrepared(inputDistinct, a));
                    }
                }
            }
            if (s > bestScore) {
                bestScore = s;
                bestFuzzy = e;
            }
        }
        if (bestFuzzy != null && bestScore >= scoreThreshold) {
            return new NormalizeResult(bestFuzzy.getStandardTerm(), LEVEL_FUZZY);
        }

        return null;
    }

    /** 标准词/别名是否作为**完整片段**出现在输入里，且差额不超过 containDelta（R2~R4）。 */
    private boolean containsAsPartOfInput(TermEntry e, String input, int inputLen) {
        String std = e.getStandardTerm();
        if (std != null && !std.isBlank() && input.contains(std)
                && inputLen - std.codePointCount(0, std.length()) <= containDelta) {
            return true;
        }
        if (e.getAliases() == null) {
            return false;
        }
        for (String a : e.getAliases()) {
            if (a == null || a.isBlank()) {
                continue;
            }
            // R8：单字别名只参与**召回与精确命中**，不参与包含（2026-10-19）。
            // 豆包补词引入了「脉软←软」「呕吐←吐/呕」「斑疹←斑/疹」这类单字别名，
            // 若允许包含，则任何含该字的 2 字串都会被吃掉：「质软」「软条」→ 脉软、
            // 「吐痰」「呕血」→ 呕吐、「湿疹」→ 斑疹。差额阈值（≤1）挡不住 1 字别名。
            if (a.codePointCount(0, a.length()) < 2) {
                continue;
            }
            // 别名只在这个方向上认：输入里出现了完整的别名片段（R4 禁掉的是反方向）
            if (input.contains(a) && inputLen - a.codePointCount(0, a.length()) <= containDelta) {
                return true;
            }
        }
        return false;
    }

    /**
     * 两串只差一个「否定 / 减弱」字（不无未否欠除减）→ 语义相反或不成立。
     * 例：咽不痛 vs 咽痛、大便成形 vs 大便不成形、食欲欠佳 vs 食欲佳。
     */
    private static boolean polarityOnlyDiff(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String longer;
        String shorter;
        if (a.length() == b.length() + 1) {
            longer = a;
            shorter = b;
        } else if (b.length() == a.length() + 1) {
            longer = b;
            shorter = a;
        } else {
            return false;
        }
        for (int i = 0; i < longer.length(); i++) {
            if (POLARITY_CHARS.indexOf(longer.charAt(i)) < 0) {
                continue;
            }
            if ((longer.substring(0, i) + longer.substring(i + 1)).equals(shorter)) {
                return true;
            }
        }
        return false;
    }

    /** 候选更短者优先（与改造前「多命中取最短标准词」同口径）。 */
    private static boolean shorter(TermEntry candidate, TermEntry best) {
        return best == null
                || candidate.getStandardTerm().length() < best.getStandardTerm().length();
    }

    /** 候选的 std / 任一别名是否与输入互为子串（R7：这类关系不许走模糊级，交给别名表）。 */
    private static boolean hasSubstringRelation(TermEntry e, String input) {
        if (substringEitherWay(e.getStandardTerm(), input)) {
            return true;
        }
        if (e.getAliases() != null) {
            for (String a : e.getAliases()) {
                if (substringEitherWay(a, input)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean substringEitherWay(String a, String input) {
        return a != null && !a.isBlank() && (a.contains(input) || input.contains(a));
    }

    private boolean containsAnyAlias(TermEntry e, String input) {
        // 1. 没别名就不参与包含判定
        if (e.getAliases() == null) return false;
        // 2. 任一别名双向包含即算命中；单字别名只用于召回与精确命中（同 R8）
        for (String a : e.getAliases()) {
            if (a == null || a.codePointCount(0, a.length()) < 2) continue;
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
     * 预置输入的 Dice：inputDistinct 已是去重后的输入字符集，其长度即 |A|。
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
