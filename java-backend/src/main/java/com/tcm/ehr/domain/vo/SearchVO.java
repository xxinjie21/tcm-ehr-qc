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
        /**
         * 该条结构化数据是否含人工修改。
         *
         * <p>来自 {@code structured_data._meta.manuallyEdited}，由后端在两个<b>人工</b>写入口
         * （复核提交修正、{@code PUT /api/records/{id}}）打上。清洗归一这类自动流程不打，
         * 且遇到人工修改过的病历会<b>跳过</b>归一（方案 A），所以这个标记能稳定成立。</p>
         *
         * <p>用途有二：列表上一眼看出「这条不是模型原样抽的」；以及评估模型准确率时
         * 排除人工补过的数据。</p>
         */
        private Boolean manuallyEdited;

        public Item() {
        }

        public Item(String id, String summary, String grade,
                    LocalDateTime visitTime, String gender, String age, Integer score) {
            this(id, summary, grade, visitTime, gender, age, score, null);
        }

        public Item(String id, String summary, String grade,
                    LocalDateTime visitTime, String gender, String age, Integer score,
                    Boolean manuallyEdited) {
            this.id = id;
            this.summary = summary;
            this.grade = grade;
            this.visitTime = visitTime;
            this.gender = gender;
            this.age = age;
            this.score = score;
            this.manuallyEdited = manuallyEdited;
        }
    }
}
