package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 终末质控评分结果。
 */
@Data
public class ScoreResultVO {

    private int score;
    /** 合格 / 待复核 / 无效 */
    private String grade;
    private List<Deduction> deductions = new ArrayList<>();
    private List<String> logicConflicts = new ArrayList<>();
    private boolean serious;
    /** 结构化数据缺失/解析失败：本次未按结构化结果评分（各核心按"漏抽"口径扣分） */
    private boolean structuredMissing;
    private LocalDateTime checkedAt;

    @Data
    public static class Deduction {
        private String type;
        private String item;
        private int points;
        private String reason;

        public Deduction() {
        }

        public Deduction(String type, String item, int points, String reason) {
            this.type = type;
            this.item = item;
            this.points = points;
            this.reason = reason;
        }
    }
}
