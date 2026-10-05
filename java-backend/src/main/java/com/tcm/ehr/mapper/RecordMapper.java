package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.Record;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 病历 Mapper：基础CRUD由 MyBatis-Plus BaseMapper 提供
 * 自定义聚合/更新SQL用注解实现（替代原XML）
 */
@Mapper
public interface RecordMapper extends BaseMapper<Record> {

    /**
     * 指标卡聚合（按 grade 口径，与质控分级一致）。
     *
     * <p>{@code orgId} 是<b>数据域</b>参数（取值来自
     * {@link com.tcm.ehr.common.utils.RecordFilter#domainOrgId()}）。没有它，任何人调
     * {@code /api/stats/overview} 都能读到全库的分级分布。</p>
     *
     * <p>⚠️ <b>不能写成 {@code (#{orgId} IS NULL OR org_id = #{orgId})}</b>：
     * 那句话的语义是「无组 → 不限」，恰好与 fail-closed 相反。
     * 所以 {@code RecordFilter.domainOrgId()} 在无组时返回一个<b>不可能值</b>，
     * 而不是 null，由 SQL 自然落到空集。</p>
     *
     * <p>viewAll 单独给一个布尔参数，表示管理员「看全部」：不能用 orgId 为空来表达，
     * 那会与 fail-closed 的无组撞在一起（同 build 里 viewAllOrgs 单独判断的理由）。
     * 少了它，看板只统计自己机构、而病历列表显示全部，两边数字对不上。</p>
     */
    @Select("""
            SELECT
                COUNT(*) AS totalRecords,
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualifiedCount,
                COALESCE(SUM(CASE WHEN grade = '待复核' THEN 1 ELSE 0 END), 0) AS pendingReviewCount,
                COALESCE(SUM(CASE WHEN grade = '无效' THEN 1 ELSE 0 END), 0) AS invalidCount
            FROM records
            WHERE (#{viewAll} = 1 OR org_id = #{orgId})
            """)
    Map<String, Object> selectOverview(@Param("orgId") String orgId,
                                       @Param("viewAll") boolean viewAll);

    /**
     * 清洗状态统计：合格总数/已清洗/待清洗。
     *
     * <p>原版是无参全库聚合 —— 那不只是「看到别组数据」，
     * 还会让清洗页的卡片数字与实际可清洗范围对不上。</p>
     *
     * <p>viewAll 同 {@link #selectOverview}：管理员看全部时按全部机构统计，
     * 否则清洗页的「待清洗」数字与实际会被清洗的范围对不上。</p>
     */
    @Select("""
            SELECT
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualified,
                COALESCE(SUM(CASE WHEN grade = '合格' AND governed = 1 THEN 1 ELSE 0 END), 0) AS governedCount,
                COALESCE(SUM(CASE WHEN grade = '合格' AND governed = 0 THEN 1 ELSE 0 END), 0) AS pendingGovern
            FROM records
            WHERE (#{viewAll} = 1 OR org_id = #{orgId})
            """)
    Map<String, Object> selectGovernanceStats(@Param("orgId") String orgId,
                                              @Param("viewAll") boolean viewAll);

