package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 诊疗逻辑一致性检查请求。
 */
@Data
public class LogicCheckDTO {

    private List<Map<String, Object>> patternList;
    private List<Map<String, Object>> treatmentList;
    private List<Map<String, Object>> formulaList;
    private List<Map<String, Object>> herbList;
}
