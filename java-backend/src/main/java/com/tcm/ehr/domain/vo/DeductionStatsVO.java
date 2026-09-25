package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 范围扣分维度聚合（出参，批P）：把范围内各病历的扣分明细按维度/明细汇总。
 *
 * <p>数据优先读 {@code records.qc_results}（已算过），缺失则按当前规则现算；
 * 扫描上限沿用质控图谱口径，超限 {@code truncated=true}。</p>
 */
@Data
public class DeductionStatsVO {

    /** 实际扫描病历数 */
    private int scanned;
    /** 是否因上限被截断 */
    private boolean truncated;
    /** 本范围扣分总点数 */
    private int totalPoints;
    /** 按维度（核心字段缺失 / 逻辑冲突 / 格式错误 / 重复数据）汇总 */
    private List<ByType> byType = new ArrayList<>();
    /** 按明细项（维度+item）汇总，按扣分降序，取前 N */
    private List<ByItem> byItem = new ArrayList<>();
    /** 分级分布 */
    private Map<String, Integer> gradeDist = new LinkedHashMap<>();

    @Data
    public static class ByType {
        private String type;
        private int count;
        private int points;

        public ByType(String type, int count, int points) {
            this.type = type;
            this.count = count;
            this.points = points;
        }
    }

    @Data
    public static class ByItem {
        private String type;
        private String item;
        private int count;
        private int points;

        public ByItem(String type, String item, int count, int points) {
            this.type = type;
            this.item = item;
            this.count = count;
            this.points = points;
        }
    }
}
