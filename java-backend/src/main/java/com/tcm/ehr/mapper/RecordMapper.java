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
     * <p>只允许管理员调用（Service 层按 {@code RequestUtils.viewAllOrgs()} 分支），
     * 口径与 {@link com.tcm.ehr.common.utils.RecordFilter} 的「管理员 = 全部」一致。</p>
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
     * <p>⚠️ <b>fail-closed</b>：这里永远是「org_id 等值过滤」，不存在任何「没传 org → 不限」
     * 的分支 —— {@code orgId} 传哨兵值（{@code RecordFilter.NO_GROUP_SENTINEL}）、空串或
     * {@code null} 时自然查到 0 行，绝不会退化成全表（那会跨机构泄漏）。
     * 与 {@link com.tcm.ehr.common.utils.RecordFilter#operatorScope} 同口径。</p>
     *
     * <p>{@code orgId} 取自 {@link com.tcm.ehr.common.utils.RecordFilter#domainOrgId()}。</p>
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

    /**
     * 单类实体词频 TopN（性能审查 P0-3#3）：把 {@code structured_data} 里的一个实体数组
     * 在 <b>DB 侧</b> 用 {@code JSON_TABLE} 展开成行、{@code GROUP BY} 计数，只把 TopN 带回 JVM。
     *
     * <p><b>替代了什么</b>：原实现把命中的全部病历（4 万行时含 ~3.7KB/行的 structured_data）
     * 一次物化进堆，再在 Java 里逐条解析并聚合 —— 4 万行下约 150MB 堆峰值 + 4 万次 JSON 解析，
     * 且 TopN 之前的中间计数全留在 JVM。现在中间结果留在 DB，返回行数恒为 TopN。</p>
     *
     * <p><b>取值口径与改造前的 Java 实现一致</b>（原实现已随本次下沉一并删除，其口径即下面三条）：
     * 对象元素取 {@code content}（为空回退 {@code name}，对应 Entity / Herb 两种形态）；
     * 纯字符串元素（旧格式）取元素自身；其余（数字 / null / 空串）一律不计。
     * 数组缺失、{@code structured_data} 为 NULL、根不是对象时该记录不产出任何词
     * —— 与原实现「解析不出就按没抽到处理」同结论。</p>
     *
     * <p><b>排序规则显式 {@code utf8mb4_bin}</b>：Java 侧 {@code counter.merge} 是精确匹配
     * （等价 _bin），而 {@code JSON_TABLE} 派生出的字符串与 {@code records} 各列的
     * {@code utf8mb4_unicode_ci} 不同源，两路混用（见 {@link #selectPatternFreq}）会让
     * {@code UNION} 直接报 1712（Illegal mix of collations）。</p>
     *
     * <p><b>同次数的次序为词名升序</b>：原 Java 实现的 Javadoc 早已声明「同次数按名称升序
     * 保证稳定」，但代码只按次数排、同次数落到 LinkedHashMap 的插入序（即病历顺序）。
     * 这里真正按词名升序，让实现与既有声明一致 —— 代价是并列词的展示集合可能与改前不同。</p>
     *
     * <p>⚠️ {@code jsonPath} 用 {@code ${}} 做文本替换：MySQL 的 {@code JSON_TABLE} 要求路径是
     * <b>字面量</b>（绑定参数直接语法错误），因此只允许传入本仓的编译期常量；
     * 调用方 {@code StatsServiceImpl} 另有一层白名单校验，绝不接受外部输入。</p>
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
     * <p>为什么不复用 {@link #selectTermFreq} 调四次：{@code JSON_TABLE} 对每一行都要解析
     * 一次 {@code structured_data}，调四次就是四次全表解析。这里用<b>同级 NESTED PATH</b>
     * 一次调用展开 4 个数组 —— MySQL 对同级嵌套路径产出的是各路径行的<b>并集</b>，
     * 于是每条病历的 JSON 只解析一次，靠「哪一列非空」回推 {@code kind}。
     *
     * <p><b>实测（4 万行 / structured_data 合计 157MB）</b>：
     * 本方法 <b>1.05~1.61s</b>；改为四次 {@link #selectTermFreq} 为 <b>3.26~3.83s</b>；
     * 改为一趟 UNION ALL 五路为 <b>5.07~5.08s</b>（UNION 会把各路结果全物化后才分组）。
     * 原 Java 实现同场景约 9~10s 且堆峰值约 320MB。</p>
     *
     * <p>取值口径与 {@link #selectTermFreq} 完全一致；差别只有一处：为兼容旧格式字符串元素，
     * 这里无法按元素类型分派（{@code PATH '$.content'} 对字符串元素直接给 NULL），
     * 故只认对象形态的 {@code content} / {@code name}。当前数据中 4 类元素全部为对象
     * （已核对：diseases / symptoms / formulaList / herbs 的元素 100% 为 OBJECT）。</p>
     *
     * <p>证候（{@code patternList}）不在这里 —— 它多一段「原始 pattern 列兜底」，
     * 见 {@link #selectPatternFreq}。</p>
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
     * 证候词频 TopN（性能审查 P0-3#3）：{@code patternList} 主路径 + **原始 {@code pattern} 列兜底**。
     *
     * <p>兜底存在的理由与 {@code StatsServiceImpl.agg} 原注释一致：未重解析的旧病历
     * {@code patternList} 为空，此时按顿号 / 逗号 / 分号切分原始辨证结论，避免「证候词频整块缺失」。
     * 这是过渡分支 —— 归一主链的证候回补（{@code EntityNormalizer.backfillPatterns}）生效、
     * 数据重解析完成后即可删除。</p>
     *
     * <p>两路的分界条件：{@code patternList} 键缺失 / 非数组 / 长度为 0。与 Java 侧
     * 「抽取结果为空则回退」等价；唯一差别是 Java 还会在「数组非空但元素内容全为空白」时回退
     * —— 该形态当前数据中不出现，且属畸形数据。</p>
     *
     * <p>切分用递归 CTE 造序号（MySQL 没有字符串拆分函数），上限 64 段：一个证候串出现
     * 64 段以上不现实，且超出部分只影响这条畸形记录的计数。切分前先把 4 种分隔符归一成逗号，
     * 与 Java 的正则 {@code [、，,；;]} 同口径。</p>
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