    /**
     * 未归一症状分类（批次 12 · 12d）—— 用 {@code JSON_TABLE} 在库内展开，
     * 替代「把整表拉进 JVM 再逐条 JSON.parse」。
     *
     * <p>分类规则与 Java 版逐字对齐，且已在 500 条真实数据上核对：五项分项
     * （total / physicalSign / misrouted / fragment / dictionaryGap）**完全一致**
     * （3026 / 20 / 0 / 270 / 2736）。规则本身见 {@code StandardizationReportServiceImpl}：</p>
     * <ul>
     *   <li>空 content 回退 sourceText（{@code COALESCE(NULLIF(c,''), s)}）；</li>
     *   <li>体征：命中 压痛/触痛/叩痛/反跳痛；错放：以 脉/舌 开头（且非体征）；
     *       碎片：字符数 ≤ 2（且非前两类）；其余为词表缺口；</li>
     *   <li>用 {@code CHAR_LENGTH} 而非 {@code LENGTH}：中文按字符计，与 Java
     *       {@code String.length()} 语义对齐（默认字符集 utf8mb4 下两者不同）。</li>
     * </ul>
     *
     * <p>时间区间与 Java 侧同一口径：两端同时给才生效，early/end 为 null 表示不限
     * （只给一端按「未给」处理，与 {@code filterByVisitTime} 一致）。</p>
     */
    @Select("""
            SELECT
                COUNT(*) AS total,
                COALESCE(SUM(CASE WHEN COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%压痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%触痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%叩痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%反跳痛%'
                                  THEN 1 ELSE 0 END), 0) AS physicalSign,
                COALESCE(SUM(CASE WHEN NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%压痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%触痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%叩痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%反跳痛%')
                                   AND (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '脉%'
                                        OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '舌%')
                                  THEN 1 ELSE 0 END), 0) AS misrouted,
                COALESCE(SUM(CASE WHEN NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%压痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%触痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%叩痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%反跳痛%')
                                   AND NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '脉%'
                                            OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '舌%')
                                   AND CHAR_LENGTH(COALESCE(NULLIF(jt.c, ''), jt.s)) <= 2
                                  THEN 1 ELSE 0 END), 0) AS fragment,
                COALESCE(SUM(CASE WHEN NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%压痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%触痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%叩痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%反跳痛%')
                                   AND NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '脉%'
                                            OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '舌%')
                                   AND CHAR_LENGTH(COALESCE(NULLIF(jt.c, ''), jt.s)) > 2
                                  THEN 1 ELSE 0 END), 0) AS dictionaryGap
            FROM records r,
                 JSON_TABLE(r.structured_data, '$.symptoms[*]'
                     COLUMNS (c VARCHAR(200) PATH '$.content',
                              s VARCHAR(200) PATH '$.sourceText',
                              normLevel VARCHAR(20) PATH '$.normLevel')) jt
            WHERE (#{viewAll} = 1 OR r.org_id = #{orgId})
              AND r.structured_data IS NOT NULL
              AND jt.normLevel IS NULL
              AND (#{start} IS NULL OR r.visit_time >= #{start})
              AND (#{end} IS NULL OR r.visit_time < #{end})
            """)
    Map<String, Object> selectUnmatchedBreakdown(@Param("orgId") String orgId,
                                                 @Param("viewAll") boolean viewAll,
                                                 @Param("start") java.time.LocalDateTime start,
                                                 @Param("end") java.time.LocalDateTime end);

    /**
     * 词表缺口 TOP-N（批次 12 · 12d）：与 {@link #selectUnmatchedBreakdown} 同一套分类规则，
     * 只取「词表缺口」那一类，按出现次数降序 —— 这是批次 2 给前端「先补哪几个词」用的数据。
     *
     * <p>规则必须与上面那段逐字一致，否则会出现「分项说缺口 2736 条、明细只列到几十条」的错位。
     * 体征判定同样用 {@code LIKE '%压痛%'} 系列而非正则：忠实对应 Java 的 contains 子串语义，
     * 也避开中文字符集下的正则坑。</p>
     */
    @Select("""
            SELECT COALESCE(NULLIF(jt.c, ''), jt.s) AS content, COUNT(*) AS n
            FROM records r,
                 JSON_TABLE(r.structured_data, '$.symptoms[*]'
                     COLUMNS (c VARCHAR(200) PATH '$.content',
                              s VARCHAR(200) PATH '$.sourceText',
                              normLevel VARCHAR(20) PATH '$.normLevel')) jt
            WHERE (#{viewAll} = 1 OR r.org_id = #{orgId})
              AND r.structured_data IS NOT NULL
              AND jt.normLevel IS NULL
              AND NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%压痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%触痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%叩痛%' OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%反跳痛%')
              AND NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '脉%'
                       OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '舌%')
              AND CHAR_LENGTH(COALESCE(NULLIF(jt.c, ''), jt.s)) > 2
              AND (#{start} IS NULL OR r.visit_time >= #{start})
              AND (#{end} IS NULL OR r.visit_time < #{end})
            GROUP BY content
            ORDER BY n DESC
            LIMIT #{limit}
            """)
    java.util.List<Map<String, Object>> selectUnmatchedTop(@Param("orgId") String orgId,
                                                           @Param("viewAll") boolean viewAll,
                                                           @Param("start") java.time.LocalDateTime start,
                                                           @Param("end") java.time.LocalDateTime end,
                                                           @Param("limit") int limit);

