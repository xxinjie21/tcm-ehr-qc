package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.StatsDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.OverviewVO;
import com.tcm.ehr.domain.vo.StatsAllVO;
import com.tcm.ehr.domain.vo.StatsVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /**
     * 评分分布桶：降序固定顺序，最后一项为兜底桶（低于所有阈值时落入）。
     *
     * 注意：桶名即前端分布图轴标签，改动需同步前端；长度必须恒为 BUCKET_MIN 长度 + 1。
     */
    private static final List<String> SCORE_BUCKETS =
            List.of("90+", "80-89", "70-79", "60-69", "60以下");

    /**
     * 各分桶的分数下限，降序排列，与 SCORE_BUCKETS 的前 N 项一一对应。
     *
     * 约束：BUCKET_MIN.length 必须等于 SCORE_BUCKETS.size() - 1（最后一项是兜底桶，无下限）。
     * 与 SCORE_BUCKETS 拆开维护会失去这条约束的编译期保护，故两者必须相邻定义、同步修改。
     */
    private static final int[] BUCKET_MIN = {90, 80, 70, 60};

    /** 词频 TopN */
    private static final int TOP_N = 10;

    /** 看板「词典规模」展示的 5 类术语（与前端卡片一致） */
    private static final List<String> DICT_TYPES = List.of("disease", "symptom", "pattern", "herb", "formula");

    /** 看板扩展缓存基准 TTL：60 ± 10s（与词频同口径，变更低频、读量大） */
    private static final java.time.Duration EXTRA_TTL = java.time.Duration.ofSeconds(60);

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

    /** 指标卡总览（不筛选口径）：等价 {@link #overview(FiltersDTO)} 传 null。 */
    @Override
    public OverviewVO overview() {
        return overview(null);
    }

    /**
     * 指标卡总览，只读。
     *
     * <p>3 张指标卡（总数 / 合格率 / 无效）在数据域之上叠加用户筛选（科室 + 就诊区间）；
     * 待复核数仍是全量口径 —— 它是待办条入口，点击跳到全量复核列表，数字须与列表一致。
     * 合格率保留一位小数（百分数），总数为 0 时记 0。</p>
     *
     * @param filters 范围条件，null 表示不限
     * @return 总览指标
     */
    @Override
    public OverviewVO overview(FiltersDTO filters) {
        // 1. 3 张卡：数据域 + 用户筛选（buildForAggregate 只出 WHERE，不含 ORDER BY）。
        //    viewAllOrgs 由 RecordFilter 内部认，管理员与词频/列表同域。
        Map<String, Object> card = baseMapper.selectOverviewFiltered(
                RecordFilter.buildForAggregate(RequestUtils.currentOrgId(), filters));
        // 2. 待复核数：全量口径（不随筛选），拆两条 SQL 让 (org_id, grade, governed) 覆盖索引生效。
        Map<String, Object> pendingRow = RequestUtils.viewAllOrgs()
                ? baseMapper.selectOverviewAll()
                : baseMapper.selectOverviewOrg(domainOrgId());
        OverviewVO vo = new OverviewVO();
        vo.setTotalRecords(num(card.get("totalRecords")));
        vo.setQualifiedCount(num(card.get("qualifiedCount")));
        // 3. 合格率保留一位小数；总数为 0 时给 0 而不是除零
        vo.setQualifiedRate(vo.getTotalRecords() == 0 ? 0.0
                : Math.round(vo.getQualifiedCount() * 1000.0 / vo.getTotalRecords()) / 10.0);
        vo.setPendingReviewCount(num(pendingRow.get("pendingReviewCount")));
        vo.setInvalidCount(num(card.get("invalidCount")));
        // 4. 血缘与新鲜度（对标 A5/A3）：数字出自哪一版词典、什么时候算的。
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
        // 看板扩展：全表 GROUP BY ×3 + 词典读取，每次进首页都现算。加 60s 短 TTL 缓存；
        // 失效用 StatsCache 的全局版本段 —— 任一写入即作废（见 StatsCache.getOrLoad）。
        return statsCache.getOrLoad("extra", filters, EXTRA_TTL,
                () -> extraFromDb(filters), StatsVO.class);
    }

    /** 看板扩展现算主体（不含缓存）。缓存包装见 {@link #extra(FiltersDTO)}。 */
    private StatsVO extraFromDb(FiltersDTO filters) {
        // P2-10：三块统计全部下推到 SQL 侧聚合。原实现是 recordsFor() 把命中病历（4 万行 ×
        // 5 列投影）整份拉进堆，再对同一份 List 独立遍历三遍（月 / 科室 / 分数）。
        // 现在中间计数留在 DB，回传行数从「病历数」降到「月份数 / 科室数 / 分档数」。
        // 条件由 buildForAggregate 产出：只含 WHERE、**不含 ORDER BY**（与 GROUP BY 不能共存）。
        QueryWrapper<Record> wrapper = RecordFilter.buildForAggregate(
                RequestUtils.currentOrgId(), filters);
        StatsVO vo = new StatsVO();

        // 1. 质控趋势：按就诊月份聚合（升序，最多最近 12 个月）
        TreeMap<String, long[]> byMonth = new TreeMap<>();
        for (Map<String, Object> row : baseMapper.selectTrendByMonth(wrapper)) {
            // 无接诊时间的行是 NULL 分组，与原实现「monthOf 返回 null 就 continue」同口径
            Object month = row.get("month");
            if (month == null) continue;
            long[] acc = byMonth.computeIfAbsent(month.toString(), k -> new long[3]);
            acc[0] += num(row.get("total"));
            acc[1] += num(row.get("qualified"));
            acc[2] += num(row.get("pendingReview"));
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

        // 2. 科室合格率。SQL 按 department 原样分组（含 NULL / 空白 / 带空格），
        //    这里归一到「未填科室」与 trim 后的键再累加 —— 与改造前「先归一 key 再计数」等价，
        //    因此 NULL / '' / '  ' 会合并成同一个桶而不是各占一行
        Map<String, long[]> byDept = new HashMap<>();
        for (Map<String, Object> row : baseMapper.selectDeptRates(wrapper)) {
            Object raw = row.get("department");
            String dept = (raw == null || raw.toString().isBlank())
                    ? "未填科室" : raw.toString().trim();
            long[] acc = byDept.computeIfAbsent(dept, k -> new long[2]);
            acc[0] += num(row.get("total"));
            acc[1] += num(row.get("qualified"));
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

        // 3. 评分分布直方图（固定桶顺序）。分档仍由 bucketOf 判定，避免边界在 SQL 里形成第二处真源
        Map<String, Long> dist = new LinkedHashMap<>();
        SCORE_BUCKETS.forEach(b -> dist.put(b, 0L));
        for (Map<String, Object> row : baseMapper.selectScoreCounts(wrapper)) {
            Object score = row.get("score");
            if (score == null) continue; // 无分数不计，与改造前一致
            dist.merge(bucketOf(((Number) score).intValue()), num(row.get("cnt")), Long::sum);
        }
        dist.forEach((bucket, count) -> {
            StatsVO.Bucket b = new StatsVO.Bucket();
            b.setBucket(bucket);
            b.setCount(count);
            vo.getScoreDistribution().add(b);
        });

        // 4. 词典规模（5 类术语条数）：**一次读回生效词表**，替代逐类 readEffective
        //    （性能审查 P1-1：原实现 5 类 × (基础层 + 本组织) 最多 10 条 SQL）。
        //    读库失败时全类给 -1 而不是 0 —— 0 会被读成「词典是空的」，属另一种误导。
        Map<String, List<TermEntry>> dicts = null;
        try {
            dicts = termStore.readEffectiveAll(RequestUtils.currentOrgId());
        } catch (Exception e) {
            log.warn("[统计] 词典读取失败，词典规模按 -1 上报: {}", e.getMessage());
        }
        for (String type : DICT_TYPES) {
            vo.getDictionary().put(type,
                    dicts == null ? -1 : dicts.getOrDefault(type, List.of()).size());
        }
        return vo;
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

    /** 百分比，分母为 0 时记 0，保留一位小数 */
    private static double rate(long part, long total) {
        return total == 0 ? 0.0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    /**
     * 评分落入哪个分桶。
     *
     * 按 BUCKET_MIN 降序匹配：命中第 i 个阈值即取 SCORE_BUCKETS 第 i 项；
     * 全部未命中则落最后一项（兜底桶）。
     */
    private static String bucketOf(int score) {
        for (int i = 0; i < BUCKET_MIN.length; i++) {
            if (score >= BUCKET_MIN[i]) {
                return SCORE_BUCKETS.get(i);
            }
        }
        return SCORE_BUCKETS.get(SCORE_BUCKETS.size() - 1);
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
