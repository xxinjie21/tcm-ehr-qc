package com.tcm.ehr.mapper;

import com.tcm.ehr.entity.Record;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface RecordMapper {

    int insert(Record record);

    Record findById(String id);

    List<Record> findAll();

    List<Record> findByIds(@Param("ids") List<String> ids);

    /** 清洗后的字段修复（trim/空值清理/状态标记） */
    int updateCleanFields(@Param("id") String id,
                          @Param("gender") String gender,
                          @Param("age") String age,
                          @Param("pattern") String pattern,
                          @Param("prescription") String prescription,
                          @Param("status") String status,
                          @Param("grade") String grade);

    /** 指标卡聚合 */
    Map<String, Object> selectOverview();

    /** 治理状态统计：合格总数/已治理/待治理 */
    Map<String, Object> selectGovernanceStats();

    /** 治理完成标记 */
    int markGoverned(@Param("id") String id);

    /** 更新结构化数据（清洗归一回写） */
    int updateStructuredData(@Param("id") String id, @Param("structuredData") String structuredData);
}
