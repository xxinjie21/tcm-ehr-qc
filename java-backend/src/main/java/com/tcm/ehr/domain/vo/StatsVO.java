package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计结果（按type填充对应字段）
 * disease -> statistics；pattern -> distribution；prescription -> formulaStats + herbStats。
 *
 * <p>.2 扩展（/api/stats/extra）：{@code trend} 按月合格率/待复核、
 * {@code departmentRates} 科室合格率、{@code scoreDistribution} 评分分布、{@code dictionary} 词典规模。</p>
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

    /** 质控趋势：按就诊月份升序，最近 12 个月 */
    private List<TrendPoint> trend = new ArrayList<>();

    /** 科室合格率*/
    private List<DeptRate> departmentRates = new ArrayList<>();

    /** 评分分布直方图：90+ / 80-89 / 70-79 / 60-69 / 60以下 */
    private List<Bucket> scoreDistribution = new ArrayList<>();

    /** 词典规模：5 类术语数量 */
    private Map<String, Integer> dictionary = new LinkedHashMap<>();

    @Data
    public static class TrendPoint {
        private String month;
        private long total;
        private long qualified;
        private double qualifiedRate;
        private long pendingReview;
    }

    @Data
    public static class DeptRate {
        private String department;
        private long total;
        private long qualified;
        private double qualifiedRate;
    }

    @Data
    public static class Bucket {
        private String bucket;
        private long count;
    }
}
