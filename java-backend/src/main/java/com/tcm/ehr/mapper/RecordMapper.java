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