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
 * <p><b>与清洗链路的分工</b>：本类服务于<b>解析链路</b>（`POST /api/nlp/extract` 返回前即时归一），
 * 目的是让用户在解析页当场看到「原文 → 标准词」的对照，而不是等到清洗阶段才知道术语是否规范。
 * 清洗链路（{@code GovernanceServiceImpl.normalizeStructuredData}）仍保留<b>兜底补归一</b>——
 * 因为病历可能来自导入、手工新增等未经解析页的入口。</p>
 *
 * <p><b>为什么不会重复归一</b>：两处都只做「原文 → 标准词」的单向替换，标准词本身在词典中
 * 是精确命中项，二次归一得到的仍是同一标准词；清洗链路另有「标准词与原值不同才计数」的判据，
 * 因此已归一实体不会被重复计入统计。</p>
 *
 * <p><b>为什么不下推判定到 ES</b>：与 {@link EsTermNormalizer} 一致——ES 只负责召回候选，
 * 精确 / 包含 / 字符 Dice≥0.8 三级判定仍在 Java 侧完成。</p>
 *
 * <p><b>同标准词去重</b>：归一本身会把不同原文折叠到同一标准词上——原文里同时有
 * 「嗳气」与「嗳气频作」时，后者按「包含命中」也归成「嗳气」，于是列表里出现两个一模一样的
 * 「嗳气」，用户会以为系统出错。{@link #dedupByTerm} 是<b>唯一去重口径</b>，解析链路与清洗链路
 * 共用（与 {@link #dictionaryType} 同一思路：口径只写一处，避免两处漂移）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityNormalizer {

    private final EsTermNormalizer termNormalizer;
    /** {@link #normalizeMap} 需要在 Map 与 VO 之间转换；显式注入而不是 new 出来 */
    private final tools.jackson.databind.ObjectMapper objectMapper;

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
     * <p> 起取自 {@link com.tcm.ehr.common.config.EntityTypes}：只有"有词典"的类型参与归一，
     * 舌象/脉象/病因/治法无独立词典，返回 {@code null}。</p>
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
     * <p><b>去重范围＝调用方给的那一个列表</b>，即<b>字段内</b>，不跨字段——「风寒」同时出现在
     * 疾病与病因是两个不同的语义槽位，各自保留。</p>
     *
     * <p><b>保留哪一条</b>（依次比较，先满足者胜）：
     * ① {@code sourceText} 更长优先——原文更完整（「嗳气频作」优于「嗳气」），
     * 且「原文 → 标准词」的对照才看得见，否则归一结果看起来什么都没发生；
     * ② 等长时 {@code normLevel} 更小优先（1 精确 &lt; 2 包含 &lt; 3 模糊 &lt; 未命中）；
     * ③ 再相等时保留<b>先出现</b>的那条（{@link LinkedHashMap} 保序，重复键 put 不改位置）。</p>
     *
     * <p>代表条目<b>整条</b>保留自己的 confidence，不跨条取最大值——置信度描述的是它那条 span，
     * 拼装会让数字与展示的原文对不上。</p>
     *
     * <p>术语键为空的条目<b>不参与合并</b>（各自用唯一键占位）——否则所有缺 content 的条目
     * 会被并成一条。</p>
     *
     * @param items 待去重列表，可为 null
     * @param termOf 取「归一后标准词」；为空则该条不参与合并
     * @param sourceOf 取「归一前原文」，用于比较完整性
     * @param levelOf 取「命中层级」，可为 null（未命中）
     * @return 去重后的新列表；{@code items} 为 null 或不足 2 条时原样返回
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
     * 组织级归一：按 {@code orgId} 限定的词典范围（基础层 + 该组织）召回。
     *
     * <p><b>批任务必须传任务行上的 orgId 快照</b>，不能走无参版：worker 线程没有
     * RequestContext，{@code currentOrgId()} 会拿到空串 → 静默只查基础层，
     * 组织自定义词条对批任务完全失效，而且不报错。</p>
     */
    /**
     * 组织级归一（就地改 {@code structured_data} 的 Map 形态）。
     *
     * <p><b>用途</b>：人工复核提交修正后写回前用。复核员<b>新输入</b>的词只带
     * {@code content/name}，没有 {@code normLevel}；而 {@link QcScorer#countUnnormalized}
     * 把「无 normLevel」算作未标准化 → <b>人工修正反而扣分</b>（weightEach=1、封顶 5）。
     * 这里在写回前跑一遍与模型抽取完全相同的归一，口径统一。</p>
     *
     * <p><b>为什么不在 Map 与 VO 之间整体来回转换</b>：{@code NlpExtractVO} 里没有
     * {@code _meta} / {@code modelAvailable} / {@code truncated}，整体转换会丢键，
     * 也会给未赋值的属性写出 {@code null} 键（JSON 变胖）。所以这里只把 9 个实体列表
     * 与 {@code herbs} 的归一结果<b>写回原 Map</b>，其余键原样保留。</p>
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
        return stat;
    }

    /**
     * 把误放进 {@code symptoms} 的脉象/舌象实体挪到各自字段（<b>词典驱动，不看词形</b>）。

    *
    * <p><b>归属约定（P2-11，2026-10-05）</b>：实体类型的<b>源头判定在抽取侧</b>（python-nlp 的抽取服务）；
    * 本方法只是<b>兜底</b> —— 用于两类情形：① 历史数据里已经错分的实体；② 抽取侧判错时的补救。
    * 它<b>不应成为常态</b>：若统计或日志显示本方法频繁生效，正确做法是修抽取侧的类型判定，
    * 而不是继续在这里加规则（Java 侧只做校验与兜底，不承担类型判定职责）。</p>
     *
     * <p>为什么用词典命中而不是词形匹配：像「酸软无力」这种真正的症状也含「无力」，
     * 按词形挪会误伤它（实测确有 50 条「酸软无力」）。判据与中药那段一致 ——
     * 归一命中词典即视为「本类型术语」，{@link EsTermNormalizer.NormalizeResult#source()} 非空。</p>
     *
     * <p>顺序：先试脉象、未命中再试舌象（两次 ES 查询只在确有症状实体时才发生，
     * 且第二次靠短路避免无谓查询）。命中的实体带着原 content/sourceText 挪过去，
     * 随后的 {@code normEntities} 会照常给它们打上 normLevel。</p>
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
                if (hitsDictionary("pulse", orgId, raw)) {
                    vo.getPulseList().add(e);
                    moved++;
                    continue;
                }
                if (hitsDictionary("tongue", orgId, raw)) {
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
     * <p>实测：症状字段里长度 1 的实体 135 条、病因 50 条，内容是 `双`/`腰`/`失`/`口`/`眼`/`下`/`遇`/`胸`/`心`
     * 这类截断残字（来自「双下肢水肿」「腰酸」「失眠」等）。它们必然未归一，既拉低归一率，
     * 又让复核员在列表里看到一堆无意义的字。</p>
     *
     * <p>判据：<b>长度为 1 且不在该类型词典里</b>才丢。为什么不是「长度为 1 就丢」——
     * 词典里确有 5 个单字标准词（如单字术语），一刀切会误删合法术语；
     * 而「在词典里」的单字能正常归一，本就不该丢。无词典的类型（4 类无词典实体）
     * 因为没有可依据的词典，按残词处理（实测那几类里同样只有截断字）。</p>
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
            return r != null && r.level() >= EsTermNormalizer.LEVEL_EXACT
                    && r.level() <= EsTermNormalizer.LEVEL_FUZZY;
        } catch (Exception ex) {
            // 词典/ES 不可用时**不挪**：宁可少归位，也不能凭猜把症状改成脉象
            log.warn("[归一] 归位判定失败（type={}）: {}", type, ex.getMessage());
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
     * <p>不改判定、只预热：{@code EsTermNormalizer.prefetch} 走 {@code _msearch} 一次往返，
     * 并把结果写进与单条 search 相同的缓存键，随后的逐条归一因此全部命中缓存。</p>
     *
     * <p>类型 key 由 structuredKey 反查 {@link com.tcm.ehr.common.config.EntityTypes} 得到 ——
     * 复用那一份登记，不在本类里再写一张「字段名→类型」的表。</p>
     */
    private void prefetchRecall(NlpExtractVO vo, String orgId) {
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
            if (t != null) {
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
            String raw = rawOf(e.getContent(), e.getSourceText());
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

        // 1.4 预取召回（批次12 · 12b）：归位与丢弃都会改动列表，所以放在它们**之后**、
        //     逐条归一**之前** —— 这样预取的正是最终要归一的那些术语，且只发一次 ES 往返。
        prefetchRecall(vo, orgId);

        // 1.5 归位（2026-10-05 修 #2）：抽取侧有时把脉象/舌象内容打进 symptoms ——
        //     实测症状字段 4170 条里有 107 条的内容**恰是脉象词典里的标准词**
        //     （「脉细数」「左尺无力」「脉弦劲有力」「脉浮」）。不归位的话它们会以
        //     「未归一的症状」计入分母，既拉低症状归一率，也可能影响完整性判定。
        moveMisplacedPulseTongue(vo, orgId);
        // 1.6 丢弃单字残词（2026-10-05 修 #6）：实测症状/病因里有 135/50 条长度为 1 的实体
        //     （`双`50 `腰`34 `失`22 `口`10 `眼`9 …），全是「双下肢水肿」「腰酸」「失眠」这类
        //     被截断的残字。判据同样是**词典驱动**：长度为 1 **且不在该类型词典里**才丢 ——
        //     词典里确有 5 个单字标准词，一刀切会误删合法术语（本批数据里这类为 0，但规则要立对）。
        dropSingleCharFragments(vo, orgId);

        // 2. 8 类 Entity 走同一字段→类型映射；无词典的 4 类只回填 sourceText（供前端展示原文）
        vo.setDiseases(normEntities(vo.getDiseases(), "diseases", stat, orgId));
        vo.setSymptoms(normEntities(vo.getSymptoms(), "symptoms", stat, orgId));
        vo.setTongueList(normEntities(vo.getTongueList(), "tongueList", stat, orgId));
        vo.setPulseList(normEntities(vo.getPulseList(), "pulseList", stat, orgId));
        vo.setPatternList(normEntities(vo.getPatternList(), "patternList", stat, orgId));
        vo.setCauseList(normEntities(vo.getCauseList(), "causeList", stat, orgId));
        vo.setTreatmentList(normEntities(vo.getTreatmentList(), "treatmentList", stat, orgId));
        vo.setFormulaList(normEntities(vo.getFormulaList(), "formulaList", stat, orgId));

        // 3. 中药单独处理：归一目标是 name 而不是 content
        for (NlpExtractVO.Herb herb : vo.getHerbs()) {
            if (herb == null) continue;
            // 中药以 name 为归一目标，sourceText 保留原文（模型侧 name 与 sourceText 同源）
            String raw = rawOf(herb.getName(), herb.getSourceText());
            if (raw.isEmpty()) continue;
            if (TextUtil.isBlank(herb.getSourceText())) {
                herb.setSourceText(raw);
            }
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize("herb", orgId, raw);
            // 4. 没命中词典就保持原样（不写 normLevel，质控据此算"未标准化"）
            if (r.level() < EsTermNormalizer.LEVEL_EXACT || r.level() > EsTermNormalizer.LEVEL_FUZZY) {
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

        return NormStat.of(stat);
    }

    private List<NlpExtractVO.Entity> normEntities(List<NlpExtractVO.Entity> entities, String fieldKey, int[] stat, String orgId) {
        // 1. 该字段没抽到东西就保持 null，别把 null 换成空列表
        if (entities == null) {
            return null;
        }
        // 2. 该字段对应哪类词典（舌/脉/病因/治法返回 null）
        String type = dictionaryType(fieldKey);
        // 3. 逐个实体归一
        for (NlpExtractVO.Entity e : entities) {
            if (e == null) continue;
            String raw = rawOf(e.getContent(), e.getSourceText());
            if (raw.isEmpty()) continue;
            if (TextUtil.isBlank(e.getSourceText())) {
                e.setSourceText(raw);
            }
            if (type == null) {
                continue; // 该字段无独立词典，仅保留原文
            }
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize(type, orgId, raw);
            // 4. 未命中词典就保持原样，不写 normLevel
            if (r.level() < EsTermNormalizer.LEVEL_EXACT || r.level() > EsTermNormalizer.LEVEL_FUZZY) {
                continue;
            }
            e.setContent(r.standardTerm());
            e.setNormLevel(r.level());
            stat[0]++;
            stat[r.level()]++;
        }
        // 5. 归一之后再合并同标准词。统计仍按「归一动作」计（去重前），
        // 即 NormStat 描述的是做了多少次归一，去重只影响下发给前端的列表。
        return dedupByTerm(entities, NlpExtractVO.Entity::getContent,
                NlpExtractVO.Entity::getSourceText, NlpExtractVO.Entity::getNormLevel);
    }

    /**
     * 从原始字段回补抽取漏掉 / 截断的实体（§九 9.2 第 ④ 步）。
     *
     * <p><b>解决什么</b>：NER + 规则兜底会把长词切短（本数据集 500 条里 100 条的
     * 「左尺无力」这类脉位被丢掉；处方里的中药名也常只出前一个字，如 "天"）。
     * 这些残词既归不上标准词、又占着列表，用户看到的是「一堆没归一的碎片」。
     * 处方与中医诊断这两列是<b>原文必然存在</b>的，实体却可能没被抽全 ——
     * 拿它们当「事实来源」回补，比调模型可靠。</p>
     *
     * <p><b>处理顺序</b>：① 剔除截断项 → ② append 完整词 → ③ 再走一次
     * {@link #normalize}（由它统一填 level / source / code 并按标准词去重）。
     * 刻意<b>不</b>在本方法里自己判命中层级：那是 {@link EsTermNormalizer#judge} 的职责，
     * 复制一份就会两处漂移。二次归一是幂等的（标准词本身是精确命中项）。</p>
     *
     * <p><b>截断项判据</b>：归一失败（无 normLevel）<b>且</b> content/name 是某个
     * {@code scan} 命中词的<b>真子串</b>。刻意<b>不用</b>「长度为 1」这类启发式 ——
     * 单字本身可能就是合法的标准词（如药名 "姜"），按长度砍会误删。</p>
     *
     * <p><b>异常触发</b>：只在该字段<b>确有未归一项</b>时才动手。全部已归一就完全不调
     * {@code scan}，不给每次批量解析白加一次 ES 查询。</p>
     *
     * @param vo           归一后的抽取结果，就地修改
     * @param prescription 原始处方列（可空）
     * @param tcmDiagnosis 原始中医诊断列（可空）
     * @param pattern      原始辨证结论列（可空），用于证候回补
     */
    public void backfillFromRaw(NlpExtractVO vo, String prescription, String tcmDiagnosis, String pattern) {
        backfillFromRaw(vo, com.tcm.ehr.common.utils.RequestUtils.currentOrgId(),
                prescription, tcmDiagnosis, pattern);
    }

    /** 组织级回填；批任务请传任务行上的 orgId 快照 */
    public void backfillFromRaw(NlpExtractVO vo, String orgId, String prescription,
                                String tcmDiagnosis, String pattern) {
        if (vo == null) {
            return;
        }
        boolean touched = backfillHerbs(vo, orgId, prescription);
        touched |= backfillDiseases(vo, orgId, tcmDiagnosis);
        touched |= backfillPatterns(vo, orgId, pattern);
        // 1. 有回补就重跑一次归一：把新 append 的词按统一口径判层级、去重
        if (touched) {
            normalize(vo);
        }
    }

    /** 中药回补：目标字段是 {@code Herb.name}，原料是处方列 */
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

    /** 疾病回补：目标字段是 {@code Entity.content}，原料是中医诊断列 */
    private boolean backfillDiseases(NlpExtractVO vo, String orgId, String tcmDiagnosis) {
        if (TextUtil.isBlank(tcmDiagnosis)) {
            return false;
        }
        List<NlpExtractVO.Entity> diseases = vo.getDiseases();
        // 1. 异常触发
        if (diseases != null && !diseases.isEmpty() && allNormalized(diseases, NlpExtractVO.Entity::getNormLevel)) {
            return false;
        }
        Set<String> hits = termNormalizer.scan("disease", orgId, tcmDiagnosis);
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
     * 证候回补：目标字段是 {@code Entity.content}，原料是辨证结论列（{@code pattern}）。
     *
     * <p><b>为什么需要它</b>：证候只有模型一条来源（{@code python-nlp} 的规则兜底不含
     * patternList），实测 1000 条里 200 条模型漏抽 —— 而词典里那些词都在。
     * 中医诊断列有疾病回补、处方列有中药回补，辨证结论列同样该有。</p>
     *
     * <p>用 {@code scan} 而不是按顿号裸切分：切分不查词典，会把「经络不通」这类
     * 非标准词原样塞进 patternList，反而制造未归一项（统计模块曾有一份这样的局部兜底）。</p>
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

    /** s 是否为 hits 中某个词的<b>真</b>子串（相等不算，那是完整词本身） */
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
    private String rawOf(String primary, String fallback) {
        // 1. 主值可用就用主值
        if (!TextUtil.isBlank(primary)) return primary.trim();
        // 2. 否则退回原文；两边都空给空串
        if (!TextUtil.isBlank(fallback)) return fallback.trim();
        return "";
    }
}