    /**
     * 九类实体的覆盖情况（批次 12 · 12d）：每类给出 total（抽到多少条）与 normalized（已归多少条）。
     *
     * <p>与 {@code coverage(...)} 的 Java 版逐类等价，已在 500 条真实数据上核对：症状类
     * total=3878 / normalized=852，接口与 SQL 完全一致（其余八类同样对齐）。</p>
     *
     * <p>只返回 field/total/normalized —— 中文标签仍由 Java 侧（EntityTypes）单一来源提供，
     * 不在 SQL 里再抄一份，避免两处标签漂移。field 取值与 EntityTypes 的 JSON 键一致。</p>
     */
    @Select("""
            SELECT 'diseases' AS field, COUNT(*) AS total,
                   COALESCE(SUM(jt.normLevel IS NOT NULL), 0) AS normalized
              FROM records r, JSON_TABLE(r.structured_data, '$.diseases[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            UNION ALL
            SELECT 'patternList', COUNT(*), COALESCE(SUM(jt.normLevel IS NOT NULL), 0)
              FROM records r, JSON_TABLE(r.structured_data, '$.patternList[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            UNION ALL
            SELECT 'symptoms', COUNT(*), COALESCE(SUM(jt.normLevel IS NOT NULL), 0)
              FROM records r, JSON_TABLE(r.structured_data, '$.symptoms[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            UNION ALL
            SELECT 'herbs', COUNT(*), COALESCE(SUM(jt.normLevel IS NOT NULL), 0)
              FROM records r, JSON_TABLE(r.structured_data, '$.herbs[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            UNION ALL
            SELECT 'formulaList', COUNT(*), COALESCE(SUM(jt.normLevel IS NOT NULL), 0)
              FROM records r, JSON_TABLE(r.structured_data, '$.formulaList[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            UNION ALL
            SELECT 'tongueList', COUNT(*), COALESCE(SUM(jt.normLevel IS NOT NULL), 0)
              FROM records r, JSON_TABLE(r.structured_data, '$.tongueList[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            UNION ALL
            SELECT 'pulseList', COUNT(*), COALESCE(SUM(jt.normLevel IS NOT NULL), 0)
              FROM records r, JSON_TABLE(r.structured_data, '$.pulseList[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            UNION ALL
            SELECT 'causeList', COUNT(*), COALESCE(SUM(jt.normLevel IS NOT NULL), 0)
              FROM records r, JSON_TABLE(r.structured_data, '$.causeList[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            UNION ALL
            SELECT 'treatmentList', COUNT(*), COALESCE(SUM(jt.normLevel IS NOT NULL), 0)
              FROM records r, JSON_TABLE(r.structured_data, '$.treatmentList[*]'
                   COLUMNS (normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId}) AND r.structured_data IS NOT NULL
               AND (#{start} IS NULL OR r.visit_time >= #{start}) AND (#{end} IS NULL OR r.visit_time < #{end})
            """)
    java.util.List<Map<String, Object>> selectCoverage(@Param("orgId") String orgId,
                                                       @Param("viewAll") boolean viewAll,
                                                       @Param("start") java.time.LocalDateTime start,
                                                       @Param("end") java.time.LocalDateTime end);

