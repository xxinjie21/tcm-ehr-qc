package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 标准化质量报告（批次 24）。
 *
 *
 * 刻意分成两层，因为两者的性质完全不同，混在一张表里会误导：
 *
 *
 *
 * 甲类 · 标准符合度（真正的目标）：词典规模、来源、编码覆盖率、别名质量、
 *
 * 跨类重名。这些只取决于「词表建得对不对」，与用哪份数据无关 —— 换数据集它也不变。
 *
 *
 * 乙类 · 数据集覆盖度（下限验证，不是目标）：归一率、未归一实体的构成、
 *
 * 质控封顶率。500 条是 10 个模板生成的合成数据，这层数字只用来回答「词表建全了没有」，
 * 绝不能当成真实病历上的准确率对外引用。报告页首必须带这句声明。
 */
@Data
public class StandardizationReportVO {

    /** 甲类 · 标准符合度 */
    private List<DictQuality> dictQuality = new ArrayList<>();

    /** 甲类 · 跨类重名术语（同一词出现在多本词典，类型判定会有歧义） */
    private List<String> crossTypeDuplicates = new ArrayList<>();

    /** 乙类 · 各类实体的归一情况 */
    private List<TypeCoverage> coverage = new ArrayList<>();

    /** 乙类 · 症状未归一实体的构成；四类责任方不同，不能合并看 */
    private UnmatchedBreakdown unmatched = new UnmatchedBreakdown();

    /** 乙类 · 可归一实体归一率：分母 = 已抽取、非抽取缺陷、已在症状词表内 */
    private NormalizableRate normalizable = new NormalizableRate();

    /** 乙类 · 质控分数分布与封顶率 */
    private ScoreDistribution score = new ScoreDistribution();

    /** 数据集形态提示：模板塌缩度决定这批数据能不能代表真实病历 */
    private DatasetShape dataset = new DatasetShape();

    /** 数据来源声明；页面上要原样显示，不能删 */
    private String disclaimer;

    /** 报告生成时间（yyyy-MM-dd HH:mm） */
    private String generatedAt;

    /** 单类词典的质量画像（甲类） */
    @Data
    public static class DictQuality {
        /** 术语类型 key（disease/pattern/…） */
        private String type;
        /** 中文名 */
        private String label;
        /** 词条数 */
        private int termCount;
        /** 有编码的词条数 */
        private int codedCount;
        /** 有别名的词条数 */
        private int aliasedCount;
        /** 别名与标准词相同的条数（归一会自命中，属数据缺陷） */
        private int selfAliasCount;
        /** 主要来源标注 */
        private String source;
    }

    /** 单类实体的归一覆盖（乙类） */
    @Data
    public static class TypeCoverage {
        /** structured_data 里的字段名（symptoms/diseases/…） */
        private String field;
        /** 中文名 */
        private String label;
        /** 抽取到的实体总数 */
        private int total;
        /** 已归一（normLevel 非空）的数量 */
        private int normalized;
    }

    /** 症状未归一实体的四类构成（乙类） */
    @Data
    public static class UnmatchedBreakdown {
        /** 未归一总数 */
        private int total;
        /** 抽取碎片：长度过短的残词，责任在抽取侧 */
        private int fragment;
        /** 分类错放：脉/舌要素进了症状，责任在抽取侧 */
        private int misrouted;
        /** 体征错放：压痛等进了症状，责任在抽取侧 */
        private int physicalSign;
        /** 其余：多为标准词，责任在词表侧（补词表即可改善） */
        private int dictionaryGap;
    }

    /** 可归一实体归一率（乙类） */
    @Data
    public static class NormalizableRate {
        /** 分母：已抽取、非抽取缺陷、且已在症状词表内 */
        private int denominator;
        /** 分子：其中已归一的 */
        private int numerator;
    }

    /** 质控分数分布与封顶率（乙类） */
    @Data
    public static class ScoreDistribution {
        /** 参与统计的病历数 */
        private int total;
        /** 术语未标准化扣满 5 分的病历数；占比高说明评分失去区分度 */
        private int capped;
        /** 最低分 */
        private int min;
        /** 最高分 */
        private int max;
        /** 平均分，保留一位小数 */
        private double avg;
    }

    /** 数据集形态（乙类，用来提醒这批数据能不能代表真实病历） */
    @Data
    public static class DatasetShape {
        /** 病历总数 */
        private int recordCount;
        /** 主诉去数字后的模板数；远小于总数说明是模板生成的 */
        private int chiefComplaintTemplates;
        /** 症状归一失败的实体中，源自患者口语字段的病历数 */
        private int recordsWithColloquialSymptom;
    }
}