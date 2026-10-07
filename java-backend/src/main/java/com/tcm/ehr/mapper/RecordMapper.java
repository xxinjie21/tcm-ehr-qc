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
}