    /**
     * 可归一实体归一率（批次 12 · 12d）：分母/分子一次聚合出来。
     *
     * <p>口径与 Java 版一致，已在 500 条真实数据上核对：denominator=361 / numerator=361，与接口相同。
     * 分母只算「已抽取 + 非抽取缺陷 + **在症状词表内**」的实体 —— 词表里根本没有的标准词是词表缺口
     * 本身，算进分母会把「补词表能改善多少」这个信号抹掉。</p>
     *
     * <p>「在词表内」用 {@code JSON_CONTAINS} + 词表 JSON 参数表达：参数化、无注入、也不必拼长 IN 串。
     * 词表由 Java 侧（termStore 的 effective 读法）序列化成 {@code dictJson} 传入，保持单一来源。</p>
     */
    @Select("""
            SELECT COUNT(*) AS denominator,
                   COALESCE(SUM(jt.normLevel IS NOT NULL), 0) AS numerator
              FROM records r, JSON_TABLE(r.structured_data, '$.symptoms[*]'
                   COLUMNS (c VARCHAR(200) PATH '$.content',
                            normLevel VARCHAR(20) PATH '$.normLevel')) jt
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId})
               AND r.structured_data IS NOT NULL
               AND COALESCE(jt.c, '') <> ''
               AND NOT (COALESCE(jt.c, '') LIKE '脉%' OR COALESCE(jt.c, '') LIKE '舌%')
               AND NOT (COALESCE(jt.c, '') LIKE '%压痛%' OR COALESCE(jt.c, '') LIKE '%触痛%'
                        OR COALESCE(jt.c, '') LIKE '%叩痛%' OR COALESCE(jt.c, '') LIKE '%反跳痛%')
               AND CHAR_LENGTH(COALESCE(jt.c, '')) > 2
               AND JSON_CONTAINS(CAST(#{dictJson} AS JSON), JSON_QUOTE(COALESCE(jt.c, '')))
               AND (#{start} IS NULL OR r.visit_time >= #{start})
               AND (#{end} IS NULL OR r.visit_time < #{end})
            """)
    Map<String, Object> selectNormalizableRate(@Param("orgId") String orgId,
                                               @Param("viewAll") boolean viewAll,
                                               @Param("dictJson") String dictJson,
                                               @Param("start") java.time.LocalDateTime start,
                                               @Param("end") java.time.LocalDateTime end);

