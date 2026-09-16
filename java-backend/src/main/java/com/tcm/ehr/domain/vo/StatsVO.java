package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统计结果（按type填充对应字段）
 * disease -> statistics；pattern -> distribution；prescription -> formulaStats + herbStats
 */
@Data
public class StatsVO {

    /** [{disease, count}] */
    private List<Map<String, Object>> statistics = new ArrayList<>();

    /** [{pattern, count}] */
    private List<Map<String, Object>> distribution = new ArrayList<>();

    /** [{formula, count}] */
    private List<Map<String, Object>> formulaStats = new ArrayList<>();

    /** [{herb, count}] */
    private List<Map<String, Object>> herbStats = new ArrayList<>();
}
