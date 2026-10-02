package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 多条件病历查询响应：总数 + 摘要列表。
 */
@Data
public class SearchVO {

    private long total;
    private List<Item> records = new ArrayList<>();

    @Data
    public static class Item {
        private String id;
        /** 原始文本摘要（主诉/中医诊断截取） */
        private String summary;
        /**
         * 分级：合格 / 待复核 / 无效。
         * 列表页需要直接展示分级，随查询一并返回可免去逐条二次请求。
         */
        private String grade;
        /**
         * 接诊时间。
         * 列表页展示到日、悬浮展示到秒，故此处下发完整精度（秒级），由前端按场景格式化。
         * 同时它是列表的排序键（见 RecordFilter），避免「排序键与所见图列不一致」。
         */
        private LocalDateTime visitTime;
        /** 性别，与 age 合成一列展示 */
        private String gender;
        /** 年龄，与 gender 合成一列展示 */
        private String age;
        /**
         * 质控评分（0~100，未评分时为 null）。
         *
         * <p>与 {@code grade} 成对下发：分级是结论、评分是量值，两者分开算、分开看。
         * 列表页要同时看到「多少分」与「算不算合格」—— 只给分级就看不出差几分。</p>
         */
        private Integer score;

        public Item() {
        }

        public Item(String id, String summary, String grade,
                    LocalDateTime visitTime, String gender, String age, Integer score) {
            this.id = id;
            this.summary = summary;
            this.grade = grade;
            this.visitTime = visitTime;
            this.gender = gender;
            this.age = age;
            this.score = score;
        }
    }
}
