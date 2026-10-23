package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.List;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * 统计分析请求参数。
 *
 * type 用枚举式正则限定四类统计，防止任意字符串穿透到下游分支；
 * recordIds 与 filters 二选一圈定范围，都为空则按全量统计。
 *
 * ⚠️ 全量统计会扫全部病历，是大查询；调用方应优先传范围。
 */
@Data
public class StatsDTO {

    /** disease（疾病频次）/ pattern（证候分布）/ prescription（方剂+中药频次） */
    @Pattern(regexp = "^(disease|pattern|symptom|prescription)?$",
            message = "统计类型只能是 disease/pattern/symptom/prescription")
    private String type;

    /** 统计范围（圈定的病历ID），空=按filters/全量 */
    private List<String> recordIds;

    /** 筛选条件（复用查询1字段：department/dateRange/pattern），recordIds为空时生效 */
    private Map<String, Object> filters;
}
