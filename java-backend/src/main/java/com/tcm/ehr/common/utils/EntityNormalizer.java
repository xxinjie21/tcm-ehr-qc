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
 * 实体术语归一：把抽取出的 9 类实体就地归一到国标标准术语。
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
        // 1. 没抽取出东西就不做归一
        if (vo == null) {
            return new NormStat(0, 0, 0, 0);
        }
        int[] stat = {0, 0, 0, 0};

        // 2. 8 类 Entity 走同一字段→类型映射；无词典的 4 类只回填 sourceText（供前端展示原文）
        vo.setDiseases(normEntities(vo.getDiseases(), "diseases", stat));
        vo.setSymptoms(normEntities(vo.getSymptoms(), "symptoms", stat));
        vo.setTongueList(normEntities(vo.getTongueList(), "tongueList", stat));
        vo.setPulseList(normEntities(vo.getPulseList(), "pulseList", stat));
        vo.setPatternList(normEntities(vo.getPatternList(), "patternList", stat));
        vo.setCauseList(normEntities(vo.getCauseList(), "causeList", stat));
        vo.setTreatmentList(normEntities(vo.getTreatmentList(), "treatmentList", stat));
        vo.setFormulaList(normEntities(vo.getFormulaList(), "formulaList", stat));

        // 3. 中药单独处理：归一目标是 name 而不是 content
        for (NlpExtractVO.Herb herb : vo.getHerbs()) {
            if (herb == null) continue;
            // 中药以 name 为归一目标，sourceText 保留原文（模型侧 name 与 sourceText 同源）
            String raw = rawOf(herb.getName(), herb.getSourceText());
            if (raw.isEmpty()) continue;
            if (TextUtil.isBlank(herb.getSourceText())) {
                herb.setSourceText(raw);
            }
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize("herb", raw);
            // 4. 没命中词典就保持原样（不写 normLevel，质控据此算"未标准化"）
            if (r.source() == null || r.source().isBlank() || r.level() < 1 || r.level() > 3) {
                continue;
            }
            herb.setName(r.standardTerm());
            herb.setNormLevel(r.level());
            herb.setNormSource(r.source());
            herb.setNormCode(r.code());
            stat[0]++;
            stat[r.level()]++;
        }
        // 5. 中药也按标准词去重
        vo.setHerbs(dedupByTerm(vo.getHerbs(), NlpExtractVO.Herb::getName,
                NlpExtractVO.Herb::getSourceText, NlpExtractVO.Herb::getNormLevel));

        return NormStat.of(stat);
    }

    private List<NlpExtractVO.Entity> normEntities(List<NlpExtractVO.Entity> entities, String fieldKey, int[] stat) {
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
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize(type, raw);
            // 4. 未命中词典就保持原样，不写 normLevel
            if (r.source() == null || r.source().isBlank() || r.level() < 1 || r.level() > 3) {
                continue;
            }
            e.setContent(r.standardTerm());
            e.setNormLevel(r.level());
            e.setNormSource(r.source());
            e.setNormCode(r.code());
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
     */
    public void backfillFromRaw(NlpExtractVO vo, String prescription, String tcmDiagnosis) {
        if (vo == null) {
            return;
        }
        boolean touched = backfillHerbs(vo, prescription);
        touched |= backfillDiseases(vo, tcmDiagnosis);
        // 1. 有回补就重跑一次归一：把新 append 的词按统一口径判层级、去重
        if (touched) {
            normalize(vo);
        }
    }

    /** 中药回补：目标字段是 {@code Herb.name}，原料是处方列 */
    private boolean backfillHerbs(NlpExtractVO vo, String prescription) {
        if (TextUtil.isBlank(prescription)) {
            return false;
        }
        List<NlpExtractVO.Herb> herbs = vo.getHerbs();
        // 1. 异常触发：全部已归一就不必回补
        if (herbs != null && !herbs.isEmpty() && allNormalized(herbs, NlpExtractVO.Herb::getNormLevel)) {
            return false;
        }
        Set<String> hits = termNormalizer.scan("herb", prescription);
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
    private boolean backfillDiseases(NlpExtractVO vo, String tcmDiagnosis) {
        if (TextUtil.isBlank(tcmDiagnosis)) {
            return false;
        }
        List<NlpExtractVO.Entity> diseases = vo.getDiseases();
        // 1. 异常触发
        if (diseases != null && !diseases.isEmpty() && allNormalized(diseases, NlpExtractVO.Entity::getNormLevel)) {
            return false;
        }
        Set<String> hits = termNormalizer.scan("disease", tcmDiagnosis);
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
