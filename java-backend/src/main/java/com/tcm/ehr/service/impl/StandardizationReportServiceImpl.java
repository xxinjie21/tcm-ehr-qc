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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
        return report(null, null);
    }

    /**
     * 按接诊时间区间出报告。
     *
     * @param start 起（yyyy-MM-dd），为空表示不限
     * @param end   止（yyyy-MM-dd），为空表示不限；与 start 必须同时给或同时不给
     */
    @Override
    public StandardizationReportVO report(String start, String end) {
        // 批次12（12d）：清掉上一次请求的解析缓存（Tomcat 线程池会复用线程）
        structuredMemo.get().clear();
        StandardizationReportVO vo = new StandardizationReportVO();
        vo.setDictQuality(dictQuality());
        vo.setCrossTypeDuplicates(crossTypeDuplicates());
        // 乙类：病历相关，必须先按数据域收窄，否则登录即可的接口会读到跨组织数据
        // 批次12（12d）：报告全部八段都已改走库内聚合，这里不再需要把整表拉进 JVM。
        // 原先的 recordsInDomain() 取数 + filterByVisitTime 过滤是纯浪费：3.5 万行查出来、
        // 传进 JVM，然后没有任何消费者（八个消费方逐个迁移后留下的空壳）。
        // 区间口径没有丢：rangeOf/byMonth/coverage/unmatched 都由 SQL 承担同样的时间边界。
        vo.setRange(rangeOf(start, end));
        // 批次12（12d）：时间边界统一在此算好，供后面各段（按月/覆盖/未归一/评分质控）共用
        java.time.LocalDateTime from = null;
        java.time.LocalDateTime to = null;
        if (notBlankDate(start) && notBlankDate(end)) {
            from = LocalDate.parse(start).atStartOfDay();
            to = LocalDate.parse(end).plusDays(1).atStartOfDay();
        }
        vo.setByMonth(byMonth(from, to));
        vo.setCoverage(coverage(from, to));
        vo.setUnmatched(unmatched(from, to));
        vo.setNormalizable(normalizableRate(from, to));
        vo.setScore(scoreDistribution(from, to));
        vo.setQc(qcCoverage(from, to));
        vo.setDataset(datasetShape(from, to));
        vo.setDisclaimer(DISCLAIMER);
        vo.setGeneratedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return vo;
    }

    /**
     * 按接诊时间过滤。
     *
     *
     * 必须上下界同时给才生效，只给一端按「未给」处理 ——
     *
     * 与 StatsController 的区间口径一致。只给一端会被理解成「从某时到最新」，
     * 那不是用户的意思；而如果只给一端就悄悄生效，用户看到记录数变少却找不到原因。
     */
    private List<Record> filterByVisitTime(List<Record> all, String start, String end) {
        // 两个条件同时成立才过滤：早前写成「任一非空即过滤」，
        // 结果只给 start 时返回 131 条（应为全部 500），与注释和既有口径都不符
        if (!notBlankDate(start) || !notBlankDate(end)) {
            return all;
        }
        LocalDate from = LocalDate.parse(start);
        LocalDate to = LocalDate.parse(end).plusDays(1);
        List<Record> out = new ArrayList<>();
        for (Record r : all) {
            LocalDateTime t = r.getVisitTime();
            if (t == null) {
                continue;
            }
            LocalDate d = t.toLocalDate();
            if (d.isBefore(from) || !d.isBefore(to)) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private boolean notBlankDate(String s) {
        return s != null && !s.isBlank();
    }

    private StandardizationReportVO.TimeRange rangeOf(String start, String end) {
        StandardizationReportVO.TimeRange r = new StandardizationReportVO.TimeRange();
        // 只有两端都给、过滤真正生效时才回显区间。
        // 只给一端时过滤没生效，若还回显「2024-01-01 ~ 不限」，界面会显示成一个
        // 实际并未应用的区间，而记录数其实是全部 —— 那是自相矛盾的展示。
        boolean applied = notBlankDate(start) && notBlankDate(end);
        r.setStart(applied ? start : null);
        r.setEnd(applied ? end : null);
        // 批次12（12d）：两个计数改由库内聚合给出（见 RecordMapper.selectRangeAndDataset），
        // 与 datasetShape 共用同一条查询。区间口径在此按 filterByVisitTime 的同一规则解析：
        // 只有两端都给才下推边界，只给一端一律传 null —— 测试直接断言这两个参数。
        java.time.LocalDateTime from = applied ? LocalDate.parse(start).atStartOfDay() : null;
        java.time.LocalDateTime to = applied ? LocalDate.parse(end).plusDays(1).atStartOfDay() : null;
        Map<String, Object> row = reportDatasetAggregate(from, to);
        r.setRecords(num(row.get("recordCount")));
        // 「被排除」= 全库数 - 区间内数；无区间时两者相等，恒为 0
        r.setExcluded(Math.max(0, num(row.get("totalAll")) - num(row.get("recordCount"))));
        return r;
    }

    /** 区间计数与数据集形态共用一条聚合（批次12·12d），避免同一批数据查两遍 */
    private Map<String, Object> reportDatasetAggregate(java.time.LocalDateTime from,
                                                       java.time.LocalDateTime to) {
        Map<String, Object> row = recordMapper.selectRangeAndDataset(
                RequestUtils.currentOrgId(), RequestUtils.viewAllOrgs(), from, to);
        return row == null ? Map.of() : row;
    }

    /**
     * 按接诊月份分组。
     *
     *
     * 为什么要按月而不是按年：词表补一批、解析重跑一批，通常只覆盖某些月份的数据；
     *
     * 按月才能看出「哪些月份已经吃到新词表、哪些还没」。没有 visitTime 的病历
     * 归入「未知」一组，不静默丢弃。
     */
    private List<StandardizationReportVO.MonthlyBucket> byMonth(java.time.LocalDateTime from,
                                                               java.time.LocalDateTime to) {
        // 批次12（12d）：按月分桶与月内统计改由库内聚合给出（见 RecordMapper.selectByMonth）。
        // 口径提醒：gap 按「长度>2 且不以脉/舌开头且非体征」统计，**不查词表** ——
        // 与 normalizableRate 的 isInDict 口径不同，混用会算错。
        List<StandardizationReportVO.MonthlyBucket> out = new ArrayList<>();
        for (Map<String, Object> row : recordMapper.selectByMonth(
                RequestUtils.currentOrgId(), RequestUtils.viewAllOrgs(), from, to)) {
            StandardizationReportVO.MonthlyBucket b = new StandardizationReportVO.MonthlyBucket();
            b.setMonth(String.valueOf(row.get("month")));
            b.setRecords(num(row.get("records")));
            int symTotal = num(row.get("symTotal"));
            b.setSymptomRate(symTotal == 0 ? null : pct(num(row.get("symHit")), symTotal));
            b.setDictionaryGap(num(row.get("gap")));
            b.setAvgScore(row.get("avgScore") instanceof Number n ? n.doubleValue() : 0.0);
            b.setCapped(num(row.get("capped")));
            out.add(b);
        }
        // 「未知」永远排最后；其余按月份倒序（最近的在最前）—— 展示规则留在 Java 侧
        out.sort(Comparator.comparing((StandardizationReportVO.MonthlyBucket b) -> b.getMonth())
                .reversed());
        out.sort(Comparator.comparing(b -> "未知".equals(b.getMonth())));
        return out;
    }

    /** 该未归一实体是否属于「本该能归一」的（不是碎片、不是错放） */
    private boolean normalizableTerm(String content) {
        if (content.isEmpty() || content.length() <= FRAGMENT_MAX_LEN) {
            return false;
        }
        return !startsWithAny(content, MISROUTED_PREFIX) && !containsAny(content, PHYSICAL_SIGN);
    }

    private String pct(int part, int total) {
        return total == 0 ? null
                : Math.round(part * 1000.0 / total) / 10.0 + "%";
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

    /** 各类实体：抽取数 / 已归一数（批次12·12d：计数改走库内聚合，标签与过期判定仍在 Java 侧） */
    private List<StandardizationReportVO.TypeCoverage> coverage(java.time.LocalDateTime from,
                                                               java.time.LocalDateTime to) {
        Map<String, StandardizationReportVO.TypeCoverage> byField = new LinkedHashMap<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            StandardizationReportVO.TypeCoverage c = new StandardizationReportVO.TypeCoverage();
            c.setField(t.structuredKey());
            c.setLabel(t.label());
            byField.put(t.structuredKey(), c);
        }
        // 抽取数与已归一数不再靠「把整表拉进 JVM 逐条数」，而由 RecordMapper.selectCoverage
        // 在库内用 JSON_TABLE 一次聚合出来（九类 UNION ALL；已与 Java 版逐类核对一致）。
        // 中文标签仍取自 EntityTypes —— 单一来源，不在 SQL 里再抄一份。
        for (Map<String, Object> row : recordMapper.selectCoverage(
                RecordFilter.domainOrgId(), RequestUtils.viewAllOrgs(), from, to)) {
            StandardizationReportVO.TypeCoverage c = byField.get(String.valueOf(row.get("field")));
            if (c != null) {
                c.setTotal(num(row.get("total")));
                c.setNormalized(num(row.get("normalized")));
            }
        }
        // 标出「解析早于词表建立」的类型：词表非空且抽到了实体，却一条都没归上。
        // 这一种补词表无效，只能重跑解析 —— 与真正的词表缺口必须分开说。
        String orgId = RequestUtils.currentOrgId();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            StandardizationReportVO.TypeCoverage c = byField.get(t.structuredKey());
            if (c == null) {
                continue;
            }
            if (t.dict()) {
                c.setTermCount(termStore.readEffective(orgId, t.key()).size());
            }
            c.setSuspectedStaleExtraction(c.getTermCount() > 0 && c.getTotal() > 0 && c.getNormalized() == 0);
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
    private StandardizationReportVO.UnmatchedBreakdown unmatched(java.time.LocalDateTime from,
                                                                java.time.LocalDateTime to) {
        // 批次12（12d）：改用库内 JSON_TABLE 聚合。此前是「把整表拉进 JVM、逐条解析 JSON、
        // 在 Java 里分类」——3.5 万条时这是本接口最重的一段（整接口 3.5~6.2 秒，见 docs/性能基线实测.md）。
        // 等价性不再由 mock 单测保证（被 mock 的正是这里删掉的 Java 分类），而由
        // tools/verify-unmatched-sql.py 对真实库校验：分项自洽 + TOP 明细 + 规则夹具。
        StandardizationReportVO.UnmatchedBreakdown b = new StandardizationReportVO.UnmatchedBreakdown();
        String orgId = RequestUtils.currentOrgId();
        boolean viewAll = RequestUtils.viewAllOrgs();
        Map<String, Object> row = recordMapper.selectUnmatchedBreakdown(orgId, viewAll, from, to);
        if (row != null) {
            b.setTotal(num(row.get("total")));
            b.setPhysicalSign(num(row.get("physicalSign")));
            b.setMisrouted(num(row.get("misrouted")));
            b.setFragment(num(row.get("fragment")));
            b.setDictionaryGap(num(row.get("dictionaryGap")));
        }
        // 列表也守卫：mapper 契约上返回空列表，但测试替身可能给 null
        java.util.List<Map<String, Object>> tops =
                recordMapper.selectUnmatchedTop(orgId, viewAll, from, to, 15);
        if (tops != null) {
            for (Map<String, Object> t : tops) {
                b.getTop().put(String.valueOf(t.get("content")), num(t.get("n")));
            }
        }
        return b;
    }

    /** 聚合值 MySQL 给的是 Long/BigDecimal，统一按 Number 取整，避免 ClassCastException */
    private static int num(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 可归一实体归一率。
     *
     *
     * 分母只算「已抽取 + 非抽取缺陷 + 已在症状词表内」：词表里根本没有的标准词
     *
     * （如 神疲乏力）是词表缺口本身，算进分母会把「补词表能改善多少」这个信号抹掉。
     */
    private StandardizationReportVO.NormalizableRate normalizableRate(java.time.LocalDateTime from,
                                                                     java.time.LocalDateTime to) {
        // 批次12（12d）：分子分母改由库内一次聚合算出（见 RecordMapper.selectNormalizableRate）。
        // 「在词表内」这条用 JSON_CONTAINS + 词表 JSON 参数表达 —— 参数化、无注入、不必拼长 IN 串。
        // 词表仍走 symptomTerms()（与原先调用点同一个有效词表口径），单一来源、不在 SQL 里另抄一份。
        StandardizationReportVO.NormalizableRate r = new StandardizationReportVO.NormalizableRate();
        String dictJson;
        try {
            dictJson = objectMapper.writeValueAsString(symptomTerms());
        } catch (Exception e) {
            // 词表序列化失败时按空词表算（分母 0），并留告警；不编造数字
            log.warn("[报告] 症状词表序列化失败，归一率按空词表计算：{}", e.getMessage());
            dictJson = "[]";
        }
        Map<String, Object> row = recordMapper.selectNormalizableRate(
                RequestUtils.currentOrgId(), RequestUtils.viewAllOrgs(), dictJson, from, to);
        if (row != null) {
            r.setDenominator(num(row.get("denominator")));
            r.setNumerator(num(row.get("numerator")));
        }
        return r;
    }

    /** 评分分布（批次12·12d，report 用）：走库内聚合，见 RecordMapper.selectScoreAndQc */
    private StandardizationReportVO.ScoreDistribution scoreDistribution(java.time.LocalDateTime from,
                                                                       java.time.LocalDateTime to) {
        StandardizationReportVO.ScoreDistribution d = new StandardizationReportVO.ScoreDistribution();
        Map<String, Object> row = reportAggregates(from, to);
        // 口径与下面的 List 版一致：total 与 avg 都基于「有分数的记录」
        d.setTotal(num(row.get("total")));
        d.setMin(num(row.get("minScore")));
        d.setMax(num(row.get("maxScore")));
        d.setCapped(num(row.get("capped")));
        d.setAvg(row.get("avgScore") instanceof Number n ? n.doubleValue() : 0.0);
        return d;
    }

    /**
     * 质控完成度（批次12·12d，report 用）：走库内聚合。
     *
     * <p>total 用**全部记录数**（totalAll），scored 用「qc_results.score 与 records.score 一致」的数
     * —— 与下面的 List 版口径一致；两处计数不同，别合并成一个。</p>
     */
    private StandardizationReportVO.QcCoverage qcCoverage(java.time.LocalDateTime from,
                                                         java.time.LocalDateTime to) {
        StandardizationReportVO.QcCoverage c = new StandardizationReportVO.QcCoverage();
        Map<String, Object> row = reportAggregates(from, to);
        int totalAll = num(row.get("totalAll"));
        int scored = num(row.get("qcScored"));
        c.setTotal(totalAll);
        c.setScored(scored);
        c.setComplete(totalAll > 0 && scored == totalAll);
        Object last = row.get("lastScoredAt");
        if (last != null) {
            // 库里是 DATETIME，取出可能是 Timestamp/LocalDateTime —— 统一转成同一格式
            java.time.LocalDateTime ldt = last instanceof java.time.LocalDateTime l
                    ? l : java.time.LocalDateTime.parse(String.valueOf(last).replace(' ', 'T'));
            c.setLastScoredAt(ldt.withNano(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }
        return c;
    }

    /** 评分分布与质控完成度共用一条聚合（批次12·12d），避免同一批数据算两遍 */
    private Map<String, Object> reportAggregates(java.time.LocalDateTime from, java.time.LocalDateTime to) {
        Map<String, Object> row = recordMapper.selectScoreAndQc(
                RequestUtils.currentOrgId(), RequestUtils.viewAllOrgs(), from, to);
        return row == null ? Map.of() : row;
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

    /**
     * 质控完成度。
     *
     *
     * 判据不是「有没有 qc_results」而是「qc_results 里的 score 与 records.score 是否一致」：
     *
     * 病历重新导入或重跑解析后，score 可能已变而 qc_results 还是上一次的结果，
     * 那种情况下报告里的分数分布与扣分构成全是旧口径。
     */
    private StandardizationReportVO.QcCoverage qcCoverage(List<Record> records) {
        StandardizationReportVO.QcCoverage c = new StandardizationReportVO.QcCoverage();
        c.setTotal(records.size());
        LocalDateTime latest = null;
        for (Record r : records) {
            Map<String, Object> qc = parseJson(r.getQcResults());
            if (qc == null || r.getScore() == null) {
                continue;
            }
            Object scoreInQc = qc.get("score");
            if (scoreInQc instanceof Number n && n.intValue() == r.getScore()) {
                c.setScored(c.getScored() + 1);
                if (r.getUpdateTime() != null
                        && (latest == null || r.getUpdateTime().isAfter(latest))) {
                    latest = r.getUpdateTime();
                }
            }
        }
        c.setComplete(c.getTotal() > 0 && c.getScored() == c.getTotal());
        if (latest != null) {
            c.setLastScoredAt(latest.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }
        return c;
    }

    /** 数据集形态：模板塌缩度决定这批数据能不能代表真实病历 */
    private StandardizationReportVO.DatasetShape datasetShape(java.time.LocalDateTime from,
                                                              java.time.LocalDateTime to) {
        // 批次12（12d）：三个字段改由库内聚合给出（见 RecordMapper.selectRangeAndDataset）——
        // recordCount=记录数、templates=主诉模板数（去掉病程月数后去重）、colloquial=含口语化未归一症状的记录数。
        StandardizationReportVO.DatasetShape s = new StandardizationReportVO.DatasetShape();
        Map<String, Object> row = reportDatasetAggregate(from, to);
        s.setRecordCount(num(row.get("recordCount")));
        s.setChiefComplaintTemplates(num(row.get("templates")));
        s.setRecordsWithColloquialSymptom(num(row.get("colloquial")));
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

    /**
     * 同一条病历的结构化数据，在**一次请求内**只解析一次（批次 12 · 12d）。
     *
     * <p>{@code report()} 里 coverage / unmatched / normalizableRate 三个消费者都要这份 Map，
     * 此前各自调用本方法 ⇒ 同一条 JSON 被 parse 三遍。3.5 万条时该接口实测 3.5~6.2 秒
     * （见 docs/性能基线实测.md），三份解析开销都压在这条路径上。</p>
     *
     * <p>用 ThreadLocal：请求线程各自一份，天然并发安全；{@code report()} 入口先 clear()，
     * 避免 Tomcat 线程复用读到上次请求的残留。用 IdentityHashMap：{@code Record} 未重写
     * equals/hashCode，按引用比较既正确又最快。</p>
     */
    /**
     * 解析缓存必须是 {@code static final}：Mockito 用 Objenesis 绕过构造造实例时
     * **实例字段初始化器不会执行**，实例级 ThreadLocal 会是 null ⇒ NPE。
     * 静态初始化器一定会跑；每线程一份、请求入口 clear()，语义与实例级等价。
     */
    private static final ThreadLocal<Map<Record, Map<String, Object>>> structuredMemo =
            ThreadLocal.withInitial(java.util.IdentityHashMap::new);

    @SuppressWarnings("unchecked")
    private Map<String, Object> structured(Record r) {
        if (r.getStructuredData() == null || r.getStructuredData().isBlank()) {
            return null;
        }
        Map<Record, Map<String, Object>> memo = structuredMemo.get();
        Map<String, Object> hit = memo.get(r);
        if (hit != null) {
            return hit;
        }
        Map<String, Object> parsed = parseJson(r.getStructuredData());
        memo.put(r, parsed);
        return parsed;
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