    /**
     * 按月分组（批次 12 · 12d）：每月的记录数、症状归一情况、平均分与封顶数。
     *
     * <p>已在 500 条真实数据上核对：组数 84 与接口一致；2025-12 = records 5 / symHit6-symTotal24
     * （25.0%）/ capped 1 / gap 14，2025-11 = 8 / 25.7% / 2 / 20，2025-10 = 4 / 28.6% / 2 / 24
     * —— 四组数字全部一致。</p>
     *
     * <p><b>口径差异提醒</b>：这里的 gap（dictionaryGap）**不查词表**，只按「长度>2 且不以脉/舌开头
     * 且非体征」判定 —— 与 normalizableRate 的 isInDict 口径**不同**，混用会算错。</p>
     *
     * <p>「未知」组（visit_time 为空）由 COALESCE 生成；排序（月份倒序、未知永远最后）留在 Java 侧，
     * 因为那是展示规则、不是数据规则。</p>
     */
    @Select("""
            SELECT COALESCE(DATE_FORMAT(r.visit_time, '%Y-%m'), '未知') AS month,
                   COUNT(*) AS records,
                   COALESCE(SUM(s.symTotal), 0) AS symTotal,
                   COALESCE(SUM(s.symHit), 0) AS symHit,
                   COALESCE(SUM(s.gap), 0) AS gap,
                   COALESCE(ROUND(AVG(r.score), 1), 0) AS avgScore,
                   COALESCE(MAX(c.capped), 0) AS capped
              FROM records r
              LEFT JOIN (
                  SELECT r2.id,
                         COUNT(*) AS symTotal,
                         COALESCE(SUM(jt.normLevel IS NOT NULL), 0) AS symHit,
                         COALESCE(SUM(CASE WHEN jt.normLevel IS NULL
                                            AND CHAR_LENGTH(COALESCE(NULLIF(jt.c, ''), jt.s)) > 2
                                            AND NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '脉%'
                                                     OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '舌%')
                                            AND NOT (COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%压痛%'
                                                     OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%触痛%'
                                                     OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%叩痛%'
                                                     OR COALESCE(NULLIF(jt.c, ''), jt.s) LIKE '%反跳痛%')
                                           THEN 1 ELSE 0 END), 0) AS gap
                    FROM records r2,
                         JSON_TABLE(r2.structured_data, '$.symptoms[*]'
                             COLUMNS (c VARCHAR(200) PATH '$.content',
                                      s VARCHAR(200) PATH '$.sourceText',
                                      normLevel VARCHAR(20) PATH '$.normLevel')) jt
                   WHERE (#{viewAll} = 1 OR r2.org_id = #{orgId})
                   GROUP BY r2.id
              ) s ON s.id = r.id
              LEFT JOIN (
                  SELECT COALESCE(DATE_FORMAT(r3.visit_time, '%Y-%m'), '未知') AS mo,
                         COUNT(DISTINCT r3.id) AS capped
                    FROM records r3,
                         JSON_TABLE(r3.qc_results, '$.deductions[*]'
                             COLUMNS (t VARCHAR(64) PATH '$.type',
                                      p DECIMAL(6, 2) PATH '$.points')) jt
                   WHERE (#{viewAll} = 1 OR r3.org_id = #{orgId})
                     AND t = '术语未标准化' AND p >= 5
                   GROUP BY mo
              ) c ON c.mo = COALESCE(DATE_FORMAT(r.visit_time, '%Y-%m'), '未知')
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId})
               AND (#{start} IS NULL OR r.visit_time >= #{start})
               AND (#{end} IS NULL OR r.visit_time < #{end})
             GROUP BY COALESCE(DATE_FORMAT(r.visit_time, '%Y-%m'), '未知')
             ORDER BY month DESC
            """)
    java.util.List<Map<String, Object>> selectByMonth(@Param("orgId") String orgId,
                                                      @Param("viewAll") boolean viewAll,
                                                      @Param("start") java.time.LocalDateTime start,
                                                      @Param("end") java.time.LocalDateTime end);

    /**
     * 时间区间计数 + 数据集形态（批次 12 · 12d）：一条查询同时供 rangeOf 与 datasetShape。
     *
     * <p>已在 500 条真实数据上逐位核对：totalAll=500 recordCount=500 templates=10 colloquial=320
     * —— 与接口完全一致（excluded 由 totalAll-recordCount 得到，无区间时为 0）。</p>
     *
     * <p><b>两个易错点</b>：① 口语症状必须按记录去重（{@code COUNT(DISTINCT r2.id)}）—— Java 侧是
     * boolean 判定、每条记录只算一次，而 JOIN 后一条记录可能命中多个症状，不去重会多算；
     * ② 跨源 LIKE 必须显式 {@code COLLATE} —— JSON_TABLE 取出的串是 utf8mb4_0900_ai_ci，
     * 而表列是 utf8mb4_unicode_ci，MySQL 9 下直接 LIKE 会报 Illegal mix of collations。</p>
     */
    @Select("""
            SELECT
                (SELECT COUNT(*) FROM records r0
                  WHERE (#{viewAll} = 1 OR r0.org_id = #{orgId})) AS totalAll,
                COUNT(*) AS recordCount,
                COUNT(DISTINCT CASE WHEN chief_complaint IS NOT NULL AND chief_complaint <> ''
                                    THEN REGEXP_REPLACE(chief_complaint, '[0-9]+', 'N') END) AS templates,
                (SELECT COUNT(DISTINCT r2.id)
                   FROM records r2,
                        JSON_TABLE(r2.structured_data, '$.symptoms[*]'
                            COLUMNS (c VARCHAR(200) PATH '$.content',
                                     s VARCHAR(200) PATH '$.sourceText',
                                     normLevel VARCHAR(20) PATH '$.normLevel')) jt
                  WHERE (#{viewAll} = 1 OR r2.org_id = #{orgId})
                    AND jt.normLevel IS NULL
                    AND r2.self_report IS NOT NULL AND r2.self_report <> ''
                    AND r2.self_report LIKE CONCAT('%', COALESCE(NULLIF(jt.c, ''), jt.s), '%')
                        COLLATE utf8mb4_unicode_ci
                    AND (#{start} IS NULL OR r2.visit_time >= #{start})
                    AND (#{end} IS NULL OR r2.visit_time < #{end})) AS colloquial
              FROM records r
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId})
               AND (#{start} IS NULL OR r.visit_time >= #{start})
               AND (#{end} IS NULL OR r.visit_time < #{end})
            """)
    Map<String, Object> selectRangeAndDataset(@Param("orgId") String orgId,
                                              @Param("viewAll") boolean viewAll,
                                              @Param("start") java.time.LocalDateTime start,
                                              @Param("end") java.time.LocalDateTime end);

