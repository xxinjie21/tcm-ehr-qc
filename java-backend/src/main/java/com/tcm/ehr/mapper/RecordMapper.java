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
     * <p>{@code grade} 是<b>数据域</b>参数（取值来自
     * {@link com.tcm.ehr.common.utils.RecordFilter#domainGrade}）：审核员传「待复核」，
     * 只在这个域内聚合；管理员传 {@code null}，条件失效即全库。
     * 没有它，审核员调 {@code /api/stats/overview} 就能读到全库的分级分布。</p>
     */
    @Select("""
            SELECT
                COUNT(*) AS totalRecords,
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualifiedCount,
                COALESCE(SUM(CASE WHEN grade = '待复核' THEN 1 ELSE 0 END), 0) AS pendingReviewCount,
                COALESCE(SUM(CASE WHEN grade = '无效' THEN 1 ELSE 0 END), 0) AS invalidCount
            FROM records
            WHERE (#{grade} IS NULL OR grade = #{grade})
            """)
    Map<String, Object> selectOverview(@Param("grade") String grade);

    /** 清洗状态统计：合格总数/已清洗/待清洗 */
    @Select("""
            SELECT
                COALESCE(SUM(CASE WHEN grade = '合格' THEN 1 ELSE 0 END), 0) AS qualified,
                COALESCE(SUM(CASE WHEN grade = '合格' AND governed = 1 THEN 1 ELSE 0 END), 0) AS governedCount,
                COALESCE(SUM(CASE WHEN grade = '合格' AND governed = 0 THEN 1 ELSE 0 END), 0) AS pendingGovern
            FROM records
            """)
    Map<String, Object> selectGovernanceStats();

    /** 清洗后的字段修复（trim/空值清理/状态标记） */
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

    /** 清洗完成标记 */
    @Update("UPDATE records SET governed = 1 WHERE id = #{id}")
    int markGoverned(@Param("id") String id);

    /** 更新结构化数据（清洗归一回写） */
    @Update("UPDATE records SET structured_data = #{structuredData} WHERE id = #{id}")
    int updateStructuredData(@Param("id") String id, @Param("structuredData") String structuredData);

    /**
     * 科室动态选项：distinct 非空科室。
     *
     * <p>{@code grade} 同 {@link #selectOverview} 的数据域参数 ——
     * 不传的话审核员能从下拉选项里看到自己域外的科室名。</p>
     */
    @Select("""
            SELECT DISTINCT department FROM records
            WHERE department IS NOT NULL AND department <> ''
              AND (#{grade} IS NULL OR grade = #{grade})
            ORDER BY department
            """)
    List<String> selectDepartments(@Param("grade") String grade);

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