package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 实体术语归一：把抽取出的 9 类实体就地归一到标准术语。
 *
 * 与清洗链路的分工：本类服务于解析链路（`POST /api/nlp/extract` 返回前即时归一），
 * 目的是让用户在解析页当场看到「原文 → 标准词」的对照，而不是等到清洗阶段才知道术语是否规范。
 * 清洗链路（GovernanceServiceImpl.normalizeStructuredData）仍保留兜底补归一——
 * 因为病历可能来自导入、手工新增等未经解析页的入口。
 *
 * 为什么不会重复归一：两处都只做「原文 → 标准词」的单向替换，标准词本身在词典中
 * 是精确命中项，二次归一得到的仍是同一标准词；清洗链路另有「标准词与原值不同才计数」的判据，
 * 因此已归一实体不会被重复计入统计。
 *
 * 为什么不下推判定到 ES：与 {@link EsTermNormalizer} 一致——ES 只负责召回候选，
 * 精确 / 包含 / 字符 Dice≥0.8 三级判定仍在 Java 侧完成。
 *
 * 同标准词去重：归一本身会把不同原文折叠到同一标准词上——原文里同时有
 * 「嗳气」与「嗳气频作」时，后者按「包含命中」也归成「嗳气」，于是列表里出现两个一模一样的
 * 「嗳气」，用户会以为系统出错。{@link #dedupByTerm} 是唯一去重口径，解析链路与清洗链路
 * 共用（与 {@link #dictionaryType} 同一思路：口径只写一处，避免两处漂移）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityNormalizer {

    private final EsTermNormalizer termNormalizer;
    /** {@link #normalizeMap} 需要在 Map 与 VO 之间转换；显式注入而不是 new 出来 */
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /**
     * 组织级「归一范围」来源（normalization.elementTypes）—— 哪些实体类型做词形归一。
     *
     * 刻意用字段注入而非构造参数：{@code @RequiredArgsConstructor} 会把新增的 final
     * 字段加进构造签名，而 EntityNormalizerMapTest 等直接
     * {@code new EntityNormalizer(term, mapper)} 构造，加参数会破坏它们。
     * 标 {@code required=false}：单测无此 bean 时退回出厂默认范围（与旧行为一致）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcm.ehr.common.config.QcRuleStore ruleStore;

    /** 无 {@link #ruleStore}（单测直接 new）时的兜底归一范围，见 QcRuleSet.DEFAULT_NORMALIZATION_TYPES */
    private static final java.util.Set<String> FALLBACK_NORM_SCOPE =
            java.util.Set.copyOf(com.tcm.ehr.common.config.QcRuleSet.DEFAULT_NORMALIZATION_TYPES);

    /** 本组织参与归一的类型集合；无 store 时退回出厂默认（9 类，含病因） */
    private java.util.Set<String> normScope(String orgId) {
        return ruleStore == null ? FALLBACK_NORM_SCOPE : ruleStore.normalizationTypes(orgId);
    }

    /**
     * 否定语境里被「包含命中」拦下、未写 normLevel 的实体数（仅观测，不参与命中统计）。
     *
     * 存在的理由：这类拦截会让症状未归一次数**变多**（本会误归的现在保持未归一），
     * 报表上看着像「归一变差了」。有这个计数才能对账：
     * 拦截量 + 误归量 = 不拦截时的包含命中量。测试据此断言「拦的是否证句，
     * 没有把干净命中一起拦掉」。
     */
    private final java.util.concurrent.atomic.AtomicInteger negatedSkip =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 观测用：读取（并清零）自上次读取以来被否定语境拦下的实体数。 */
    public int drainNegatedSkip() {
        return negatedSkip.getAndSet(0);
    }

    /**
     * 归一统计。
     *
     * @param hit 命中词典的实体数（level 1~3）
     * @param exact 精确命中数
     * @param contain 包含命中数
     * @param fuzzy 模糊命中数
     */
    public record NormStat(int hit, int exact, int contain, int fuzzy) {
        static NormStat of(int[] stat) {
            return new NormStat(stat[1] + stat[2] + stat[3], stat[1], stat[2], stat[3]);
        }
    }

    /**
     * 附录A 字段名 → 词典类型（唯一映射，解析链路与清洗链路共用，避免两处口径漂移）。
     *
     * 取自 {@link com.tcm.ehr.common.config.EntityTypes}：9 类实体现均有独立词典
     * （病因自本批次起有 causes.json），故凡登记过的字段都返回其词典类型 key；
     * 只有未登记的字段名返回 {@code null}。是否真的归一还取决于该类型是否在
     * 本组织「归一范围」内（见 {@link #normScope}），二者不是一回事。
     *
     * @return 词典类型；该字段无独立词典时返回 {@code null}
     */
    public static String dictionaryType(String fieldKey) {
        var t = com.tcm.ehr.common.config.EntityTypes.dictTypeByStructuredKey(fieldKey);
        return t == null ? null : t.key();
    }

    /**
     * 同一标准词只留一条代表（唯一去重口径，解析链路与清洗链路共用）。
     *
     * 去重范围＝调用方给的那一个列表，即字段内，不跨字段——「风寒」同时出现在
     * 疾病与病因是两个不同的语义槽位，各自保留。
     *
     * 保留哪一条（依次比较，先满足者胜）：
     * ① sourceText 更长优先——原文更完整（「嗳气频作」优于「嗳气」），
     * 且「原文 → 标准词」的对照才看得见，否则归一结果看起来什么都没发生；
     * ② 等长时 normLevel 更小优先（1 精确 &lt; 2 包含 &lt; 3 模糊 &lt; 未命中）；
     * ③ 再相等时保留先出现的那条（{@link LinkedHashMap} 保序，重复键 put 不改位置）。
     *
     * 代表条目整条保留自己的 confidence，不跨条取最大值——置信度描述的是它那条 span，
     * 拼装会让数字与展示的原文对不上。
     *
     * 术语键为空的条目不参与合并（各自用唯一键占位）——否则所有缺 content 的条目
     * 会被并成一条。
     *
     * @param items 待去重列表，可为 null
     * @param termOf 取「归一后标准词」；为空则该条不参与合并
     * @param sourceOf 取「归一前原文」，用于比较完整性
     * @param levelOf 取「命中层级」，可为 null（未命中）
     * @return 去重后的新列表；items 为 null 或不足 2 条时原样返回
     */
    public static <T> List<T> dedupByTerm(List<T> items,
                                          Function<T, String> termOf,
                                          Function<T, String> sourceOf,
                                          Function<T, Integer> levelOf) {
        // 1. 不足两条无从去重，原样返回
        if (items == null || items.size() < 2) {
            return items;
        }
        // 2. 按标准词建索引，LinkedHashMap 保住原有顺序
        Map<String, T> kept = new LinkedHashMap<>();
        int blankSeq = 0;
        for (T item : items) {
            if (item == null) {
                continue;
            }
            String term = termOf.apply(item);
            // 术语为空：用唯一键占位，保证「不参与合并」而不是「全部并成一条」
            String key = (term == null || term.isBlank()) ? "\u0000" + (blankSeq++) : term;
            T prev = kept.get(key);
            // 3. 同键时二选一：新来的更代表就替换，否则保留先出现的那条
            if (prev == null || isMoreRepresentative(item, prev, sourceOf, levelOf)) {
                kept.put(key, item);
            }
        }
        return new ArrayList<>(kept.values());
    }

    /** 见 {@link #dedupByTerm} 的「保留哪一条」：① 原文更长 ② 层级更精确 ③ 先出现者胜 */
    private static <T> boolean isMoreRepresentative(T candidate, T current,
                                                    Function<T, String> sourceOf,
                                                    Function<T, Integer> levelOf) {
        // 1. 原文更长者优先：信息量大的那条更可能是完整表述
        int lc = lengthOf(sourceOf.apply(candidate));
        int lp = lengthOf(sourceOf.apply(current));
        if (lc != lp) {
            return lc > lp;
        }
        // 2. 长度打平再看层级（精确优于包含优于模糊）；完全一样返回 false，即先出现者胜
        return rankOf(levelOf.apply(candidate)) < rankOf(levelOf.apply(current));
    }

    /** 命中层级排序权重：1 精确 &lt; 2 包含 &lt; 3 模糊 &lt; 未命中（null） */
    private static int rankOf(Integer level) {
        return level == null ? 4 : level;
    }

    private static int lengthOf(String s) {
        return s == null ? 0 : s.length();
    }

    /** 就地归一抽取结果中的 8 类 Entity 与 herbs（共 9 路），返回命中统计 */
    public NormStat normalize(NlpExtractVO vo) {
        return normalize(vo, com.tcm.ehr.common.utils.RequestUtils.currentOrgId());
    }

    /**
     * 组织级归一：按 orgId 限定的词典范围（基础层 + 该组织）召回。
     *
     * 批任务必须传任务行上的 orgId 快照，不能走无参版：worker 线程没有
     * RequestContext，{@code currentOrgId()} 会拿到空串 → 静默只查基础层，
     * 组织自定义词条对批任务完全失效，而且不报错。
     */
    /**
     * 组织级归一（就地改 structured_data 的 Map 形态）。
     *
     * 用途：人工复核提交修正后写回前用。复核员新输入的词只带
     * {@code content/name}，没有 normLevel；而 QcScorer.countUnnormalized
     * 把「无 normLevel」算作未标准化 → 人工修正反而扣分（weightEach=1、封顶 5）。
     * 这里在写回前跑一遍与模型抽取完全相同的归一，口径统一。
     *
     * 为什么不在 Map 与 VO 之间整体来回转换：NlpExtractVO 里没有
     * _meta / modelAvailable / truncated，整体转换会丢键，
     * 也会给未赋值的属性写出 {@code null} 键（JSON 变胖）。所以这里只把 9 个实体列表
     * 与 herbs 的归一结果写回原 Map，其余键原样保留。
     *
     * @param data  structured_data 的 Map 形态（就地修改）
     * @param orgId 组织号
     * @return 命中统计
     */
    public NormStat normalizeMap(Map<String, Object> data, String orgId) {
        if (data == null) {
            return new NormStat(0, 0, 0, 0);
        }
        NlpExtractVO vo;
        try {
            vo = objectMapper.convertValue(data, NlpExtractVO.class);
        } catch (IllegalArgumentException e) {
            // 结构化数据形状异常就不归一：让复核继续走完，不因一条脏数据卡住复核员
            log.warn("[归一] 结构化数据转 VO 失败，跳过归一: {}", e.getMessage());
            return new NormStat(0, 0, 0, 0);
        }
        NormStat stat = normalize(vo, orgId);
        // 回写成 Map 而不是直接塞 vo.getXxx()：后者会把 NlpExtractVO.Entity / Herb
        // 这些**强类型对象**留在 Map 里，而方法约定是「键值仍与 structured_data 同构的 Map」。
        // 一旦有调用方接着读这个 Map（比如按 Map 取实体去比对），就会 ClassCastException。
        // 我的测试先踩了这个坑才定下来的。
        writeBack(data, "diseases", vo.getDiseases());
        writeBack(data, "symptoms", vo.getSymptoms());
        writeBack(data, "tongueList", vo.getTongueList());
        writeBack(data, "pulseList", vo.getPulseList());
        writeBack(data, "patternList", vo.getPatternList());
        writeBack(data, "causeList", vo.getCauseList());
        writeBack(data, "treatmentList", vo.getTreatmentList());
        writeBack(data, "formulaList", vo.getFormulaList());
        writeBack(data, "herbs", vo.getHerbs());
        // inquiry（任务 8·方案 A）为「问诊子项」透传位：不参与上方任何归一/扣分，
        // 仅把抽取侧挪出的二便/睡眠/饮食/喜好/卧位/精神/面色整句原样保留进 structured_data。
        writeBack(data, "inquiry", vo.getInquiry());
        return stat;
    }

    /**
     * 把误放进 symptoms 的脉象/舌象实体挪到各自字段（词典驱动，不看词形）。

    *
    * 归属约定（P2-11，2026-10-05）：实体类型的源头判定在抽取侧（python-nlp 的抽取服务）；
    * 本方法只是兜底 —— 用于两类情形：① 历史数据里已经错分的实体；② 抽取侧判错时的补救。
    * 它不应成为常态：若统计或日志显示本方法频繁生效，正确做法是修抽取侧的类型判定，
    * 而不是继续在这里加规则（Java 侧只做校验与兜底，不承担类型判定职责）。
     *
     * 为什么用词典命中而不是词形匹配：像「酸软无力」这种真正的症状也含「无力」，
     * 按词形挪会误伤它（实测确有 50 条「酸软无力」）。判据与中药那段一致 ——
     * 归一命中词典即视为「本类型术语」，{@link EsTermNormalizer.NormalizeResult#level()}
     * 落在命中区间（{@link EsTermNormalizer#isHit(int)}）。
     *
     * 顺序：先试脉象、未命中再试舌象（两次 ES 查询只在确有症状实体时才发生，
     * 且第二次靠短路避免无谓查询）。命中的实体带着原 content/sourceText 挪过去，
     * 随后的 normEntities 会照常给它们打上 normLevel。
     */
    private void moveMisplacedPulseTongue(NlpExtractVO vo, String orgId) {
        if (vo == null || vo.getSymptoms() == null || vo.getSymptoms().isEmpty()) {
            return;
        }
        java.util.List<NlpExtractVO.Entity> kept = new java.util.ArrayList<>();
        int moved = 0;
        for (NlpExtractVO.Entity e : vo.getSymptoms()) {
            if (e == null) {
                continue;
            }
            String raw = rawOf(e.getContent(), e.getSourceText());
            if (raw != null && !raw.isBlank()) {
                // 先判「它本身就是症状术语」→ 是就别动（避免把真症状挪走，也避免词典重叠时误判）
                if (hitsDictionary("symptom", orgId, raw)) {
                    kept.add(e);
                    continue;
                }
                // 兜底二：整串命中不了词典，但**能被脉象/舌象的拆解规则完整拆开**的，
                // 就是脉象/舌象内容被错放（「左略涩」→ 脉涩、「双上略浮下略沉」→ 脉浮+脉沉、
                // 「红点满布」→ 舌红点）。拆解本身要求整串被词条覆盖，因此不会把真症状挪走；
                // 挪过去后 normEntities 会照常拆出标准词，两边分子同时受益。
                if (hitsDictionary("pulse", orgId, raw)
                        || !termNormalizer.decompose("pulse", orgId, raw).isEmpty()) {
                    vo.getPulseList().add(e);
                    moved++;
                    continue;
                }
                if (hitsDictionary("tongue", orgId, raw)
                        || !termNormalizer.decompose("tongue", orgId, raw).isEmpty()) {
                    vo.getTongueList().add(e);
                    moved++;
                    continue;
                }
            }
            kept.add(e);
        }
        if (moved > 0) {
            vo.setSymptoms(kept);
            log.info("[归一] 从症状里归位脉象/舌象实体 {} 条（词典命中判定）", moved);
        }
    }

    /**
     * 丢弃单字残词（#6，2026-10-05）。
     *
     * 实测：症状字段里长度 1 的实体 135 条、病因 50 条，内容是 `双`/`腰`/`失`/`口`/`眼`/`下`/`遇`/`胸`/`心`
     * 这类截断残字（来自「双下肢水肿」「腰酸」「失眠」等）。它们必然未归一，既拉低归一率，
     * 又让复核员在列表里看到一堆无意义的字。
     *
     * 判据：长度为 1 且不在该类型词典里才丢。为什么不是「长度为 1 就丢」——
     * 词典里确有 5 个单字标准词（如单字术语），一刀切会误删合法术语；
     * 而「在词典里」的单字能正常归一，本就不该丢。无词典的类型（4 类无词典实体）
     * 因为没有可依据的词典，按残词处理（实测那几类里同样只有截断字）。
     */
    private void dropSingleCharFragments(NlpExtractVO vo, String orgId) {
        if (vo == null) {
            return;
        }
        int dropped = 0;
        dropped += dropIn(vo.getSymptoms(), "symptom", orgId);
        dropped += dropIn(vo.getDiseases(), "disease", orgId);
        dropped += dropIn(vo.getPatternList(), "pattern", orgId);
        dropped += dropIn(vo.getTongueList(), "tongue", orgId);
        dropped += dropIn(vo.getPulseList(), "pulse", orgId);
        dropped += dropIn(vo.getFormulaList(), "formula", orgId);
        // 无词典的 3 类：没有可依据的词典，长度 1 一律按残词处理
        dropped += dropIn(vo.getCauseList(), null, orgId);
        dropped += dropIn(vo.getTreatmentList(), null, orgId);
        if (dropped > 0) {
            log.info("[归一] 丢弃单字残词 {} 条（长度为 1 且不在词典里）", dropped);
        }
    }

    /** 在单个列表上执行残词过滤；type 为 null 表示该类型无词典 */
    private int dropIn(java.util.List<NlpExtractVO.Entity> list, String type, String orgId) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int before = list.size();
        list.removeIf(e -> {
            if (e == null) {
                return true;
            }
            String raw = rawOf(e.getContent(), e.getSourceText());
            if (raw == null || raw.codePointCount(0, raw.length()) != 1) {
                return false;
            }
            return type == null || !hitsDictionary(type, orgId, raw);
        });
        return before - list.size();
    }

    /** 该文本是否命中指定词典（命中 = level 落在精确~模糊，与中药那段同口径） */
    private boolean hitsDictionary(String type, String orgId, String raw) {
        try {
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize(type, orgId, raw);
            return r != null && EsTermNormalizer.isHit(r.level());
        } catch (Exception ex) {
            // 词典/ES 不可用时**不挪**：宁可少归位，也不能凭猜把症状改成脉象
            log.warn("[归一] 归位判定失败（type={}）: {}", type, ex.getMessage());
            return false;
        }
    }

    /** 精确命中（只用 LEVEL_EXACT）：路由判定必须精确，含/模糊会把「风湿性」归到「风湿」。 */
    private boolean hitsExact(String type, String orgId, String raw) {
        try {
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize(type, orgId, raw);
            return r != null && r.level() == EsTermNormalizer.LEVEL_EXACT;
        } catch (Exception ex) {
            log.warn("[归一] 精确命中判定失败（type={}）: {}", type, ex.getMessage());
            return false;
        }
    }

    /** 把一批实体追加到目标列表（目标为 null 时新建），并渲染新（新增的在后）。 */
    private static java.util.List<NlpExtractVO.Entity> appendAll(
            java.util.List<NlpExtractVO.Entity> target,
            java.util.List<NlpExtractVO.Entity> added) {
        if (added.isEmpty()) {
            return target;
        }
        java.util.List<NlpExtractVO.Entity> out =
                target == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(target);
        out.addAll(added);
        return out;
    }

    /**
     * 把误放进 diseases 的其它类型实体路由到各自字段（词典驱动，2026-10-21）。
     *
     * 实测依据（34154 份合格病历）：疾病字段 3864 条未归一里，1567 条根本不是疾病名 ——
     * 937 条是六淫/病理因素（风湿 655 / 风寒 113 / 郁热 51 / 水饮 45 …），
     * 630 条是症状或证候（发热 / 气短 / 疲劳 / 脾虚 / 肾虚 …）。
     * 它们留在 diseases 里既归不上，又把疾病标准化率从 88.9% 拖下来。
     *
     * 路由优先级 cause &gt; symptom &gt; pattern：那 937 条同时命中病因与证候词典，
     * 逐词人工核对后确认全是六淫与病机（风/寒/湿/热/毒/气血虚），出现在「疾病」位置
     * 只能是抽取把病因错分进来，故优先归病因。
     *
     * 判据只用精确命中：走 isHit（含/模糊）会把「风湿性」路由成「风湿」，
     * 属 R7 类错映射。已在疾病词典精确命中的实体一律不动（它是合法疾病词）。
     */
    private void rerouteCrossFieldDiseases(NlpExtractVO vo, String orgId) {
        java.util.List<NlpExtractVO.Entity> dis = vo.getDiseases();
        if (dis == null || dis.isEmpty()) {
            return;
        }
        java.util.List<NlpExtractVO.Entity> kept = new java.util.ArrayList<>();
        java.util.List<NlpExtractVO.Entity> toCause = new java.util.ArrayList<>();
        java.util.List<NlpExtractVO.Entity> toSymptom = new java.util.ArrayList<>();
        java.util.List<NlpExtractVO.Entity> toPattern = new java.util.ArrayList<>();
        for (NlpExtractVO.Entity e : dis) {
            if (e == null) {
                continue;
            }
            String raw = rawOf(e.getContent(), e.getSourceText());
            if (raw.isEmpty()) {
                kept.add(e);
                continue;
            }
            if (hitsExact("disease", orgId, raw)) {
                kept.add(e);
                continue;
            }
            if (hitsExact("cause", orgId, raw)) {
                toCause.add(e);
            } else if (hitsExact("symptom", orgId, raw)) {
                toSymptom.add(e);
            } else if (hitsExact("pattern", orgId, raw)) {
                toPattern.add(e);
            } else {
                // 无落点：可能是真词表缺口（如「尿频病」），留在 diseases 等待补词
                kept.add(e);
            }
        }
        int moved = toCause.size() + toSymptom.size() + toPattern.size();
        if (moved == 0) {
            return;
        }
        vo.setDiseases(kept);
        vo.setCauseList(appendAll(vo.getCauseList(), toCause));
        vo.setSymptoms(appendAll(vo.getSymptoms(), toSymptom));
        vo.setPatternList(appendAll(vo.getPatternList(), toPattern));
        log.info("[归一] 疾病字段跨类路由 {} 条（病因 {} / 症状 {} / 证候 {}）",
                moved, toCause.size(), toSymptom.size(), toPattern.size());
    }

    /**
     * 精确命中回拉（任务 8·方案 A 精度收口，与 moveMisplacedPulseTongue 同层的兜底）：
     * python 的 _reroute_inquiry 只按前缀锚分批，可能把恰是症状词典标准词/别名的真症状
     * 一起挪进 inquiry（如 嗜睡/乏力/便秘/小便不禁/面色萎黄）。
     * 这里把能被症状词典【精确】命中的拉回 symptoms，交由随后的 normEntities 正常归一。
     *
     * 判据只用精确（EsTermNormalizer.LEVEL_EXACT），绝不用 isHit（含/模糊）：
     * 否则 大便成形 会对 大便溏 走模糊命中（Dice=0.8）拉回并归成 大便溏，重演 R7 错映射。
     * 词典/ES 不可用时【不拉回】（宁可在 inquiry 里，也不凭猜把问诊当症状）。
     * 每次 normalizer 只跑一遍，纯函数式重写列表，不做有状态累积（幂等纪律）。
     */
    private void pullExactSymptomBack(NlpExtractVO vo, String orgId) {
        java.util.List<NlpExtractVO.Entity> inquiry = vo.getInquiry();
        if (inquiry == null || inquiry.isEmpty() || vo.getSymptoms() == null) {
            return;
        }
        java.util.List<NlpExtractVO.Entity> kept = new java.util.ArrayList<>(inquiry.size());
        int pulled = 0;
        for (NlpExtractVO.Entity e : inquiry) {
            if (e == null) {
                kept.add(e);
                continue;
            }
            String raw = rawSpan(e);   // 读 sourceText 优先（不读上一轮写回的 content）
            if (!raw.isBlank() && exactSymptomHit(orgId, raw)) {
                vo.getSymptoms().add(e);   // 拉回，交给后面的 normEntities 归一
                pulled++;
            } else {
                kept.add(e);
            }
        }
        if (pulled > 0) {
            vo.setInquiry(kept);
            log.info("[归一] 从 inquiry 拉回症状词典精确命中的真症状 {} 条", pulled);
        }
    }

    /** 该原文是否被症状词典【精确】命中（标准词或别名恰好等于原文；不参与含/模糊）。 */
    private boolean exactSymptomHit(String orgId, String raw) {
        try {
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize("symptom", orgId, raw);
            return r != null && r.level() == EsTermNormalizer.LEVEL_EXACT;
        } catch (Exception ex) {
            log.warn("[归一] inquiry 精确命中判定失败: {}", ex.getMessage());
            return false;
        }
    }

    /** 把归一后的强类型列表转回 Map 列表再放进 data，保持 Map 的同构约定 */
    private void writeBack(Map<String, Object> data, String key, List<?> typed) {
        data.put(key, objectMapper.convertValue(typed,
                new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                }));
    }

    /**
     * 预取召回（批次12 · 12b）：在逐条归一**之前**，把本次要归一的术语按类型一次批量问 ES。
     *
     * 不改判定、只预热：EsTermNormalizer.prefetch 走 _msearch 一次往返，
     * 并把结果写进与单条 search 相同的缓存键，随后的逐条归一因此全部命中缓存。
     *
     * 类型 key 由 structuredKey 反查 {@link com.tcm.ehr.common.config.EntityTypes} 得到 ——
     * 复用那一份登记，不在本类里再写一张「字段名→类型」的表。
     */
    private void prefetchRecall(NlpExtractVO vo, String orgId, java.util.Set<String> scope) {
        java.util.Map<String, java.util.LinkedHashSet<String>> byStructuredKey = new java.util.LinkedHashMap<>();
        collectRaws(byStructuredKey, "diseases", vo.getDiseases());
        collectRaws(byStructuredKey, "symptoms", vo.getSymptoms());
        collectRaws(byStructuredKey, "tongueList", vo.getTongueList());
        collectRaws(byStructuredKey, "pulseList", vo.getPulseList());
        collectRaws(byStructuredKey, "patternList", vo.getPatternList());
        collectRaws(byStructuredKey, "causeList", vo.getCauseList());
        collectRaws(byStructuredKey, "treatmentList", vo.getTreatmentList());
        collectRaws(byStructuredKey, "formulaList", vo.getFormulaList());
        // 中药是另一种 VO 类型：归一目标是 name 而不是 content（与下方第 3 步同口径）
        if (vo.getHerbs() != null) {
            for (NlpExtractVO.Herb h : vo.getHerbs()) {
                String raw = rawOf(h.getName(), h.getSourceText());
                if (raw != null && !raw.isBlank()) {
                    byStructuredKey.computeIfAbsent("herbs", k -> new java.util.LinkedHashSet<>()).add(raw);
                }
            }
        }
        byStructuredKey.forEach((structuredKey, raws) -> {
            com.tcm.ehr.common.config.EntityTypes.EntityType t =
                    com.tcm.ehr.common.config.EntityTypes.dictTypeByStructuredKey(structuredKey);
            // 只预热本组织归一范围内的类型：范围外的类型不会走归一，预热是白问一次 ES
            if (t != null && scope.contains(t.key())) {
                termNormalizer.prefetch(t.key(), orgId, new java.util.ArrayList<>(raws));
            }
        });
    }

    /** 把某一类实体的原文收进待预取的集合（同一批里去重） */
    private void collectRaws(java.util.Map<String, java.util.LinkedHashSet<String>> byStructuredKey,
                             String structuredKey, List<NlpExtractVO.Entity> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<String> raws =
                byStructuredKey.computeIfAbsent(structuredKey, k -> new java.util.LinkedHashSet<>());
        for (NlpExtractVO.Entity e : list) {
            String raw = rawSpan(e);
            if (raw != null && !raw.isBlank()) {
                raws.add(raw);
            }
        }
    }

    public NormStat normalize(NlpExtractVO vo, String orgId) {
        // 1. 没抽取出东西就不做归一
        if (vo == null) {
            return new NormStat(0, 0, 0, 0);
        }
        int[] stat = {0, 0, 0, 0};

        // 1.3 本组织参与归一的类型集合（规则集 normalization.elementTypes）。
        //     不在范围内的类型一律只保留原文、不写 normLevel —— 与「无独立词典」同一处理：
        //     归一执行链路据此开关，QcScorer 的扣分范围再与它取交集。
        java.util.Set<String> scope = normScope(orgId);

        // 1.4 预取召回（批次12 · 12b）：放在最前，用一次 _msearch 把原文预热进与单条 search
        //     相同的缓存键，随后 1.6 丢弃与逐条归一 的查询全部命中缓存，只发一次 ES 往返。
        //     ⚠️ 本注释原写「放在归位与丢弃之后」，与代码不符（代码在最前）。按当前顺序，预取
        //     集合会含随后被丢弃的单字残词（少量浪费）；但若真挪到 1.6 之后，1.6 的查询会全部
        //     落回单条 ES（更慢），故保留当前顺序、订正注释（审查报告 §4.1）。
        prefetchRecall(vo, orgId, scope);

        // 1.5 归位（2026-10-05 修 #2）：抽取侧有时把脉象/舌象内容打进 symptoms ——
        //     实测症状字段 4170 条里有 107 条的内容**恰是脉象词典里的标准词**
        //     （「脉细数」「左尺无力」「脉弦劲有力」「脉浮」）。不归位的话它们会以
        //     「未归一的症状」计入分母，既拉低症状归一率，也可能影响完整性判定。
        moveMisplacedPulseTongue(vo, orgId);
        // 1.5b 疾病字段跨类路由（2026-10-21）：病因/症状/证候被抽进 diseases 时归位到各自字段。
        //      必须在 normEntities 之前 —— 归位后它们才会按目标字段的词典正常归一。
        rerouteCrossFieldDiseases(vo, orgId);
        // 1.6 丢弃单字残词（2026-10-05 修 #6）：实测症状/病因里有 135/50 条长度为 1 的实体
        //     （`双`50 `腰`34 `失`22 `口`10 `眼`9 …），全是「双下肢水肿」「腰酸」「失眠」这类
        //     被截断的残字。判据同样是**词典驱动**：长度为 1 **且不在该类型词典里**才丢 ——
        //     词典里确有 5 个单字标准词，一刀切会误删合法术语（本批数据里这类为 0，但规则要立对）。
        dropSingleCharFragments(vo, orgId);
        // 1.7 精确命中回拉（任务 8·方案 A 精度收口）：python 侧把问诊整句挪进 inquiry 时只看
        //     前缀锚，可能把「恰是症状词典标准词/别名的真症状」一起挪走（如 嗜睡/乏力/便秘/小便不禁
        //     /面色萎黄）—— 实测症状词典里 116 条与问诊锚碰撞。这里是词典驱动的兜底：inquiry 里
        //     sourceText 能被症状词典【精确】命中（LEVEL_EXACT）的拉回 symptoms，参与后续正常归一。
        //     判据只用精确、绝不用 isHit（含/模糊）——否则 大便成形 会按模糊命中归成 大便溏（R7 类
        //     错映射）。放在 normEntities 之前：拉回的实体走一遍正常归一拿到标准词与 normLevel。
        pullExactSymptomBack(vo, orgId);

        // 2. 8 类 Entity 走同一字段→类型映射；无词典 / 未纳入归一范围的类型只回填 sourceText
        vo.setDiseases(normEntities(vo.getDiseases(), "diseases", stat, orgId, scope));
        vo.setSymptoms(normEntities(vo.getSymptoms(), "symptoms", stat, orgId, scope));
        vo.setTongueList(normEntities(vo.getTongueList(), "tongueList", stat, orgId, scope));
        vo.setPulseList(normEntities(vo.getPulseList(), "pulseList", stat, orgId, scope));
        vo.setPatternList(normEntities(vo.getPatternList(), "patternList", stat, orgId, scope));
        vo.setCauseList(normEntities(vo.getCauseList(), "causeList", stat, orgId, scope));
        vo.setTreatmentList(normEntities(vo.getTreatmentList(), "treatmentList", stat, orgId, scope));
        vo.setFormulaList(normEntities(vo.getFormulaList(), "formulaList", stat, orgId, scope));

        // 3. 中药单独处理：归一目标是 name 而不是 content
        //    ⚠️ 判空不可省：structured_data 可能没有 herbs 键（或显式为 null），而其余 8 类走
        //    normEntities 已有同样的判空 —— 这里原先是漏的（《common组件审查报告》§七.6）。
        //    同一份 VO 在上方 prefetchRecall 里也是判空后遍历的。
        if (vo.getHerbs() != null) {
            boolean herbOn = scope.contains("herb");
            for (NlpExtractVO.Herb herb : vo.getHerbs()) {
                if (herb == null) continue;
                // 中药以 name 为归一目标，sourceText 保留原文（模型侧 name 与 sourceText 同源）
                String raw = rawOf(herb.getName(), herb.getSourceText());
                if (raw.isEmpty()) continue;
                if (TextUtil.isBlank(herb.getSourceText())) {
                    herb.setSourceText(raw);
                }
                // 中药不在归一范围 → 只保留原文，不写 normLevel（同无词典处理）
                if (!herbOn) {
                    continue;
                }
                EsTermNormalizer.NormalizeResult r = termNormalizer.normalize("herb", orgId, raw);
                // 4. 没命中词典就保持原样（不写 normLevel，质控据此算"未标准化"）
                if (!EsTermNormalizer.isHit(r.level())) {
                    continue;
                }
                herb.setName(r.standardTerm());
                herb.setNormLevel(r.level());
                stat[0]++;
                stat[r.level()]++;
            }
            // 5. 中药也按标准词去重
            vo.setHerbs(dedupByTerm(vo.getHerbs(), NlpExtractVO.Herb::getName,
                    NlpExtractVO.Herb::getSourceText, NlpExtractVO.Herb::getNormLevel));
        }

        // 4. 噪声碎片过滤：归一之后仍无 normLevel 的实体里，属于「单维描述 / 裸部位 / 残词」
        //    的一律不入库（见 #dropNoiseFragments）。
        int noise = dropNoiseFragments(vo);
        if (noise > 0) {
            log.info("[归一] 丢弃噪声碎片实体 {} 条（描述词 / 裸部位 / 残词）", noise);
        }

        return NormStat.of(stat);
    }

    /** 单维描述词的裸名词（去掉后不影响语义的碎片）。 */
    private static final Set<String> NOISE_BARE = Set.of(
            "大便", "小便", "二便", "食欲", "食量", "疼痛", "口气", "扁桃体", "血块",
            "月经", "睡眠", "精神", "体力", "饮食", "饮水", "舌", "苔", "脉", "色", "量");

    /** 舌象的裸部位碎片（「舌质」「舌尖」「根部」这类截断词）。 */
    private static final Set<String> NOISE_TONGUE_BARE = Set.of(
            "舌质", "舌下", "舌尖", "舌体", "舌面", "苔", "根部", "边界", "中根", "舌");

    /** 单维描述：颜色 / 量 / 质地 / 时间 / 频次 / 程度。 */
    private static final java.util.regex.Pattern NOISE_DESC = java.util.regex.Pattern.compile(
            "^(色[\\u4e00-\\u9fa5]{1,3}"
                    + "|量(少|多|可|中等|一般|正常|偏少|偏多)"
                    + "|偏干|偏稀|偏硬|偏软|成形|成条|干结|尚可|正常|一般"
                    + "|晨起|清晨|夜间|夜里|晚上|平时|平素|近日|近期|偶尔|偶有|时有|经常|持续|反复|睡前|饭后|食后|经前|经期|经后"
                    + "|慢性|急性|亚急性|过敏性|原发性|继发性|轻度|中度|重度"
                    + ")$");

    /** 疾病 / 证候里只剩分级词（「慢性」「过敏性」）—— 截断残词，不是诊断。 */
    private static final java.util.regex.Pattern NOISE_SEVERITY = java.util.regex.Pattern.compile(
            "^(慢性|急性|过敏性|原发性|继发性|轻度|中度|重度|亚急性)$");

    // ==================== 抽取残次品判定（2026-10-21）====================
    // 背景：对导出数据集做全字段审计后发现，未归一实体里有一批**根本不该是实体**的东西，
    // 它们拉低标准化率却不是词表缺口。逐类给出明确判据，只删「确定不可能是合法实体」的。

    /**
     * 非中药词素：化验指标 / 人体部位 / 食物，被 NER 误抽成 herbs 时的判定词表。
     *
     * 为什么用词根而非全称：实测 NER 会把「总胆红素」截成「总胆红」、
     * 「血常规」截成「血常」，全称黑名单拦不住。改用能覆盖截断形态的词根
     * （胆红 / 血清 / 潜血 / 转氨 …），但避开会误伤真药名的宽词根
     * （不用单独「胆」，否则「蛇胆陈皮」会被误杀；不用单独「桃」，否则「桃仁」遭殃）。
     *
     * 与 python 层的关系：python 侧 nlp_filters.py 的
     * HERB_LAB_TERMS / HERB_ANATOMY / HERB_FOOD 是同一批词。
     * 这里重复一份是刻意的：python 侧要重抽才生效，而本层跑一次归一即可 ——
     * 两处判据等价、幂等（都只是删除同类噪声），重抽后双份生效也无害。
     *
     * 零误伤保证：{@link #dropNoiseIn} 只删 {@code normLevel == null} 的实体，
     * 已归一的合法药材绝不会被这条规则命中。
     */
    private static final String[] NON_HERB_TERMS = {
            // 化验指标（含截断变体）
            "甘油三", "胆红", "胆固醇", "肌酐", "尿酸", "尿素", "葡萄糖",
            "血小板", "谷草", "谷丙转氨", "血红蛋白", "白细胞", "红细胞",
            "血糖", "血脂", "总蛋白", "白蛋白", "球蛋白", "转氨酶", "尿素氮",
            "血沉", "血常规", "血常", "潜血", "间胆", "总胆", "总胆汁酸",
            "甘胆酸", "糖化血红蛋", "血清",
            // 人体部位 / 器官被当成中药（实测「扁桃体肿大」被截成「扁桃」抽成药材）
            "扁桃", "淋巴", "甲状腺", "脂肪肝", "肝内",
            // 食物（患者自述「吃苹果」被抽成中药）
            "胡萝卜", "苹果", "花菜", "空心菜"};

    /** 脉诊手法：描述「怎么切脉」，既不是症状也不是脉象实体（实测 273 条）。 */
    private static final String[] PULSE_MANUAL = {"不受按", "中取", "浮取", "沉取", "应指"};

    /** 舌象子要素：属舌象字段的子成分，被单独抽成实体即错放（实测含「红点多」225 条）。 */
    private static final String[] TONGUE_ELEMENT = {"红点", "瘀点", "齿痕", "裂纹", "边有"};

    /**
     * 病程 / 诱因前缀：是对症状的**修饰**而非本体（活动后缓解 / 遇劳加重 / 受凉后…）。
     *
     * 实测 1451 条。它们没有独立标准词可补，补进词表会让所有含该子串的实体被错误归并。
     */
    private static final String[] PROCESS_DESC_LEAD = {
            "活动后", "活动时", "活动则", "休息后", "休息时", "劳累后", "劳累时", "劳则",
            "遇劳", "遇冷", "遇热", "遇风", "受凉后", "受寒后", "受风后",
            "进食后", "进餐后", "服药后", "饮水后", "大便后", "小便后",
            "持续性", "反复发作", "阵发性", "间歇性", "夜间明显", "晨起明显", "午后",
            "加重", "减轻", "缓解", "好转", "自行缓解"};

    /** 六经名：证候的构成成分，不是方剂 / 疾病实体（方剂里实测 8 条「太阴」「阳明」）。 */
    private static final Set<String> LIUJING_BARE = Set.of(
            "太阳", "阳明", "少阳", "太阴", "少阴", "厥阴");

    /** 含标点 / 引号 / 括号 / 空白：抽取把残句或整句带进来了（实测脉象 321 + 舌象 414 条）。 */
    private static final java.util.regex.Pattern CONTAINS_PUNCT = java.util.regex.Pattern.compile(
            "[\\s（）()\\[\\]【】“”\"'、，。；;：]");

    /** 方剂残片：处方头 / 剂型名不是方剂（实测 34 条「中草药」+ 45 条过短残片）。 */
    private static final java.util.regex.Pattern FORMULA_FRAGMENT = java.util.regex.Pattern.compile(
            "^(中草药|中草|中草药处|草药|汤剂|颗粒剂|中成药)$");

    /** 含标点（抽取残句）。 */
    private static boolean hasPunct(String s) {
        return s != null && !s.isEmpty() && CONTAINS_PUNCT.matcher(s).find();
    }

    /** 是否非中药词素（化验指标 / 人体部位 / 食物）—— 用词根包含判定以覆盖截断变体。 */
    private static boolean isNonHerbTerm(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (String w : NON_HERB_TERMS) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 抽取残次品：脉诊手法 / 舌象子要素 / 病程诱因 / 六经裸名。
     *
     * 这四类都没有独立标准词可落点，作为实体入库只会污染标准化率。
     */
    private static boolean isExtractionArtifact(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (LIUJING_BARE.contains(s)) {
            return true;
        }
        for (String p : PULSE_MANUAL) {
            if (s.contains(p)) {
                return true;
            }
        }
        for (String p : TONGUE_ELEMENT) {
            if (s.contains(p)) {
                return true;
            }
        }
        for (String p : PROCESS_DESC_LEAD) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 丢弃噪声碎片实体（2026-10-19）。
     *
     * 实测（34154 份）：症状字段里 7354 条是「成形」「大便」「色黄」「晨起」这类
     * 单维描述或裸部位残词，既不可能归一、也不是一条症状发现；疾病字段另有 47 条
     * 「慢性」「过敏性」这种被截断的分级词。
     *
     * 只丢未归一的：已命中词典的实体一律保留 —— 判据是「归一失败 + 形状是碎片」，
     * 而不是「词形像碎片」。这样即使某个碎片恰好是合法标准词，也不会被误删。
     *
     * @return 丢弃条数
     */
    private int dropNoiseFragments(NlpExtractVO vo) {
        int n = 0;
        n += dropNoiseIn(vo.getSymptoms(), "symptom");
        n += dropNoiseIn(vo.getDiseases(), "disease");
        n += dropNoiseIn(vo.getTongueList(), "tongue");
        // 2026-10-21 新增：脉象（手法 / 含标点残句）、方剂（处方头 / 剂型残片）、
        // 中药（化验指标 / 含标点）—— 这几类此前完全没做噪声过滤，是未归一的主要来源之一。
        n += dropNoiseIn(vo.getPulseList(), "pulse");
        n += dropNoiseIn(vo.getFormulaList(), "formula");
        n += dropNoiseHerbs(vo);
        return n;
    }

    /** 中药的噪声过滤（herbs 用 name 而非 content，故不能复用 {@link #dropNoiseIn}）。 */
    private int dropNoiseHerbs(NlpExtractVO vo) {
        java.util.List<NlpExtractVO.Herb> list = vo.getHerbs();
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int before = list.size();
        list.removeIf(h -> h != null && h.getNormLevel() == null
                && isNoiseFragment("herb", rawOf(h.getName(), h.getSourceText())));
        return before - list.size();
    }

    private int dropNoiseIn(List<NlpExtractVO.Entity> list, String type) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int before = list.size();
        list.removeIf(e -> e != null && e.getNormLevel() == null
                && isNoiseFragment(type, rawOf(e.getContent(), e.getSourceText())));
        return before - list.size();
    }

    private static boolean isNoiseFragment(String type, String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        // 2026-10-21 跨类型通用判定：这两类无论落在哪个字段都不该是实体。
        // ① 含标点 / 引号 / 括号 —— 抽取把残句或整句带进来了；
        // ② 脉诊手法 / 舌象子要素 / 病程诱因 / 六经裸名 —— 都没有独立标准词可落点。
        if (hasPunct(s) || isExtractionArtifact(s)) {
            return true;
        }
        switch (type) {
            case "symptom":
                return NOISE_BARE.contains(s) || NOISE_DESC.matcher(s).matches();
            case "disease":
                return NOISE_SEVERITY.matcher(s).matches();
            case "tongue":
                return NOISE_TONGUE_BARE.contains(s);
            case "formula":
                return FORMULA_FRAGMENT.matcher(s).matches();
            case "herb":
                return isNonHerbTerm(s);
            case "pulse":
                // 手法与标点已由上方通用判定覆盖，脉象无其它噪声形态
                return false;
            default:
                return false;
        }
    }

    private List<NlpExtractVO.Entity> normEntities(List<NlpExtractVO.Entity> entities, String fieldKey, int[] stat, String orgId,
                                                   java.util.Set<String> scope) {
        // 1. 该字段没抽到东西就保持 null，别把 null 换成空列表
        if (entities == null) {
            return null;
        }
        // 2. 该字段对应哪类词典（舌/脉/病因/治法返回 null 已不成立：9 类均有词典，
        //    现返回的是字典类型 key；未纳入归一范围的类型由下方 scope 判定跳过）
        String type = dictionaryType(fieldKey);
        // 3. 逐个实体归一。拆解会一拆多，故另建结果表（不在遍历中改原表）
        List<NlpExtractVO.Entity> out = new ArrayList<>(entities.size());
        for (NlpExtractVO.Entity e : entities) {
            if (e == null) continue;
            String raw = rawSpan(e);
            if (raw.isEmpty()) continue;
            if (TextUtil.isBlank(e.getSourceText())) {
                e.setSourceText(raw);
            }
            // 3.5 清掉上一轮归一写回的痕迹（2026-10-19）：normLevel 只可能由归一写入，
            //     content 与 sourceText 不一致时说明 content 就是上一轮写回的标准词（含拆解产物）。
            //     重跑必须**从原文重新判定** —— 否则旧映射原样保留，撤回一条误映射的规则改动
            //     在存量数据上永远看不见（实测：R8 撤回「质软→脉软」后计数一度零变化）。
            //     抽解器自己做的同义映射（content=湿疮 / sourceText=湿疹，normLevel 为空）不在此列。
            if (e.getNormLevel() != null) {
                if (!TextUtil.isBlank(e.getSourceText()) && !e.getSourceText().equals(e.getContent())) {
                    e.setContent(e.getSourceText());
                }
                e.setNormLevel(null);
            }
            // 3.6 无独立词典，或该类型未纳入本组织「归一范围」→ 只保留原文（不写 normLevel）
            if (type == null || !scope.contains(type)) {
                out.add(e);
                continue;
            }
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize(type, orgId, raw);
            // 4. 未命中词典就保持原样，不写 normLevel。
            //    4.1 组合短语（脉位+脉质 / 舌质+苔质）整串无法命中时，尝试拆成若干标准词；
            //    包含命中同样尝试拆解：「脉细涩」按差额 1 会只归到「脉细」而丢掉「涩」，
            //    完整拆成「脉细+脉涩」才是原文的语义。拆解要求整串被词典项完整覆盖，
            //    任一残字都整体放弃（见 EsTermNormalizer#decompose）。
            boolean negated = r.level() == EsTermNormalizer.LEVEL_CONTAIN && isNegated(raw);
            boolean canDecompose = !EsTermNormalizer.isHit(r.level())
                    || (r.level() == EsTermNormalizer.LEVEL_CONTAIN && !negated);
            if (canDecompose) {
                List<EsTermNormalizer.NormalizeResult> parts = termNormalizer.decompose(type, orgId, raw);
                if (!parts.isEmpty()) {
                    for (EsTermNormalizer.NormalizeResult part : parts) {
                        NlpExtractVO.Entity split = new NlpExtractVO.Entity();
                        split.setContent(part.standardTerm());
                        split.setSourceText(raw);
                        split.setConfidence(e.getConfidence());
                        split.setSource(e.getSource());
                        split.setNormLevel(part.level());
                        out.add(split);
                        stat[0]++;
                        stat[part.level()]++;
                    }
                    continue;
                }
                // 拆不动：未命中就保持原样；包含命中则继续走下面的常规判定
                if (!EsTermNormalizer.isHit(r.level())) {
                    out.add(e);
                    continue;
                }
            }
            // 4.5 否定语境下的「包含命中」一律不归一。
            //
            // 二级·包含是双向包含（input.contains(std) || std.contains(input)），**没有长度保护**：
            // 「无明显流涎」会因含有「流涎」而命中，「带下已干净」「便前已无肠鸣」同理。
            // 这类句子是**对这个症状的否证**，归成症状后语义正好反过来 —— 病人明明说没有，
            // 结构化数据却记成有；更糟的是 QcScorer.countUnnormalized 把「无 normLevel」算未标准化，
            // 于是否证句被错误归一会**倒加分**，否证越多的病历反而显得越规范。
            //
            // 只拦包含命中（LEVEL_CONTAIN），不动精确与模糊：
            //   精确命中说明整串就是标准词或其别名，本身就自含否定语义（如「咽不红」在
            //   舌象词典里就是正式条目），拦了反而丢既有行为；
            //   模糊命中已有 Dice≥0.8 的长度约束，误伤面小。
            // 实测（35352 条真实病历）：拦住 4867 条否证/已愈句，干净命中 18874 条不受影响。
            if (r.level() == EsTermNormalizer.LEVEL_CONTAIN && isNegated(raw)) {
                negatedSkip.incrementAndGet();
                // 2026-10-19：否证句直接**不入库**（原先保留为「未归一实体」）。
                // 保留等于把一个不存在的症状记进结构化数据，还让它以「未归一」计入分母 ——
                // 既降低归一率又拉低质控分，等于对否证句倒扣分。语义上它不是一条发现。
                continue;
            }
            e.setContent(r.standardTerm());
            e.setNormLevel(r.level());
            stat[0]++;
            stat[r.level()]++;
            out.add(e);
        }
        // 5. 归一之后再合并同标准词。统计仍按「归一动作」计（去重前），
        // 即 NormStat 描述的是做了多少次归一，去重只影响下发给前端的列表。
        return dedupByTerm(out, NlpExtractVO.Entity::getContent,
                NlpExtractVO.Entity::getSourceText, NlpExtractVO.Entity::getNormLevel);
    }

    /**
     * 从原始字段回补抽取漏掉 / 截断的实体（§九 9.2 第 ④ 步）。
     *
     * 解决什么：NER + 规则兜底会把长词切短（本数据集 500 条里 100 条的
     * 「左尺无力」这类脉位被丢掉；处方里的中药名也常只出前一个字，如 "天"）。
     * 这些残词既归不上标准词、又占着列表，用户看到的是「一堆没归一的碎片」。
     * 处方与中医诊断这两列是原文必然存在的，实体却可能没被抽全 ——
     * 拿它们当「事实来源」回补，比调模型可靠。
     *
     * 处理顺序：① 剔除截断项 → ② append 完整词 → ③ 再走一次
     * {@link #normalize}（由它统一填 level / source / code 并按标准词去重）。
     * 刻意不在本方法里自己判命中层级：那是 EsTermNormalizer.judge 的职责，
     * 复制一份就会两处漂移。二次归一是幂等的（标准词本身是精确命中项）。
     *
     * 截断项判据：归一失败（无 normLevel）且 content/name 是某个
     * scan 命中词的真子串。刻意不用「长度为 1」这类启发式 ——
     * 单字本身可能就是合法的标准词（如药名 "姜"），按长度砍会误删。
     *
     * 异常触发：只在该字段确有未归一项时才动手。全部已归一就完全不调
     * scan，不给每次批量解析白加一次 ES 查询。
     *
     * @param vo           归一后的抽取结果，就地修改
     * @param prescription 原始处方列（可空）
     * @param tcmDiagnosis 原始中医诊断列（可空）
     * @param pattern      原始辨证结论列（可空），用于证候回补
     */
    public void backfillFromRaw(NlpExtractVO vo, String prescription, String tcmDiagnosis, String pattern) {
        backfillFromRaw(vo, com.tcm.ehr.common.utils.RequestUtils.currentOrgId(),
                prescription, tcmDiagnosis, null, pattern);
    }

    /** 组织级回填（旧签名，不含西医诊断：兼容既有调用与测试，西医列待新重载传入）*/
    public void backfillFromRaw(NlpExtractVO vo, String orgId, String prescription,
                                String tcmDiagnosis, String pattern) {
        backfillFromRaw(vo, orgId, prescription, tcmDiagnosis, null, pattern);
    }

    /** 组织级回填；批任务请传任务行上的 orgId 快照；westernDiagnosis 为西医诊断列 */
    public void backfillFromRaw(NlpExtractVO vo, String orgId, String prescription,
                                String tcmDiagnosis, String westernDiagnosis, String pattern) {
        if (vo == null) {
            return;
        }
        boolean touched = backfillHerbs(vo, orgId, prescription);
        touched |= backfillDiseases(vo, orgId, tcmDiagnosis, westernDiagnosis);
        touched |= backfillPatterns(vo, orgId, pattern);
        // 有回补就重跑一次归一：把新 append 的词按统一口径判层级、去重
        if (touched) {
            normalize(vo, orgId);
        }
    }

    /** 中药回补：目标字段是 Herb.name，原料是处方列 */
    private boolean backfillHerbs(NlpExtractVO vo, String orgId, String prescription) {
        if (TextUtil.isBlank(prescription)) {
            return false;
        }
        List<NlpExtractVO.Herb> herbs = vo.getHerbs();
        // 1. 异常触发：全部已归一就不必回补
        if (herbs != null && !herbs.isEmpty() && allNormalized(herbs, NlpExtractVO.Herb::getNormLevel)) {
            return false;
        }
        Set<String> hits = termNormalizer.scan("herb", orgId, prescription);
        if (hits.isEmpty()) {
            return false;
        }
        // 2. 剔除截断项：未归一 + name 是某命中词的真子串
        if (herbs != null) {
            herbs.removeIf(h -> h != null
                    && h.getNormLevel() == null
                    && isProperSubstringOfAny(h.getName(), hits));
        }
        // 3. append 已命中的完整词（已存在的按标准词去重交给 normalize）
        List<NlpExtractVO.Herb> target = herbs == null ? new ArrayList<>() : herbs;
        for (String std : hits) {
            NlpExtractVO.Herb h = new NlpExtractVO.Herb();
            h.setName(std);
            h.setSourceText(std);
            target.add(h);
        }
        vo.setHerbs(target);
        return true;
    }

    /** 疾病回补：目标字段是 Entity.content，原料是中医诊断列 */
    private boolean backfillDiseases(NlpExtractVO vo, String orgId, String tcmDiagnosis, String westernDiagnosis) {
        if (TextUtil.isBlank(tcmDiagnosis) && TextUtil.isBlank(westernDiagnosis)) {
            return false;
        }
        List<NlpExtractVO.Entity> diseases = vo.getDiseases();
        // 1. 异常触发
        if (diseases != null && !diseases.isEmpty() && allNormalized(diseases, NlpExtractVO.Entity::getNormLevel)) {
            return false;
        }
        // 疾病来源 = 中医诊断列 + 西医诊断列（质控 EntityTypes 的完整性来源即两列）
        // 两列都扫：西医诊断如 '睾丸炎' 也是疾病，之前漏扫导致该字段未命中
        Set<String> hits = new java.util.HashSet<>();
        if (!TextUtil.isBlank(tcmDiagnosis)) {
            hits.addAll(termNormalizer.scan("disease", orgId, tcmDiagnosis));
        }
        if (!TextUtil.isBlank(westernDiagnosis)) {
            hits.addAll(termNormalizer.scan("disease", orgId, westernDiagnosis));
        }
        if (hits.isEmpty()) {
            return false;
        }
        // 2. 剔除截断项
        if (diseases != null) {
            diseases.removeIf(e -> e != null
                    && e.getNormLevel() == null
                    && isProperSubstringOfAny(e.getContent(), hits));
        }
        // 3. append
        List<NlpExtractVO.Entity> target = diseases == null ? new ArrayList<>() : diseases;
        for (String std : hits) {
            NlpExtractVO.Entity e = new NlpExtractVO.Entity();
            e.setContent(std);
            e.setSourceText(std);
            target.add(e);
        }
        vo.setDiseases(target);
        return true;
    }

    /**
     * 证候回补：目标字段是 Entity.content，原料是辨证结论列（pattern）。
     *
     * 为什么需要它：证候只有模型一条来源（{@code python-nlp} 的规则兜底不含
     * patternList），实测 1000 条里 200 条模型漏抽 —— 而词典里那些词都在。
     * 中医诊断列有疾病回补、处方列有中药回补，辨证结论列同样该有。
     *
     * 用 scan 而不是按顿号裸切分：切分不查词典，会把「经络不通」这类
     * 非标准词原样塞进 patternList，反而制造未归一项（统计模块曾有一份这样的局部兜底）。
     */
    private boolean backfillPatterns(NlpExtractVO vo, String orgId, String pattern) {
        if (TextUtil.isBlank(pattern)) {
            return false;
        }
        List<NlpExtractVO.Entity> patterns = vo.getPatternList();
        // 1. 异常触发：全部已归一就不必回补
        if (patterns != null && !patterns.isEmpty() && allNormalized(patterns, NlpExtractVO.Entity::getNormLevel)) {
            return false;
        }
        Set<String> hits = termNormalizer.scan("pattern", orgId, pattern);
        if (hits.isEmpty()) {
            return false;
        }
        // 2. 剔除截断项：未归一 + content 是某命中词的真子串
        if (patterns != null) {
            patterns.removeIf(e -> e != null
                    && e.getNormLevel() == null
                    && isProperSubstringOfAny(e.getContent(), hits));
        }
        // 3. append 已命中的完整词
        List<NlpExtractVO.Entity> target = patterns == null ? new ArrayList<>() : patterns;
        for (String std : hits) {
            NlpExtractVO.Entity e = new NlpExtractVO.Entity();
            e.setContent(std);
            e.setSourceText(std);
            target.add(e);
        }
        vo.setPatternList(target);
        return true;
    }

    /** 是否每一项都已归一（normLevel 非空且在 1~3） */
    private <T> boolean allNormalized(List<T> items, Function<T, Integer> levelOf) {
        for (T item : items) {
            Integer lv = item == null ? null : levelOf.apply(item);
            if (lv == null || lv < 1 || lv > 3) {
                return false;
            }
        }
        return true;
    }

    /** s 是否为 hits 中某个词的真子串（相等不算，那是完整词本身） */
    private boolean isProperSubstringOfAny(String s, Set<String> hits) {
        if (TextUtil.isBlank(s)) {
            return false;
        }
        for (String hit : hits) {
            if (hit.length() > s.length() && hit.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** 归一目标值：优先 content，缺失时退回 sourceText（两者在模型侧同源） */
    /**
     * 归一原文：优先 sourceText（抽取时的原文 span），无则退 content。
     *
     * 为什么优先 sourceText（2026-10-19）：content 可能是上一轮归一写回的标准词
     * ——「仅归一」就地重跑时，拿 content 再归一等于自命中：
     * 首轮的模糊命中（{@code 大便成形→便溏}）会在第二轮被固化成精确命中，原文里的组合短语
     * （{@code 右寸关缓滑}）也永远拆不出来（content 已成「右寸」）。以原文为准则两轮幂等，
     * 且每次都用最新词典与拆解规则重新判定。
     *
     */
    private static String rawSpan(NlpExtractVO.Entity e) {
        if (e == null) {
            return "";
        }
        if (!TextUtil.isBlank(e.getSourceText())) {
            return e.getSourceText().trim();
        }
        if (!TextUtil.isBlank(e.getContent())) {
            return e.getContent().trim();
        }
        return "";
    }

    private String rawOf(String primary, String fallback) {
        // 1. 主值可用就用主值
        if (!TextUtil.isBlank(primary)) return primary.trim();
        // 2. 否则退回原文；两边都空给空串
        if (!TextUtil.isBlank(fallback)) return fallback.trim();
        return "";
    }

    /**
     * 该实体文本是否处于否定语境（症状不存在的陈述）。
     *
     * 只用于「二级·包含命中的拦截」，判据取病历里表达否证 / 已愈的固定说法：
     * 无、不、未、莫、非、缺、少见、偶见、未曾 等，以及「已 + 痊愈类词」。
     * 判定用**包含**而非前缀 —— 否定词常落在症状词前面而不是句首
     * （「便前已无肠鸣」「无夜寐流涎」的主语在更前面）。
     *
     * 为什么不用句法分析：这些实体是被 NER 切出来的短 span，
     * 没有完整句子结构可析；而且这里是命中后的兜底拦截，误拦的代价是
     * 「少归一一条」，误放的代价是「语义反转 + 质控倒加分」，故口径从紧。
     *
     * 已知代价（诚实记账）：「不饥」「不渴」这类**本身是否定式的规范症状**
     * （饥不欲食 / 口渴不欲饮）会被一起拦下。但它们在精确命中层不受影响
     * （词典里就是正式条目），只有被当包含命中捞到时才会被拦 —— 那种情况下
     * 归一结果本来就可疑。
     *
     * 2026-10-18 起判据迁到 {@link TermGuard#isNegatedContext(String)}：清洗链路也要用同一条，
     * 口径只写一处。本方法保留原签名、内部委托，既有调用点与测试不动。
     */
    static boolean isNegated(String raw) {
        return TermGuard.isNegatedContext(raw);
    }
}
