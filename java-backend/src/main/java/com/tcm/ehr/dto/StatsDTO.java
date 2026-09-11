package com.tcm.ehr.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class StatsDTO {

    /** disease（疾病频次）/ pattern（证候分布）/ prescription（方剂+中药频次） */
    private String type;

    /** 统计范围（圈定的病历ID），空=按filters/全量 */
    private List<String> recordIds;

    /** 筛选条件（复用查询1字段：department/dateRange/pattern），recordIds为空时生效 */
    private Map<String, Object> filters;
}
