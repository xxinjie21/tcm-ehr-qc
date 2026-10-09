package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.config.EntityTypes;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.StandardizationReportVO;
import com.tcm.ehr.service.IDictionaryTermStore;
import com.tcm.ehr.service.IStandardizationReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
    private final IDictionaryTermStore termStore;
    /** 与 StatsServiceImpl 同一个注入实例，不另建 —— 每次 new ObjectMapper 会有可观的初始化开销 */
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Override
    public StandardizationReportVO report() {
        return report(null, null);
    }

    /**
     * 28.23：报告 CSV 由后端生成，与另外两条导出（数据集 / 日志）统一走文件流。
     *
     * <p>列固定为 区块 / 指标 / 数值 / 说明；中文靠 UTF-8 BOM 让 Excel 正确识别。
     * 行内容与报告页展示一一对应，前端不再自行拼 CSV。</p>
     */
    @Override
    public byte[] reportCsv(String start, String end) {
        StandardizationReportVO r = report(start, end);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("区块", "指标", "数值", "说明"));
        for (StandardizationReportVO.DictQuality d : r.getDictQuality()) {
            String label = nz(d.getLabel());
            rows.add(List.of("词典质量", label + " 词条数", String.valueOf(d.getTermCount()), "共 " + d.getTermCount() + " 条"));
            rows.add(List.of("词典质量", label + " 有别名", String.valueOf(d.getAliasedCount()), "共 " + d.getTermCount() + " 条"));
            rows.add(List.of("词典质量", label + " 别名重复", String.valueOf(d.getSelfAliasCount()), "别名含标准词本身会自命中"));
        }
        rows.add(List.of("词典质量", "同名术语跨词典",
                String.valueOf(r.getCrossTypeDuplicates().size()), "同一词出现在多本词典"));
        for (StandardizationReportVO.TypeCoverage c : r.getCoverage()) {
            rows.add(List.of("归一情况", nz(c.getLabel()) + " 归一率",
                    c.getTotal() > 0 ? pct(c.getNormalized(), c.getTotal()) : "未抽取",
                    "抽取 " + c.getTotal() + " 条"));
        }
        StandardizationReportVO.UnmatchedBreakdown u = r.getUnmatched();
        rows.add(List.of("未归一构成", "词表未收录", String.valueOf(u.getDictionaryGap()), "责任：词表"));
        rows.add(List.of("未归一构成", "脉/舌被当成症状", String.valueOf(u.getMisrouted()), "责任：抽取"));
        rows.add(List.of("未归一构成", "体征被当成症状", String.valueOf(u.getPhysicalSign()), "责任：抽取"));
        rows.add(List.of("未归一构成", "抽取残词", String.valueOf(u.getFragment()), "责任：抽取"));
        rows.add(List.of("未归一构成", "合计", String.valueOf(u.getTotal()), ""));
        StandardizationReportVO.ScoreDistribution s = r.getScore();
        rows.add(List.of("评分", "扣分相同占比", s.getTotal() > 0 ? pct(s.getCapped(), s.getTotal()) : "—",
                "平均分 " + s.getAvg() + "，区间 " + s.getMin() + "~" + s.getMax()));
        StandardizationReportVO.TimeRange rg = r.getRange();
        rows.add(List.of("区间", "统计区间",
                (rg.getStart() == null ? "不限" : rg.getStart()) + " ~ "
                        + (rg.getEnd() == null ? "不限" : rg.getEnd()),
                "区间内 " + rg.getRecords() + " 条，排除 " + rg.getExcluded() + " 条"));
        for (StandardizationReportVO.MonthlyBucket m : r.getByMonth()) {
            rows.add(List.of("按月份", m.getMonth() + " 病历数", String.valueOf(m.getRecords()), ""));
            rows.add(List.of("按月份", m.getMonth() + " 症状归一率",
                    m.getSymptomRate() == null ? "未抽取" : m.getSymptomRate(), ""));
            rows.add(List.of("按月份", m.getMonth() + " 词表缺口", String.valueOf(m.getDictionaryGap()), "补词表可解决"));
            rows.add(List.of("按月份", m.getMonth() + " 平均分", String.valueOf(m.getAvgScore()), "扣分封顶 " + m.getCapped()));
        }
        StandardizationReportVO.DatasetShape ds = r.getDataset();
        rows.add(List.of("数据集", "病历总数", String.valueOf(ds.getRecordCount()), ""));
        rows.add(List.of("数据集", "主诉写法种类", String.valueOf(ds.getChiefComplaintTemplates()), "远小于病历数说明是测试数据"));
        rows.add(List.of("数据集", "来自患者口语", String.valueOf(ds.getRecordsWithColloquialSymptom()), "口语不是标准症状词"));
        rows.add(List.of("声明", nz(r.getDisclaimer()), "", nz(r.getGeneratedAt())));
        return csvBytes(rows);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 生成带 UTF-8 BOM 的 CSV：单元格一律加引号并转义内部引号，行尾 CRLF */
    private static byte[] csvBytes(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(nz(row.get(i)).replace("\"", "\"\"")).append('"');
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 按接诊时间区间出报告。
     *
     * @param start 起（yyyy-MM-dd），为空表示不限
     * @param end   止（yyyy-MM-dd），为空表示不限；与 start 必须同时给或同时不给
     */
    @Override
    public StandardizationReportVO report(String start, String end) {
        StandardizationReportVO vo = new StandardizationReportVO();
        vo.setDictQuality(dictQuality());
        vo.setCrossTypeDuplicates(crossTypeDuplicates());
        // 乙类：病历相关，必须先按数据域收窄，否则登录即可的接口会读到跨组织数据
        List<Record> all = recordsInDomain();
        List<Record> records = filterByVisitTime(all, start, end);
        vo.setRange(rangeOf(all, records, start, end));
        vo.setByMonth(byMonth(records));
        vo.setCoverage(coverage(records));
        vo.setUnmatched(unmatched(records, new HashSet<>(symptomTerms())));
        vo.setNormalizable(normalizableRate(records, new HashSet<>(symptomTerms())));
        vo.setScore(scoreDistribution(records));
        vo.setQc(qcCoverage(records));
        vo.setDataset(datasetShape(records));
        vo.setDisclaimer(DISCLAIMER);
        vo.setGeneratedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        // 批次14 · 对标 A5：数字的血缘 —— 本报告出自哪一版词典。
        // 与 generatedAt 配对：「按什么算的」+「什么时候算的」才是可追溯的；
        // 只给时间的话，换过词表后数字变了也说不清是哪一版造成的。
        vo.setSourceVersion(termStore.effectiveDictVersion(
                com.tcm.ehr.common.utils.RequestUtils.currentOrgId()));
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

    private StandardizationReportVO.TimeRange rangeOf(List<Record> all, List<Record> picked,
                                                     String start, String end) {
        StandardizationReportVO.TimeRange r = new StandardizationReportVO.TimeRange();
        // 只有两端都给、过滤真正生效时才回显区间。
        // 只给一端时过滤没生效，若还回显「2024-01-01 ~ 不限」，界面会显示成一个
        // 实际并未应用的区间，而记录数其实是全部 —— 那是自相矛盾的展示。
        boolean applied = notBlankDate(start) && notBlankDate(end);
        r.setStart(applied ? start : null);
        r.setEnd(applied ? end : null);
        r.setRecords(picked.size());
        r.setExcluded(all.size() - picked.size());
        return r;
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
    private List<StandardizationReportVO.MonthlyBucket> byMonth(List<Record> records) {
        Map<String, List<Record>> groups = new LinkedHashMap<>();
        for (Record r : records) {
            String key = r.getVisitTime() == null
                    ? "未知"
                    : YearMonth.from(r.getVisitTime()).toString();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<StandardizationReportVO.MonthlyBucket> out = new ArrayList<>();
        for (Map.Entry<String, List<Record>> en : groups.entrySet()) {
            List<Record> rs = en.getValue();
            StandardizationReportVO.MonthlyBucket b = new StandardizationReportVO.MonthlyBucket();
            b.setMonth(en.getKey());
            b.setRecords(rs.size());

            int symTotal = 0;
            int symHit = 0;
            for (Record r : rs) {
                Map<String, Object> sd = structured(r);
                if (sd == null || !(sd.get("symptoms") instanceof List<?> list)) {
                    continue;
                }
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    symTotal++;
                    if (m.get("normLevel") != null) {
                        symHit++;
                    } else {
                        String content = str(m.get("content"));
                        if (content.isEmpty()) {
                            content = str(m.get("sourceText"));
                        }
                        if (normalizableTerm(content)) {
                            b.setDictionaryGap(b.getDictionaryGap() + 1);
                        }
                    }
                }
            }
            b.setSymptomRate(symTotal == 0 ? null : pct(symHit, symTotal));

            StandardizationReportVO.ScoreDistribution sd2 = scoreDistribution(rs);
            b.setAvgScore(sd2.getAvg());
            b.setCapped(sd2.getCapped());
            out.add(b);
        }
        // 「未知」永远排最后；其余按月份倒序（最近的在最前）
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
            int aliased = 0;
            int selfAlias = 0;
            for (TermEntry e : entries) {
                if (e.getAliases() != null && !e.getAliases().isEmpty()) {
                    aliased++;
                    // 别名与标准词相同会在归一时自命中，属数据缺陷
                    if (e.getAliases().contains(e.getStandardTerm())) {
                        selfAlias++;
                    }
                }
            }
            q.setAliasedCount(aliased);
            q.setSelfAliasCount(selfAlias);
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
    private StandardizationReportVO.UnmatchedBreakdown unmatched(List<Record> records, Set<String> symptomDict) {
        StandardizationReportVO.UnmatchedBreakdown b = new StandardizationReportVO.UnmatchedBreakdown();
        // 批次2：同一次遍历内累积「词表缺口」项的次数（键=实体原文），不额外扫库
        java.util.Map<String, Integer> gapCount = new java.util.LinkedHashMap<>();
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
                    gapCount.merge(content, 1, Integer::sum);
                }
            }
        }
        // 批次2：按次数降序装入（LinkedHashMap 保序），前端直接渲染为可行动清单
        gapCount.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .limit(15)
                .forEach(e -> b.getTop().put(e.getKey(), e.getValue()));
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