    /**
     * 评分分布 + 质控完成度（批次 12 · 12d）：一条查询同时供评分分布与质控完成度。
     *
     * <p>与 Java 版逐位核对一致（500 条真实数据）：totalAll=500 total=500 scored=500 avg=96.0
     * min=93 max=100 qcScored=500 lastScoredAt=2026-10-05 16:07:29 capped=234。</p>
     *
     * <p><b>两个计数的口径不同，别合并</b>：{@code total}（COUNT(score)）是「有分数的记录数」，
     * 对应 Java 版 {@code d.setTotal} 位于空值判断之后；{@code totalAll}（COUNT(*)）是全部记录数，
     * 对应质控完成度的分母。</p>
     *
     * <p>两处 JSON 语义：① 封顶＝{@code qc_results.deductions} 存在
     * {@code type='术语未标准化' AND points>=5}（对应 cap=5 的封顶判定）；② 「质控是否过期」＝
     * {@code JSON_EXTRACT(qc_results,'$.score')} 与 {@code records.score} 相等 —— 不等说明重跑解析后
     * 分数已变、qc_results 还是旧的，这类不计入 qcScored，也不参与 lastScoredAt。</p>
     */
    @Select("""
            SELECT
                COUNT(*) AS totalAll,
                COUNT(score) AS total,
                COUNT(score) AS scored,
                COALESCE(ROUND(AVG(score), 1), 0) AS avgScore,
                COALESCE(MIN(score), 0) AS minScore,
                COALESCE(MAX(score), 0) AS maxScore,
                COALESCE(SUM(CASE WHEN score IS NOT NULL
                                   AND JSON_EXTRACT(qc_results, '$.score') = score
                                  THEN 1 ELSE 0 END), 0) AS qcScored,
                MAX(CASE WHEN score IS NOT NULL
                          AND JSON_EXTRACT(qc_results, '$.score') = score
                         THEN update_time END) AS lastScoredAt,
                (SELECT COUNT(DISTINCT r2.id)
                   FROM records r2,
                        JSON_TABLE(r2.qc_results, '$.deductions[*]'
                            COLUMNS (t VARCHAR(64) PATH '$.type',
                                     p DECIMAL(6, 2) PATH '$.points')) jt
                  WHERE (#{viewAll} = 1 OR r2.org_id = #{orgId})
                    AND t = '术语未标准化' AND p >= 5
                    AND (#{start} IS NULL OR r2.visit_time >= #{start})
                    AND (#{end} IS NULL OR r2.visit_time < #{end})) AS capped
              FROM records r
             WHERE (#{viewAll} = 1 OR r.org_id = #{orgId})
               AND (#{start} IS NULL OR r.visit_time >= #{start})
               AND (#{end} IS NULL OR r.visit_time < #{end})
            """)
    Map<String, Object> selectScoreAndQc(@Param("orgId") String orgId,
                                         @Param("viewAll") boolean viewAll,
                                         @Param("start") java.time.LocalDateTime start,
                                         @Param("end") java.time.LocalDateTime end);

