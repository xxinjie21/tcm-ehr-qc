package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.StatsDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.OverviewVO;
import com.tcm.ehr.domain.vo.StatsAllVO;
import com.tcm.ehr.domain.vo.StatsVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 统计服务实现：指标卡聚合 + 按type统计（500条内内存聚合）+ 看板扩展。
 *
 * <p>看板扩展的过滤统一走 {@link RecordFilter}（先数据域、后用户筛选），
 * 与病历读取同域（按组织过滤）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl extends ServiceImpl<RecordMapper, Record> implements IStatsService {

    /** 评分分布桶（固定顺序） */
    private static final List<String> SCORE_BUCKETS = List.of("90+", "80-89", "70-79", "60-69", "60以下");

    /** 词频 TopN */
    private static final int TOP_N = 10;

    /**
     * 允许下推到 SQL 的实体数组路径白名单。
     *
     * <p>{@code RecordMapper.selectTermFreq} 的路径用 MyBatis {@code ${}} 做文本替换
     * （MySQL 的 {@code JSON_TABLE} 要求路径是字面量，不能用绑定参数），所以这里必须挡一道：
     * 任何非本类常量的取值都直接拒绝，杜绝外部输入进入 SQL 文本。
     * 证候走 {@code selectPatternFreq}（多一段兜底逻辑），不在此列。</p>
     */
    private static final Set<String> ALLOWED_JSON_PATHS = Set.of(
            "$.diseases[*]", "$.symptoms[*]", "$.formulaList[*]", "$.herbs[*]");

    // 词典真源（批次8b）：统计必须与归一读同一处，否则看板数字和实际生效词典对不上
    private final com.tcm.ehr.service.IDictionaryTermStore termStore;
    // 词频统计短 TTL 缓存（B1）：只缓 /stats/all 的词频分布，按数据域 + 筛选条件隔离
    private final com.tcm.ehr.common.cache.StatsCache statsCache;

    /**
     * 查询可选科室列表，只读。
     *
     * <p>按当前请求的组织数据域取，供筛选下拉使用。</p>
     *
     * @return 去重后的科室名列表
     */
    @Override
    public List<String> departments() {
        // 性能审查 P1-6 / A5：拆成两条 SQL，Java 侧按 viewAllOrgs 二选一 ——
        // 原本的 WHERE (#{viewAll}=1 OR org_id=#{orgId}) 会让优化器难按 org_id 收窄。
        // Org 分支的 orgId 传 domainOrgId()（无组=哨兵值），fail-closed 不会查到别组科室。
        return RequestUtils.viewAllOrgs()
                ? baseMapper.selectDepartmentsAll()
                : baseMapper.selectDepartmentsOrg(domainOrgId());
    }

    /**
     * 指标卡总览，只读。
     *
     * <p>按数据域一次性聚合总数、合格数、待复核数与无效数；合格率保留一位小数（百分数），
     * 总数为 0 时记 0。</p>
     *
     * @return 总览指标
     */
    @Override
    public OverviewVO overview() {
        // 1. 指标在库里聚合，只按数据域过滤（总览不受页面筛选影响）。
        //    viewAllOrgs 必须一并下推：同一次响应里的词频走 RecordFilter.build（认看全部），
        //    指标卡若不认，管理员的卡片数字与词频/列表就对不上。
        //    性能审查 P1-6 / A5：拆成两条 SQL（All/Org），Java 侧二选一，让
        //    (org_id, grade, governed) 覆盖索引对非管理员路径稳定生效。
        Map<String, Object> row = RequestUtils.viewAllOrgs()
                ? baseMapper.selectOverviewAll()
                : baseMapper.selectOverviewOrg(domainOrgId());
        OverviewVO vo = new OverviewVO();
        vo.setTotalRecords(num(row.get("totalRecords")));
        vo.setQualifiedCount(num(row.get("qualifiedCount")));
        // 2. 合格率保留一位小数；总数为 0 时给 0 而不是除零
        vo.setQualifiedRate(vo.getTotalRecords() == 0 ? 0.0
                : Math.round(vo.getQualifiedCount() * 1000.0 / vo.getTotalRecords()) / 10.0);
        vo.setPendingReviewCount(num(row.get("pendingReviewCount")));
        vo.setInvalidCount(num(row.get("invalidCount")));
        // 3. 血缘与新鲜度（对标 A5/A3）：数字出自哪一版词典、什么时候算的。
        //    同一响应里同时给出两者，前端才能在下钻时回答「换词表前后为什么变了」。
        vo.setSourceVersion(termStore.effectiveDictVersion(domainOrgId()));
        vo.setGeneratedAt(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return vo;
    }

    /**
     * 按类型统计：先按数据域收窄范围，再按调用方给的 ID 或筛选条件圈定。
     *
     * <p>数据域必须在最前面叠加，否则登录即可的接口会读到跨组织词频。</p>
     */
    @Override
    public StatsVO stats(StatsDTO dto) {
        String orgId = RequestUtils.currentOrgId();
        // 1. 三种圈定方式：病历ID → 筛选条件 → 全域（都不给时就是数据域内全部）。
        //    条件一律走 buildForAggregate（**无 ORDER BY**）：词频已在 SQL 侧 GROUP BY，
        //    ORDER BY 与 GROUP BY 不能共存（性能审查 P0-3#3）。
        QueryWrapper<Record> wrapper;
        if (dto.getRecordIds() != null && !dto.getRecordIds().isEmpty()) {
            // 优先：按病历ID圈定；ID 由调用方给，数据域必须先叠加
            wrapper = RecordFilter.buildForAggregate(orgId, new FiltersDTO()).in("id", dto.getRecordIds());
        } else if (dto.getFilters() != null && !dto.getFilters().isEmpty()) {
            // 次选：按筛选条件圈定
            wrapper = RecordFilter.buildForAggregate(orgId, toFilters(dto.getFilters()));
        } else {
            wrapper = RecordFilter.buildForAggregate(orgId, new FiltersDTO());
        }
        // 2. 词频在 DB 侧聚合，只把 Top10 带回 JVM（原实现把命中的全部病历物化进堆再逐条解析 JSON）
        return statsFor(wrapper, dto.getType());
    }

    /**
     * 看板汇总，只读。
     *
     * <p>病历范围经数据域 + 用户筛选圈定后，一次性产出疾病 / 症状 / 证型 / 处方四类词频 Top10；
     * 总览指标单独按数据域聚合。</p>
     *
     * @param filters 用户筛选条件，可为 null（表示不限）
     * @return 总览 + 四类统计
     */
    @Override
    public StatsAllVO all(FiltersDTO filters) {
        // 1. 总览每次现算（A5 已把计数下推 SQL 聚合，毫秒级；它依赖 grade/governed，
        //    与词频的失效时机不同，不放进缓存）
        StatsAllVO vo = new StatsAllVO();
        vo.setOverview(overview());
        // 2. 词频分布：**聚合已在 SQL 侧**（JSON_TABLE + GROUP BY，只回各类 Top10），
        //    但仍要全表展开 JSON，故保留 60s 缓存 + 写操作主动失效（StatsCacheInvalidator）。
        //    缓存未命中 / Redis 不可用都回退现算，不阻塞页面。
        //    只发两条 SQL：四类实体（疾病/症状/方剂/中药）一趟扫完，证候单独一条（带原始列兜底）。
        StatsAllVO freq = statsCache.wordFreq(filters, () -> {
            QueryWrapper<Record> wrapper =
                    RecordFilter.buildForAggregate(RequestUtils.currentOrgId(), filters);
            Map<String, List<Map<String, Object>>> entities = entityFreq(wrapper);
            StatsAllVO f = new StatsAllVO();
            f.setDisease(statisticsOf(entities.get("disease")));
            f.setSymptom(statisticsOf(entities.get("symptom")));
            f.setPattern(distributionOf(patternFreq(wrapper)));
            StatsVO prescription = new StatsVO();
            prescription.setFormulaStats(entities.get("formula"));
            prescription.setHerbStats(entities.get("herb"));
            f.setPrescription(prescription);
            return f;
        });
        if (freq != null) {
            vo.setDisease(freq.getDisease());
            vo.setSymptom(freq.getSymptom());
            vo.setPattern(freq.getPattern());
            vo.setPrescription(freq.getPrescription());
        }
        return vo;
    }

    /**
     * 看板扩展统计，只读。
     *
     * <p>病历范围经数据域 + 用户筛选圈定后，产出四块：质控趋势（按就诊月份升序、最多最近 12
     * 个月，含合格 / 待复核数与合格率）、科室合格率（按总数降序）、评分分布（固定分桶顺序）与
     * 词典规模（疾病 / 症状 / 证型 / 中药 / 方剂，直接读 dictionary_terms，读取失败按 -1 上报）。</p>
     *
     * @param filters 用户筛选条件，可为 null（表示不限）
     * @return 趋势、科室合格率、评分分布与词典规模
     */
    @Override
    public StatsVO extra(FiltersDTO filters) {
        List<Record> records = recordsFor(filters);
        StatsVO vo = new StatsVO();

        // 1. 质控趋势：按就诊月份聚合（升序，最多最近 12 个月）
        TreeMap<String, long[]> byMonth = new TreeMap<>();
        for (Record r : records) {
            String month = monthOf(r);
            if (month == null) continue;
            long[] acc = byMonth.computeIfAbsent(month, k -> new long[3]); // total, qualified, pending
            acc[0]++;
            if (isGrade(r, "合格")) acc[1]++;
            else if (isGrade(r, "待复核")) acc[2]++;
        }
        List<Map.Entry<String, long[]>> months = new java.util.ArrayList<>(byMonth.entrySet());
        // P5.2：超 12 个月会截断，告知前端（否则“为何只有最近 12 个月”无从判断）
        vo.setTrendTruncated(months.size() > 12);
        if (months.size() > 12) {
            months = months.subList(months.size() - 12, months.size());
        }
        for (Map.Entry<String, long[]> e : months) {
            StatsVO.TrendPoint p = new StatsVO.TrendPoint();
            p.setMonth(e.getKey());
            p.setTotal(e.getValue()[0]);
            p.setQualified(e.getValue()[1]);
            p.setQualifiedRate(rate(e.getValue()[1], e.getValue()[0]));
            p.setPendingReview(e.getValue()[2]);
            vo.getTrend().add(p);
        }

        // 2. 科室合格率
        Map<String, long[]> byDept = new HashMap<>();
        for (Record r : records) {
            String dept = r.getDepartment() == null || r.getDepartment().isBlank() ? "未填科室" : r.getDepartment().trim();
            long[] acc = byDept.computeIfAbsent(dept, k -> new long[2]); // total, qualified
            acc[0]++;
            if (isGrade(r, "合格")) acc[1]++;
        }
        byDept.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .forEach(e -> {
                    StatsVO.DeptRate d = new StatsVO.DeptRate();
                    d.setDepartment(e.getKey());
                    d.setTotal(e.getValue()[0]);
                    d.setQualified(e.getValue()[1]);
                    d.setQualifiedRate(rate(e.getValue()[1], e.getValue()[0]));
                    vo.getDepartmentRates().add(d);
                });

        // 3. 评分分布直方图（固定桶顺序）
        Map<String, Long> dist = new LinkedHashMap<>();
        SCORE_BUCKETS.forEach(b -> dist.put(b, 0L));
        for (Record r : records) {
            if (r.getScore() == null) continue;
            dist.merge(bucketOf(r.getScore()), 1L, Long::sum);
        }
        dist.forEach((bucket, count) -> {
            StatsVO.Bucket b = new StatsVO.Bucket();
            b.setBucket(bucket);
            b.setCount(count);
            vo.getScoreDistribution().add(b);
        });

        // 4. 词典规模（5 类术语数量）：读 dictionary_terms，见 termCount
        vo.getDictionary().put("disease", termCount("disease"));
        vo.getDictionary().put("symptom", termCount("symptom"));
        vo.getDictionary().put("pattern", termCount("pattern"));
        vo.getDictionary().put("herb", termCount("herb"));
        vo.getDictionary().put("formula", termCount("formula"));
        return vo;
    }

    /**
     * 某类术语条数：读 {@code dictionary_terms}（当前组织生效口径）。
     *
     * <p><b>必须与归一读同一处</b>（批次8b）：词典真源已从 JSON 文件改为按组织的表，
     * 这里若还数文件，看板会显示「文件里的基础层条数」，与该组织实际生效的词典
     * （含组织自有词条）对不上 —— 数字看起来正常，含义是错的。</p>
     *
     * <p>刻意<b>不</b>改成查 ES 的 _count：那样看板会因 ES 不可用而连「词典规模」这种
     * 静态信息都拿不到，而改造前看板并不依赖 ES。</p>
     *
     * <p>读库失败时返回 <b>-1</b> 而不是 0 —— 0 会被读成「词典是空的」，属于另一种误导。</p>
     */
    private int termCount(String type) {
        try {
            return termStore.readEffective(RequestUtils.currentOrgId(), type).size();
        } catch (Exception e) {
            // 读不到给 -1 而不是 0：0 会被当成「词典是空的」，-1 才能让页面显示「不可用」
            log.warn("[统计] {} 词典读取失败，词条数按 -1 上报: {}", type, e.getMessage());
            return -1;
        }
    }

    /**
     * 按数据域 + 用户筛选取病历（**只给看板扩展用**）。
     *
     * <p>词频统计已改为 SQL 侧聚合（性能审查 P0-3#3），不再走这里；本方法只服务
     * {@link #extra}，而它只消费就诊月份 / 分级 / 科室 / 评分四个标量列 ——
     * 所以投影里连 {@code structured_data} 与 {@code pattern} 都不需要。</p>
     */
    private List<Record> recordsFor(FiltersDTO filters) {
        // 条件组装统一走 RecordFilter：数据域与用户筛选的交集口径只有那一处
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentOrgId(), filters);
        // ⚠️ 列投影（性能审查 P0-3）：不投影会把 21 个 TEXT 列 + 2 个 JSON 列全拉进堆
        //    （4 万行 ≈320MB）。列名与 RecordFilter / Record 实体列一致，
        //    新增字段请同步补进 RecordColumnNameGuardTest 的列名守卫。
        wrapper.select("id", "visit_time", "grade", "department", "score");
        return baseMapper.selectList(wrapper);
    }

    /**
     * 按类型统计词频 Top10（性能审查 P0-3#3）：**全部在 SQL 侧聚合**，只把 TopN 带回 JVM。
     *
     * <p>原实现把命中病历整体物化进堆、再逐条解析 {@code structured_data} 后在 Java 里计数；
     * 现在中间计数留在 DB。口径不变：中药 / 方剂不回退原始列，证候在结构化缺失时回退
     * （见 {@link com.tcm.ehr.mapper.RecordMapper#selectPatternFreq}）。</p>
     */
    private StatsVO statsFor(QueryWrapper<Record> wrapper, String type) {
        StatsVO vo = new StatsVO();
        // 1. 按类型分派：中药/方剂走"处方"这一档（同时出方剂与中药两组）
        switch (type == null ? "" : type) {
            case "disease" -> vo.setStatistics(termFreq(wrapper, "$.diseases[*]", "disease"));
            case "pattern" -> vo.setDistribution(patternFreq(wrapper));
            case "symptom" -> vo.setStatistics(termFreq(wrapper, "$.symptoms[*]", "symptom"));
            case "prescription" -> {
                vo.setFormulaStats(termFreq(wrapper, "$.formulaList[*]", "formula"));
                vo.setHerbStats(termFreq(wrapper, "$.herbs[*]", "herb"));
            }
            // 同理：原来把四个英文枚举值拼进报错，界面上直接显示给用户
            default -> throw new IllegalArgumentException("统计类型不合法，请选择：疾病 / 证型 / 症状 / 处方");
        }
        return vo;
    }

    /**
     * 单类实体词频 TopN：一条 SQL 出结果（{@code JSON_TABLE} 展开 + {@code GROUP BY}）。
     *
     * @param jsonPath structured_data 里的数组路径；用 MyBatis {@code ${}} 进 SQL 文本，
     *                 故必须命中 {@link #ALLOWED_JSON_PATHS} 白名单，杜绝外部输入
     */
    private List<Map<String, Object>> termFreq(QueryWrapper<Record> wrapper, String jsonPath, String keyName) {
        if (!ALLOWED_JSON_PATHS.contains(jsonPath)) {
            throw new IllegalArgumentException("非法的结构化数组路径：" + jsonPath);
        }
        return toTop(baseMapper.selectTermFreq(wrapper, jsonPath, TOP_N), keyName);
    }

    /** 证候词频 TopN：结构化 {@code patternList} 为主，缺失时由 SQL 侧回退切分原始 {@code pattern} 列 */
    private List<Map<String, Object>> patternFreq(QueryWrapper<Record> wrapper) {
        return toTop(baseMapper.selectPatternFreq(wrapper, TOP_N), "pattern");
    }

    /**
     * 看板四类实体（疾病 / 症状 / 方剂 / 中药）各 TopN：**一趟扫完**。
     *
     * @return {@code kind → 该类 {键名: 词, count: 次数} 列表}，四个键恒在（无数据时为空列表）
     */
    private Map<String, List<Map<String, Object>>> entityFreq(QueryWrapper<Record> wrapper) {
        // 1. SQL 回的是 {kind, term, cnt} 扁平行，先按 kind 分桶
        Map<String, List<Map<String, Object>>> raw = new HashMap<>();
        for (Map<String, Object> row : baseMapper.selectTermFreqMulti(wrapper, TOP_N)) {
            raw.computeIfAbsent(String.valueOf(row.get("kind")), k -> new java.util.ArrayList<>()).add(row);
        }
        // 2. 归位到契约键名（与 statsFor 的单类分支同一套 keyName），缺的补空列表
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        out.put("disease", toTop(raw.get("disease"), "disease"));
        out.put("symptom", toTop(raw.get("symptom"), "symptom"));
        out.put("formula", toTop(raw.get("formula"), "formula"));
        out.put("herb", toTop(raw.get("herb"), "herb"));
        return out;
    }

    /** 把词频列表装成「按类型统计」档（{@code statistics}）—— 契约形状与改前一致 */
    private static StatsVO statisticsOf(List<Map<String, Object>> rows) {
        StatsVO vo = new StatsVO();
        vo.setStatistics(rows);
        return vo;
    }

    /** 把词频列表装成「证候分布」档（{@code distribution}） */
    private static StatsVO distributionOf(List<Map<String, Object>> rows) {
        StatsVO vo = new StatsVO();
        vo.setDistribution(rows);
        return vo;
    }

    /**
     * SQL 聚合结果（{@code term} / {@code cnt}）→ 契约形态 {@code {键名: 词, count: 次数}}。
     *
     * <p>契约不能改：{@code StatsVO} 的三个列表字段与前端图表都按这个形状读。
     * {@code cnt} 由 {@code COUNT(*)} 回来是 Long，这里收敛成 int —— 与改前的
     * {@code Map<String,Integer>} 一致，避免同一接口在两种实现下给出不同类型。</p>
     */
    private static List<Map<String, Object>> toTop(List<Map<String, Object>> rows, String keyName) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put(keyName, row.get("term"));
            Object cnt = row.get("cnt");
            m.put("count", cnt == null ? 0 : ((Number) cnt).intValue());
            out.add(m);
        }
        return out;
    }

    /** 就诊月份（yyyy-MM）；接诊时间为空返回 null，该条不进趋势 */
    private static String monthOf(Record r) {
        // 1. 无接诊时间不进趋势（否则会多出一个空月份桶）
        if (r.getVisitTime() == null) return null;
        String s = r.getVisitTime().toString();
        return s.length() >= 7 ? s.substring(0, 7) : null;
    }

    /** 分级是否等于给定值（空分级视为不等） */
    private static boolean isGrade(Record r, String grade) {
        return grade.equals(r.getGrade());
    }

    /** 百分比，分母为 0 时记 0，保留一位小数 */
    private static double rate(long part, long total) {
        return total == 0 ? 0.0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    /** 评分落入哪个分桶（前端分布图按固定顺序展示） */
    private static String bucketOf(int score) {
        // 1. 从高到低匹配，前端按固定桶顺序画分布图
        if (score >= 90) return "90+";
        if (score >= 80) return "80-89";
        if (score >= 70) return "70-79";
        if (score >= 60) return "60-69";
        return "60以下";
    }

    /** 本请求的组织数据域：管理员 = null（不限），其余身份 = 当前组织 ID */
    private String domainOrgId() {
        return RecordFilter.domainOrgId();
    }

    /** stats 契约的 filters 是无类型 Map —— 翻译交给 RecordFilter（口径只有那一处） */
    private FiltersDTO toFilters(Map<String, Object> filters) {
        return RecordFilter.fromMap(filters);
    }

    private long num(Object o) {
        return o == null ? 0 : Long.parseLong(String.valueOf(o));
    }
}
