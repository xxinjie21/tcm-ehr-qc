package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
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
     * 指标卡聚合（管理员「看全部」路径）：按 grade 口径对**全部机构**聚合。
     *
     * 只允许管理员调用（Service 层按 {@code RequestUtils.viewAllOrgs()} 分支），
     * 口径与 {@link com.tcm.ehr.common.utils.RecordFilter} 的「管理员 = 全部」一致。
     */
    @Select("""
            SELECT
                COUNT(*) AS totalRecords,
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualifiedCount,
                COALESCE(SUM(CASE WHEN grade = '待复核' THEN 1 ELSE 0 END), 0) AS pendingReviewCount,
                COALESCE(SUM(CASE WHEN grade = '无效' THEN 1 ELSE 0 END), 0) AS invalidCount
            FROM records
            """)
    Map<String, Object> selectOverviewAll();

    /**
     * 指标卡聚合（数据域路径）：{@code WHERE org_id = ?}，只统计本机构。
     *
     * ⚠️ <b>fail-closed</b>：这里永远是「org_id 等值过滤」，不存在任何「没传 org → 不限」
     * 的分支 —— orgId 传哨兵值（RecordFilter.NO_GROUP_SENTINEL）、空串或
     * {@code null} 时自然查到 0 行，绝不会退化成全表（那会跨机构泄漏）。
     * 与 {@link com.tcm.ehr.common.utils.RecordFilter#operatorScope} 同口径。
     *
     * orgId 取自 {@link com.tcm.ehr.common.utils.RecordFilter#domainOrgId()}。
     */
    @Select("""
            SELECT
                COUNT(*) AS totalRecords,
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualifiedCount,
                COALESCE(SUM(CASE WHEN grade = '待复核' THEN 1 ELSE 0 END), 0) AS pendingReviewCount,
                COALESCE(SUM(CASE WHEN grade = '无效' THEN 1 ELSE 0 END), 0) AS invalidCount
            FROM records
            WHERE org_id = #{orgId}
            """)
    Map<String, Object> selectOverviewOrg(@Param("orgId") String orgId);

    /**
     * 指标卡聚合（随筛选路径）：在数据域之上再叠加用户筛选（科室 / 就诊区间），
     * 供首页 3 张指标卡「随上方筛选变化」。
     *
     * 只算 <b>总数 / 合格数 / 无效数</b>三项 —— 待复核数仍走无参口径
     * （{@link #selectOverviewAll} / {@link #selectOverviewOrg}）：待复核是待办条入口，
     * 点击跳到全量复核列表，数字必须与列表一致，不能随筛选漂移。
     *
     * @param wrapper 只含数据域 + 用户筛选的条件，**不含 ORDER BY**
     *                （由 {@link com.tcm.ehr.common.utils.RecordFilter#buildForAggregate} 产出）
     */
    @Select("""
            SELECT
                COUNT(*) AS totalRecords,
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualifiedCount,
                COALESCE(SUM(CASE WHEN grade = '无效' THEN 1 ELSE 0 END), 0) AS invalidCount
            FROM records
            ${ew.customSqlSegment}
            """)
    Map<String, Object> selectOverviewFiltered(@Param(Constants.WRAPPER) Wrapper<Record> wrapper);

    /**
     * 清洗状态统计（管理员「看全部」路径）：全部机构的合格/已清洗/待清洗。
     */
    @Select("""
            SELECT
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualified,
                COALESCE(SUM(CASE WHEN grade = '合格' AND governed = 1 THEN 1 ELSE 0 END), 0) AS governedCount,
                COALESCE(SUM(CASE WHEN grade = '合格' AND governed = 0 THEN 1 ELSE 0 END), 0) AS pendingGovern
            FROM records
            """)
    Map<String, Object> selectGovernanceStatsAll();

    /**
     * 清洗状态统计（数据域路径）：{@code WHERE org_id = ?}，fail-closed 口径同
     * {@link #selectOverviewOrg}。
     */
    @Select("""
            SELECT
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualified,
                COALESCE(SUM(CASE WHEN grade = '合格' AND governed = 1 THEN 1 ELSE 0 END), 0) AS governedCount,
                COALESCE(SUM(CASE WHEN grade = '合格' AND governed = 0 THEN 1 ELSE 0 END), 0) AS pendingGovern
            FROM records
            WHERE org_id = #{orgId}
            """)
    Map<String, Object> selectGovernanceStatsOrg(@Param("orgId") String orgId);

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
     * 科室动态选项（管理员「看全部」路径）：全部机构的非空科室。
     */
    @Select("""
            SELECT DISTINCT department FROM records
            WHERE department IS NOT NULL AND department <> ''
            ORDER BY department
            """)
    List<String> selectDepartmentsAll();

    /**
     * 科室动态选项（数据域路径）：{@code WHERE org_id = ?}，fail-closed 口径同
     * {@link #selectOverviewOrg}（空/哨兵 orgId 自然空集，不会查到别组科室名）。
     */
    @Select("""
            SELECT DISTINCT department FROM records
            WHERE department IS NOT NULL AND department <> ''
              AND org_id = #{orgId}
            ORDER BY department
            """)
    List<String> selectDepartmentsOrg(@Param("orgId") String orgId);

    /**
     * 看板质控趋势：按就诊月份聚合（性能审查 P2-10，**SQL 侧聚合**）。
     *
     * <b>替代了什么</b>：原实现把命中病历（4 万行 × 5 列投影）整份物化进堆，再遍历一遍按
     * {@code LocalDateTime.toString().substring(0,7)} 分月计数 —— 回传行数 = 病历数。现在
     * 中间计数留在 DB，回传行数 = 月份数（≤ 记录跨度的月数）。
     *
     * <b>口径与 Java 实现逐条对齐</b>：visit_time 为空的行不进趋势
     * （{@code DATE_FORMAT(NULL,…)} 产出 NULL 分组，由调用方丢弃，等价于原来的
     * 「monthOf 返回 null 就 continue」）；合格 / 待复核按 grade 精确等值，
     * 与 {@code "合格".equals(r.getGrade())} 同口径（grade 为 NULL 时不匹配任何一档）。
     *
     * {@code DATE_FORMAT(visit_time,'%Y-%m')} 与 {@code LocalDateTime.toString()} 的前 7 位
     * 同为 {@code yyyy-MM}。
     *
     * <b>只留已质控的月份</b>：{@code HAVING COUNT(grade) > 0} —— 某月全部病历都还没质控
     * （grade 全 NULL）时该月不出现在趋势里。否则一批刚导入、日期落在更近月份的病历
     * 会把「最近 12 个月」窗口整体顶到那批未质控的月份上，两条线全压 0、看着像没数据
     * （2026-10-15 导入 500 条 visit_time 散落到 2025 各月即此况）。
     *
     * @param wrapper 只含数据域 + 用户筛选的条件，**不含 ORDER BY**
     *                （由 {@link com.tcm.ehr.common.utils.RecordFilter#buildForAggregate} 产出）
     * @return 每行 {@code {month, total, qualified, pendingReview}}，按月份升序
     */
    @Select("""
            SELECT DATE_FORMAT(visit_time, '%Y-%m') AS month,
                   COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualified,
                   COALESCE(SUM(CASE WHEN grade = '待复核' THEN 1 ELSE 0 END), 0) AS pendingReview
            FROM records
            ${ew.customSqlSegment}
            GROUP BY DATE_FORMAT(visit_time, '%Y-%m')
            HAVING COUNT(grade) > 0
            ORDER BY month ASC
            """)
    List<Map<String, Object>> selectTrendByMonth(@Param(Constants.WRAPPER) Wrapper<Record> wrapper);

    /**
     * 看板科室合格率：按科室聚合（性能审查 P2-10，**SQL 侧聚合**）。
     *
     * department 为 NULL / 空白 / 带首尾空格的形态都按原样分组，由调用方归一到
     * 「未填科室」与 {@code trim()} 后的键并累加 —— 与 Java 实现「先归一 key、再累加」
     * 的结果逐位一致（SQL 侧不做归一，是为了不在 SQL 里写中文业务字面量）。
     *
     * @param wrapper 只含数据域 + 用户筛选的条件，**不含 ORDER BY**
     * @return 每行 {@code {department, total, qualified}}，顺序由调用方按总数降序重排
     */
    @Select("""
            SELECT department AS department,
                   COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualified
            FROM records
            ${ew.customSqlSegment}
            GROUP BY department
            """)
    List<Map<String, Object>> selectDeptRates(@Param(Constants.WRAPPER) Wrapper<Record> wrapper);

    /**
     * 看板评分分布：按分数聚合（性能审查 P2-10，**SQL 侧聚合**）。
     *
     * 只回「分数 → 条数」，分档（90+ / 80-89 / …）仍由调用方按原 bucketOf 判定 ——
     * 分档边界写在 SQL 的 CASE 里会在改动分档时形成两处真源。行数上界是
     * 出现过的不同分数个数（≤ 101），远小于病历数。
     *
     * score 为空的行会形成一个 NULL 分组，由调用方丢弃 ——
     * 与原来「{@code score == null} 不计」一致。
     *
     * @param wrapper 只含数据域 + 用户筛选的条件，**不含 ORDER BY**
     * @return 每行 {@code {score, cnt}}
     */
    @Select("""
            SELECT score AS score, COUNT(*) AS cnt
            FROM records
            ${ew.customSqlSegment}
            GROUP BY score
            """)
    List<Map<String, Object>> selectScoreCounts(@Param(Constants.WRAPPER) Wrapper<Record> wrapper);

    /**
     * 质控评分结果回写：分数 / 分级 / 状态 / 预检单。
     *
     * <b>为什么要在这里撤销 governed</b>（2026-10-21 一致性修复）：
     * 清洗把 governed 置 1 的前提是「该病历质控合格」。但质控可以在清洗之后重跑，
     * 把一条原本合格的病历改判为待复核 / 无效 —— 此时 governed 若不撤销，
     * 这条病历会落进一个「既不可导出、也不在待清洗队列」的黑洞：
     * 导出要求 {@code grade='合格' AND governed=1}，待清洗要求 {@code grade='合格' AND governed=0}，
     * 两边都不匹配。实测存量有 160 条待复核病历处于该状态。
     *
     * 故：grade 不是「合格」时把 governed 归零；是「合格」时不动它 ——
     * 不能顺便置 1，否则会绕过清洗流程伪造「已清洗」（清洗才该置 1）。
     */
    @Update("""
            UPDATE records
            SET score = #{score}, grade = #{grade}, status = #{status}, qc_results = #{qcResults},
                governed = CASE WHEN #{grade} = '合格' THEN governed ELSE 0 END
            WHERE id = #{id}
            """)
    int updateScoreFields(@Param("id") String id,
                          @Param("score") Integer score,
                          @Param("grade") String grade,
                          @Param("status") String status,
                          @Param("qcResults") String qcResults);

    /**
     * 单类实体词频 TopN（性能审查 P0-3#3）：把 structured_data 里的一个实体数组
     * 在 <b>DB 侧</b> 用 JSON_TABLE 展开成行、{@code GROUP BY} 计数，只把 TopN 带回 JVM。
     *
     * <b>替代了什么</b>：原实现把命中的全部病历（4 万行时含 ~3.7KB/行的 structured_data）
     * 一次物化进堆，再在 Java 里逐条解析并聚合 —— 4 万行下约 150MB 堆峰值 + 4 万次 JSON 解析，
     * 且 TopN 之前的中间计数全留在 JVM。现在中间结果留在 DB，返回行数恒为 TopN。
     *
     * <b>取值口径与改造前的 Java 实现一致</b>（原实现已随本次下沉一并删除，其口径即下面三条）：
     * 对象元素取 content（为空回退 name，对应 Entity / Herb 两种形态）；
     * 纯字符串元素（旧格式）取元素自身；其余（数字 / null / 空串）一律不计。
     * 数组缺失、structured_data 为 NULL、根不是对象时该记录不产出任何词
     * —— 与原实现「解析不出就按没抽到处理」同结论。
     *
     * <b>排序规则显式 utf8mb4_bin</b>：Java 侧 counter.merge 是精确匹配
     * （等价 _bin），而 JSON_TABLE 派生出的字符串与 records 各列的
     * utf8mb4_unicode_ci 不同源，两路混用（见 {@link #selectPatternFreq}）会让
     * UNION 直接报 1712（Illegal mix of collations）。
     *
     * <b>同次数的次序为词名升序</b>：原 Java 实现的 Javadoc 早已声明「同次数按名称升序
     * 保证稳定」，但代码只按次数排、同次数落到 LinkedHashMap 的插入序（即病历顺序）。
     * 这里真正按词名升序，让实现与既有声明一致 —— 代价是并列词的展示集合可能与改前不同。
     *
     * ⚠️ jsonPath 用 {@code ${}} 做文本替换：MySQL 的 JSON_TABLE 要求路径是
     * <b>字面量</b>（绑定参数直接语法错误），因此只允许传入本仓的编译期常量；
     * 调用方 StatsServiceImpl 另有一层白名单校验，绝不接受外部输入。
     *
     * @param wrapper  只含数据域 + 用户筛选的条件，**不含 ORDER BY**
     *                 （由 {@link com.tcm.ehr.common.utils.RecordFilter#buildForAggregate} 产出）
     * @param jsonPath structured_data 里的数组路径，如 {@code $.diseases[*]}
     * @param limit    TopN
     * @return 每行 {@code {term: 词, cnt: 次数}}，按次数降序、词名升序
     */
    @Select("""
            SELECT TRIM(u.term) AS term, COUNT(*) AS cnt
            FROM (
                SELECT CASE
                         WHEN JSON_TYPE(jt.elem) = 'OBJECT'
                           THEN COALESCE(NULLIF(jt.content, ''), NULLIF(jt.name, ''))
                         WHEN JSON_TYPE(jt.elem) = 'STRING' THEN jt.plain
                       END COLLATE utf8mb4_bin AS term
                FROM records r
                JOIN JSON_TABLE(r.structured_data, '${jsonPath}' COLUMNS(
                       elem    JSON         PATH '$',
                       content VARCHAR(512) PATH '$.content',
                       name    VARCHAR(512) PATH '$.name',
                       plain   VARCHAR(512) PATH '$'
                     )) jt ON TRUE
                ${ew.customSqlSegment}
            ) u
            WHERE u.term IS NOT NULL AND TRIM(u.term) <> ''
            GROUP BY TRIM(u.term)
            ORDER BY cnt DESC, TRIM(u.term) ASC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectTermFreq(@Param(Constants.WRAPPER) Wrapper<Record> wrapper,
                                             @Param("jsonPath") String jsonPath,
                                             @Param("limit") int limit);

    /**
     * 看板四类实体（疾病 / 症状 / 方剂 / 中药）词频 TopN，**一趟扫完**（性能审查 P0-3#3）。
     *
     * 为什么不复用 {@link #selectTermFreq} 调四次：JSON_TABLE 对每一行都要解析
     * 一次 structured_data，调四次就是四次全表解析。这里用<b>同级 NESTED PATH</b>
     * 一次调用展开 4 个数组 —— MySQL 对同级嵌套路径产出的是各路径行的<b>并集</b>，
     * 于是每条病历的 JSON 只解析一次，靠「哪一列非空」回推 kind。
     *
     * <b>实测（4 万行 / structured_data 合计 157MB）</b>：
     * 本方法 <b>1.05~1.61s</b>；改为四次 {@link #selectTermFreq} 为 <b>3.26~3.83s</b>；
     * 改为一趟 UNION ALL 五路为 <b>5.07~5.08s</b>（UNION 会把各路结果全物化后才分组）。
     * 原 Java 实现同场景约 9~10s 且堆峰值约 320MB。
     *
     * 取值口径与 {@link #selectTermFreq} 完全一致；差别只有一处：为兼容旧格式字符串元素，
     * 这里无法按元素类型分派（{@code PATH '$.content'} 对字符串元素直接给 NULL），
     * 故只认对象形态的 content / name。当前数据中 4 类元素全部为对象
     * （已核对：diseases / symptoms / formulaList / herbs 的元素 100% 为 OBJECT）。
     *
     * 证候（patternList）不在这里 —— 它多一段「原始 pattern 列兜底」，
     * 见 {@link #selectPatternFreq}。
     *
     * @param wrapper 只含数据域 + 用户筛选的条件，**不含 ORDER BY**
     * @param limit   每类 TopN
     * @return 每行 {@code {kind: disease|symptom|formula|herb, term: 词, cnt: 次数}}
     */
    @Select("""
            SELECT kind, term, cnt FROM (
                SELECT kind, term, cnt,
                       ROW_NUMBER() OVER (PARTITION BY kind ORDER BY cnt DESC, term ASC) AS rn
                FROM (
                    SELECT raw.kind AS kind, TRIM(raw.term) AS term, COUNT(*) AS cnt
                    FROM (
                        SELECT CASE
                                 WHEN jt.d_term IS NOT NULL THEN 'disease'
                                 WHEN jt.s_term IS NOT NULL THEN 'symptom'
                                 WHEN jt.f_term IS NOT NULL THEN 'formula'
                                 ELSE 'herb'
                               END AS kind,
                               COALESCE(jt.d_term, jt.s_term, jt.f_term, jt.h_term)
                                 COLLATE utf8mb4_bin AS term
                        FROM records r
                        JOIN JSON_TABLE(r.structured_data, '$' COLUMNS(
                               NESTED PATH '$.diseases[*]'    COLUMNS(d_term VARCHAR(512) PATH '$.content'),
                               NESTED PATH '$.symptoms[*]'    COLUMNS(s_term VARCHAR(512) PATH '$.content'),
                               NESTED PATH '$.formulaList[*]' COLUMNS(f_term VARCHAR(512) PATH '$.content'),
                               NESTED PATH '$.herbs[*]'       COLUMNS(h_term VARCHAR(512) PATH '$.name')
                             )) jt ON TRUE
                        ${ew.customSqlSegment}
                    ) raw
                    WHERE raw.term IS NOT NULL AND TRIM(raw.term) <> ''
                    GROUP BY raw.kind, TRIM(raw.term)
                ) agg
            ) ranked
            WHERE rn <= #{limit}
            ORDER BY kind, rn
            """)
    List<Map<String, Object>> selectTermFreqMulti(@Param(Constants.WRAPPER) Wrapper<Record> wrapper,
                                                  @Param("limit") int limit);

    /**
     * 证候词频 TopN（性能审查 P0-3#3）：patternList 主路径 + **原始 pattern 列兜底**。
     *
     * 兜底存在的理由与 StatsServiceImpl.agg 原注释一致：未重解析的旧病历
     * patternList 为空，此时按顿号 / 逗号 / 分号切分原始辨证结论，避免「证候词频整块缺失」。
     * 这是过渡分支 —— 归一主链的证候回补（EntityNormalizer.backfillPatterns）生效、
     * 数据重解析完成后即可删除。
     *
     * 两路的分界条件：patternList 键缺失 / 非数组 / 长度为 0。与 Java 侧
     * 「抽取结果为空则回退」等价；唯一差别是 Java 还会在「数组非空但元素内容全为空白」时回退
     * —— 该形态当前数据中不出现，且属畸形数据。
     *
     * 切分用递归 CTE 造序号（MySQL 没有字符串拆分函数），上限 64 段：一个证候串出现
     * 64 段以上不现实，且超出部分只影响这条畸形记录的计数。切分前先把 4 种分隔符归一成逗号，
     * 与 Java 的正则 {@code [、，,；;]} 同口径。
     *
     * @param wrapper 只含数据域 + 用户筛选的条件，**不含 ORDER BY**
     * @param limit   TopN
     * @return 每行 {@code {term: 证候, cnt: 次数}}，按次数降序、词名升序
     */
    @Select("""
            SELECT TRIM(u.term) AS term, COUNT(*) AS cnt
            FROM (
                SELECT CASE
                         WHEN JSON_TYPE(jt.elem) = 'OBJECT'
                           THEN COALESCE(NULLIF(jt.content, ''), NULLIF(jt.name, ''))
                         WHEN JSON_TYPE(jt.elem) = 'STRING' THEN jt.plain
                       END COLLATE utf8mb4_bin AS term
                FROM records r
                JOIN JSON_TABLE(r.structured_data, '$.patternList[*]' COLUMNS(
                       elem    JSON         PATH '$',
                       content VARCHAR(512) PATH '$.content',
                       name    VARCHAR(512) PATH '$.name',
                       plain   VARCHAR(512) PATH '$'
                     )) jt ON TRUE
                ${ew.customSqlSegment}

                UNION ALL

                SELECT TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(x.norm, ',', seq.n), ',', -1))
                         COLLATE utf8mb4_bin AS term
                FROM (
                    SELECT REPLACE(REPLACE(REPLACE(REPLACE(r.pattern, '、', ','), '，', ','), '；', ','), ';', ',') AS norm,
                           (JSON_EXTRACT(r.structured_data, '$.patternList') IS NULL
                            OR JSON_TYPE(JSON_EXTRACT(r.structured_data, '$.patternList')) <> 'ARRAY'
                            OR JSON_LENGTH(JSON_EXTRACT(r.structured_data, '$.patternList')) = 0) AS no_struct
                    FROM records r
                    ${ew.customSqlSegment}
                ) x
                JOIN (
                    WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 64)
                    SELECT n FROM s
                ) seq ON seq.n <= 1 + LENGTH(x.norm) - LENGTH(REPLACE(x.norm, ',', ''))
                WHERE x.no_struct AND x.norm <> ''
            ) u
            WHERE u.term IS NOT NULL AND TRIM(u.term) <> ''
            GROUP BY TRIM(u.term)
            ORDER BY cnt DESC, TRIM(u.term) ASC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectPatternFreq(@Param(Constants.WRAPPER) Wrapper<Record> wrapper,
                                                @Param("limit") int limit);
}