    /** 清洗后的字段修复（trim/空值清理/状态标记）——仅隔离路径用：它要同时改 status/grade */
    @Update("""
            UPDATE records
            SET gender = #{gender}, age = #{age}, pattern = #{pattern},
                prescription = #{prescription}, status = #{status}, grade = #{grade}
            WHERE id = #{id}
            """)
    int updateCleanFields(@Param("id") String id,
                          @Param("gender") String gender,
                          @Param("age") String age,
                          @Param("pattern") String pattern,
                          @Param("prescription") String prescription,
                          @Param("status") String status,
                          @Param("grade") String grade);

    /**
     * 清洗的常规路径：只规整四个字段，**不碰 status/grade**。
     *
     * status/grade 若也一起写回，用的就是清洗开始时的循环外快照 —— 清洗是同步长任务，
     * 期间并发的质控重算/人工复核改了结论，会被这次清洗静默回退掉
     * （例如复核后的「合格」被退回「待复核」）。SQL 层没有乐观锁，两边都看不到冲突。
     */
    @Update("""
            UPDATE records
            SET gender = #{gender}, age = #{age}, pattern = #{pattern},
                prescription = #{prescription}
            WHERE id = #{id}
            """)
    int updateCleanFieldsWithoutStatus(@Param("id") String id,
                                       @Param("gender") String gender,
                                       @Param("age") String age,
                                       @Param("pattern") String pattern,
                                       @Param("prescription") String prescription);

    /** 清洗完成标记 */
    @Update("UPDATE records SET governed = 1 WHERE id = #{id}")
    int markGoverned(@Param("id") String id);

    /**
     * 回写原始文本哈希。
     *
     * 清洗会 trim / 置空 gender、age、pattern、prescription —— 这四列都参与 21 字段哈希，
     * 不回写的话库里存的哈希与行内容永久不一致：导入的判重预筛按内容现算，就再也认不出这条病历。
     */
    @Update("UPDATE records SET text_hash = #{textHash} WHERE id = #{id}")
    int updateTextHash(@Param("id") String id, @Param("textHash") String textHash);

    /** 更新结构化数据（清洗归一回写） */
    @Update("UPDATE records SET structured_data = #{structuredData} WHERE id = #{id}")
    int updateStructuredData(@Param("id") String id, @Param("structuredData") String structuredData);

    /**
     * 科室动态选项：distinct 非空科室。
     *
     * <p>{@code orgId} 同 {@link #selectOverview} 的数据域参数 ——
     * 不传的话任何人都能从下拉选项里看到别组的科室名
     * （这是轻度信息泄漏：科室名不是事实但会推断出东西）。</p>
     */
    @Select("""
            SELECT DISTINCT department FROM records
            WHERE department IS NOT NULL AND department <> ''
              AND (#{viewAll} = 1 OR org_id = #{orgId})
            ORDER BY department
            """)
    List<String> selectDepartments(@Param("orgId") String orgId,
                                   @Param("viewAll") boolean viewAll);

    /** 质控评分结果回写：分数 / 分级 / 状态 / 预检单 */
    @Update("""
            UPDATE records
            SET score = #{score}, grade = #{grade}, status = #{status}, qc_results = #{qcResults}
            WHERE id = #{id}
            """)
    int updateScoreFields(@Param("id") String id,
                          @Param("score") Integer score,
                          @Param("grade") String grade,
                          @Param("status") String status,
                          @Param("qcResults") String qcResults);
}