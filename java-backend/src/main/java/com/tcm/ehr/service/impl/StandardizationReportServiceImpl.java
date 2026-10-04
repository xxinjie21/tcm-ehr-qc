package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.config.EntityTypes;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.StandardizationReportVO;
import com.tcm.ehr.service.DictionaryTermStore;
import com.tcm.ehr.service.IStandardizationReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标准化质量报告实现（批次 24）。
 *
 *
 * 只读：只 select，不写库、不重建索引、不触发质控重算。
 *
 *
 *
 * 为什么报告要分甲乙两层：甲类只取决于词表建得对不对（与数据无关），
 *
 * 乙类取决于这批数据长什么样。500 条是 10 个模板生成的合成数据，乙类只能当下限用；
 * 混在一张表里，读者会把「模板数据的归一率」当成「真实病历的准确率」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardizationReportServiceImpl implements IStandardizationReportService {

    /** 抽取碎片的判定：长度 ≤ 2 的残词（归一不可能命中标准词） */
    private static final int FRAGMENT_MAX_LEN = 2;
    /** 分类错放的前缀：脉/舌 要素不该出现在症状里 */
    private static final String[] MISROUTED_PREFIX = {"脉", "舌"};
    /** 体征错放的关键字：属体征而非症状 */
    private static final String[] PHYSICAL_SIGN = {"压痛", "触痛", "叩痛", "反跳痛"};
    /** 有意保留在页面上的声明，防止报告被当成科研级准确率引用 */
    private static final String DISCLAIMER =
            "本报告基于现有数据的实测统计。乙类（数据集覆盖度）只用于验证词表是否建全，"
            + "不代表真实病历上的准确率；甲类（标准符合度）才是词表质量的目标口径。";

    private final com.tcm.ehr.mapper.RecordMapper recordMapper;
    private final DictionaryTermStore termStore;
    /** 与 StatsServiceImpl 同一个注入实例，不另建 —— 每次 new ObjectMapper 会有可观的初始化开销 */
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Override
    public StandardizationReportVO report() {
        StandardizationReportVO vo = new StandardizationReportVO();
        vo.setDictQuality(dictQuality());
        vo.setCrossTypeDuplicates(crossTypeDuplicates());
        // 乙类：病历相关，必须先按数据域收窄，否则登录即可的接口会读到跨组织数据
        List<Record> records = recordsInDomain();
        vo.setCoverage(coverage(records));
        vo.setUnmatched(unmatched(records, new HashSet<>(symptomTerms())));
        vo.setNormalizable(normalizableRate(records, new HashSet<>(symptomTerms())));
        vo.setScore(scoreDistribution(records));
        vo.setDataset(datasetShape(records));
        vo.setDisclaimer(DISCLAIMER);
        vo.setGeneratedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return vo;
    }

    // ------------------------------------------------------------ 甲类 · 词典质量

    /** 各类型词表的规模、编码覆盖、别名质量 */
    private List<StandardizationReportVO.DictQuality> dictQuality() {
        String orgId = RequestUtils.currentOrgId();
        List<StandardizationReportVO.DictQuality> out = new ArrayList<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            if (!t.dict()) {
                continue;
            }
            List<TermEntry> entries = termStore.readEffective(orgId, t.key());
            StandardizationReportVO.DictQuality q = new StandardizationReportVO.DictQuality();
            q.setType(t.key());
            q.setLabel(t.label());
            q.setTermCount(entries.size());
            int coded = 0;
            int aliased = 0;
            int selfAlias = 0;
            Set<String> sources = new LinkedHashSet<>();
            for (TermEntry e : entries) {
                if (e.getCode() != null && !e.getCode().isBlank()) {
                    coded++;
                }
                if (e.getAliases() != null && !e.getAliases().isEmpty()) {
                    aliased++;
                    // 别名与标准词相同会在归一时自命中，属数据缺陷
                    if (e.getAliases().contains(e.getStandardTerm())) {
                        selfAlias++;
                    }
                }
                if (e.getSource() != null && !e.getSource().isBlank()) {
                    sources.add(e.getSource());
                }
            }
            q.setCodedCount(coded);
            q.setAliasedCount(aliased);
            q.setSelfAliasCount(selfAlias);
            // 来源可能分散（如疾病来自国标、中药来自药典），取第一段做代表
            q.setSource(sources.isEmpty() ? "" : sources.iterator().next());
            out.add(q);
        }
        return out;
    }

    /** 跨类重名：同一术语出现在多本词典里，类型判定会有歧义 */
    private List<String> crossTypeDuplicates() {
        String orgId = RequestUtils.currentOrgId();
        Map<String, Set<String>> termToTypes = new LinkedHashMap<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            if (!t.dict()) {
                continue;
            }
            for (TermEntry e : termStore.readEffective(orgId, t.key())) {
                termToTypes.computeIfAbsent(e.getStandardTerm(), k -> new LinkedHashSet<>()).add(t.key());
            }
        }
        List<String> dup = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : termToTypes.entrySet()) {
            if (e.getValue().size() > 1) {
                dup.add(e.getKey());
            }
        }
        return dup;
    }

    // ------------------------------------------------------------ 乙类 · 数据集覆盖

    /** 数据域内的病历（管理员看全部，其余只看本组织） */
    private List<Record> recordsInDomain() {
        return recordMapper.selectList(RecordFilter.build(
                RecordFilter.domainOrgId(), new com.tcm.ehr.domain.dto.FiltersDTO()));
    }

    /** 各类实体：抽取数 / 已归一数 */
    private List<StandardizationReportVO.TypeCoverage> coverage(List<Record> records) {
        Map<String, StandardizationReportVO.TypeCoverage> byField = new LinkedHashMap<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            StandardizationReportVO.TypeCoverage c = new StandardizationReportVO.TypeCoverage();
            c.setField(t.structuredKey());
            c.setLabel(t.label());
            byField.put(t.structuredKey(), c);
        }
        for (Record r : records) {
            Map<String, Object> sd = structured(r);
            if (sd == null) {
                continue;
            }
            byField.values().forEach(c -> countEntities(c, sd.get(c.getField())));
        }
        return new ArrayList<>(byField.values());
    }

    @SuppressWarnings("unchecked")
    private void countEntities(StandardizationReportVO.TypeCoverage c, Object raw) {
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            c.setTotal(c.getTotal() + 1);
            if (m.get("normLevel") != null) {
                c.setNormalized(c.getNormalized() + 1);
            }
        }
    }

    /**
     * 症状未归一实体的四类构成；责任方不同，不能合并看。
     *
     *
     * 判定顺序要紧：具名错放先于长度判定。像「压痛」这种体征只有 2 个字，
     *
     * 若先按「长度 ≤ 2 = 抽取碎片」判，它会被归到碎片里 —— 但它其实是被完整抽出来的，
     * 只是被放错了数组。归错责会让修复方向跑偏（去改抽取截断，而不是改分类路由）。
     */
    private StandardizationReportVO.UnmatchedBreakdown unmatched(List<Record> records, Set<String> symptomDict) {
        StandardizationReportVO.UnmatchedBreakdown b = new StandardizationReportVO.UnmatchedBreakdown();
        for (Record r : records) {
            Map<String, Object> sd = structured(r);
            if (sd == null || !(sd.get("symptoms") instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m) || m.get("normLevel") != null) {
                    continue;
                }
                String content = str(m.get("content"));
                if (content.isEmpty()) {
                    content = str(m.get("sourceText"));
                }
                b.setTotal(b.getTotal() + 1);
                // ① 具名错放优先：含体征关键字，或以脉/舌开头
                boolean namedMisroute = containsAny(content, PHYSICAL_SIGN)
                        || startsWithAny(content, MISROUTED_PREFIX);
                if (namedMisroute) {
                    if (containsAny(content, PHYSICAL_SIGN)) {
                        b.setPhysicalSign(b.getPhysicalSign() + 1);
                    } else {
                        b.setMisrouted(b.getMisrouted() + 1);
                    }
                } else if (content.length() <= FRAGMENT_MAX_LEN) {
                    // ② 剩下的短词才是抽取碎片
                    b.setFragment(b.getFragment() + 1);
                } else {
                    // ③ 多为标准词但词表没有 → 词表侧
                    b.setDictionaryGap(b.getDictionaryGap() + 1);
                }
            }
        }
        return b;
    }

    /**
     * 可归一实体归一率。
     *
     *
     * 分母只算「已抽取 + 非抽取缺陷 + 已在症状词表内」：词表里根本没有的标准词
     *
     * （如 神疲乏力）是词表缺口本身，算进分母会把「补词表能改善多少」这个信号抹掉。
     */
    private StandardizationReportVO.NormalizableRate normalizableRate(List<Record> records, Set<String> symptomDict) {
        StandardizationReportVO.NormalizableRate r = new StandardizationReportVO.NormalizableRate();
        for (Record rec : records) {
            Map<String, Object> sd = structured(rec);
            if (sd == null || !(sd.get("symptoms") instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String content = str(m.get("content"));
                if (content.isEmpty()) {
                    continue;
                }
                boolean defect = startsWithAny(content, MISROUTED_PREFIX)
                        || containsAny(content, PHYSICAL_SIGN)
                        || content.length() <= FRAGMENT_MAX_LEN;
                if (defect || !isInDict(content, symptomDict)) {
                    continue;
                }
                r.setDenominator(r.getDenominator() + 1);
                if (m.get("normLevel") != null) {
                    r.setNumerator(r.getNumerator() + 1);
                }
            }
        }
        return r;
    }

    /** 质控分数分布与封顶率；封顶率高说明评分失去区分度 */
    private StandardizationReportVO.ScoreDistribution scoreDistribution(List<Record> records) {
        StandardizationReportVO.ScoreDistribution d = new StandardizationReportVO.ScoreDistribution();
        int sum = 0;
        int scored = 0;
        for (Record r : records) {
            if (r.getScore() == null) {
                continue;
            }
            d.setTotal(d.getTotal() + 1);
            sum += r.getScore();
            scored++;
            if (d.getMin() == 0 || r.getScore() < d.getMin()) {
                d.setMin(r.getScore());
            }
            if (r.getScore() > d.getMax()) {
                d.setMax(r.getScore());
            }
            // 术语未标准化扣满 5 分（QcRuleSet 的 cap）即封顶
            Map<String, Object> qc = parseJson(r.getQcResults());
            if (qc != null && deductionsCapped(qc)) {
                d.setCapped(d.getCapped() + 1);
            }
        }
        if (scored > 0) {
            d.setAvg(Math.round(sum * 10.0 / scored) / 10.0);
        }
        return d;
    }

    /** 数据集形态：模板塌缩度决定这批数据能不能代表真实病历 */
    private StandardizationReportVO.DatasetShape datasetShape(List<Record> records) {
        StandardizationReportVO.DatasetShape s = new StandardizationReportVO.DatasetShape();
        s.setRecordCount(records.size());
        Set<String> chiefTemplates = new HashSet<>();
        int colloquial = 0;
        for (Record r : records) {
            String chief = r.getChiefComplaint();
            if (chief != null && !chief.isBlank()) {
                // 主诉里的病程月数每条不同，去掉数字才能看出模板数
                chiefTemplates.add(chief.replaceAll("\\d+", "N"));
            }
            if (hasColloquialUnmatchedSymptom(r)) {
                colloquial++;
            }
        }
        s.setChiefComplaintTemplates(chiefTemplates.size());
        s.setRecordsWithColloquialSymptom(colloquial);
        return s;
    }

    /** 症状归一失败的实体，其原文出现在自述字段 → 来自患者口语，标准词表本就不收 */
    private boolean hasColloquialUnmatchedSymptom(Record r) {
        String selfReport = r.getSelfReport();
        if (selfReport == null || selfReport.isBlank()) {
            return false;
        }
        Map<String, Object> sd = structured(r);
        if (sd == null || !(sd.get("symptoms") instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || m.get("normLevel") != null) {
                continue;
            }
            String sourceText = str(m.get("sourceText"));
            if (!sourceText.isEmpty() && selfReport.contains(sourceText)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 工具

    private Set<String> symptomTerms() {
        Set<String> terms = new HashSet<>();
        for (TermEntry e : termStore.readEffective(RequestUtils.currentOrgId(), "symptom")) {
            terms.add(e.getStandardTerm());
            if (e.getAliases() != null) {
                terms.addAll(e.getAliases());
            }
        }
        return terms;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> structured(Record r) {
        if (r.getStructuredData() == null || r.getStructuredData().isBlank()) {
            return null;
        }
        return parseJson(r.getStructuredData());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            // 单条脏数据不该让整份报告挂掉；计数上它会因为 parsed==null 被自然跳过
            log.debug("[报告] 解析 JSON 失败，跳过该条: {}", e.getMessage());
            return null;
        }
    }

    /** 术语未标准化扣分是否撞到 cap（QcRuleSet.standardization.cap = 5） */
    @SuppressWarnings("unchecked")
    private boolean deductionsCapped(Map<String, Object> qc) {
        if (!(qc.get("deductions") instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m
                    && "术语未标准化".equals(m.get("type"))
                    && m.get("points") instanceof Number n
                    && n.doubleValue() >= 5.0) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String text, String[] prefixes) {
        for (String p : prefixes) {
            if (text.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 文本是否包含任一关键字（用于体征这类「以…痛」形式出现的错放） */
    private boolean containsAny(String text, String[] keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInDict(String content, Set<String> dict) {
        return dict.contains(content);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}