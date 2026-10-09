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

    /**
     * 质控完成度。
     *
     *
     * 报告的分数与扣分都来自 qc_results，它是质控跑完时写下的结果。
     *
     * 病历刚导入或刚重跑解析、质控还没跟上时，那份结果是旧的甚至没有 ——
     * 此时页面上任何「归一率」「封顶率」都是拿旧数据算的，会把人带偏。
     * 所以先看这一项，没跑完就不该展示结论。
     */
    private QcCoverage qc = new QcCoverage();

    /** 数据来源声明；页面上要原样显示，不能删 */
    private String disclaimer;

    /** 报告生成时间（yyyy-MM-dd HH:mm） */
    /**
     * 本报告出自哪一版词典（批次14 · 对标 A5「派生数字可回溯血缘」）。
     *
     * <p>与 {@link #generatedAt} 配对：前者答「按什么算的」，后者答「什么时候算的」。
     * 换过词表之后归一率会变，没有这一项就只能猜变化的原因。</p>
     */
    private String sourceVersion;

    private String generatedAt;

    /** 本次统计的时间范围（按接诊时间 visit_time） */
    private TimeRange range = new TimeRange();

    /** 按接诊月份分组的趋势；由近及远，便于看「换了词表之后有没有变好」 */
    private List<MonthlyBucket> byMonth = new ArrayList<>();

    /**
     * 统计区间。
     *
     *
     * 病历的接诊时间跨度很大（实测 2019-01 ~ 2025-12），把所有年份混在一起
     *
     * 看不出「换了词表之后有没有变好」—— 新词表只对重跑过解析的病历生效，
     * 而不同批次解析的病历接诊时间往往不同。所以区间必须能看到、能切。
     */
    @Data
    public static class TimeRange {
        /** 实际生效的起止（yyyy-MM-dd）；为空表示不限 */
        private String start;
        private String end;
        /** 区间内病历数 */
        private int records;
        /** 区间外（被本次过滤掉）的病历数，0 表示没过滤 */
        private int excluded;
    }

    /** 按接诊月份的分组 */
    @Data
    public static class MonthlyBucket {
        /** 月份（yyyy-MM） */
        private String month;
        /** 该月病历数 */
        private int records;
        /** 该月症状归一率（yyyy-MM-dd 形式为 null 时按 0 处理） */
        private String symptomRate;
        /** 该月平均质控分 */
        private double avgScore;
        /** 该月质控封顶的病历数 */
        private int capped;
        /** 该月「症状词表缺口」条数（补词表能解决的那部分） */
        private int dictionaryGap;
    }

    /** 单类词典的质量画像（甲类） */
    @Data
    public static class DictQuality {
        /** 术语类型 key（disease/pattern/…） */
        private String type;
        /** 中文名 */
        private String label;
        /** 词条数 */
private int termCount;
        /** 有别名的词条数 */
        private int aliasedCount;
        /** 别名与标准词相同的条数（归一会自命中，属数据缺陷） */
        private int selfAliasCount;
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
        /** 该类词表当前有多少词条 */
        private int termCount;
        /**
         * 疑似「解析早于词表建立」：词表非空、也抽到了实体，却一条都没归上。
         *
         *
         * 这不是词表缺口 —— 词表里明明有词却完全没命中，说明这批结构化数据是
         *
         * 在词表建好之前算出来的。补词表对它无效，得重跑解析。
         * 不区分这一种与真正的「词表没收录」，用户会去补不需要补的词表。
         */
        private boolean suspectedStaleExtraction;
    }

    /** 症状未归一实体的四类构成（乙类） */
    @Data
    public static class UnmatchedBreakdown {
        /**
         * 未归一词表缺口的高频明细（批次2）：键=实体原文，值=出现次数，按次数降序。
         * 只收录「词表缺口」一类 —— 它是唯一能靠补词表消除的构成，因此是前端一键
         * 「加入我的词典 / 生成提案」的输入；数据来自服务逐实体分类的同一次遍历，不额外扫库。
         * 用 Map 而非自定义内部类：避免依赖 Lombok 作用到内部类（外层 @Data 不覆盖内部类）。
         */
        private java.util.Map<String, Integer> top = new java.util.LinkedHashMap<>();
        public java.util.Map<String, Integer> getTop() { return top; }
        public void setTop(java.util.Map<String, Integer> top) { this.top = top; }
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

    /**
     * 质控完成度。
     *
     *
     * 「已完成」的判据是「有 qc_results 且其中的 score 与 records.score 一致」——
     *
     * 两者同步说明质控确实跑完并落了库；不一致或缺失说明这份结果是旧的。
     */
    @Data
    public static class QcCoverage {
        /** 数据域内的病历总数 */
        private int total;
        /** 已完成质控评分的病历数 */
        private int scored;
        /** 是否已全部完成 */
        private boolean complete;
        /** 最近一次质控完成时间；用于判断结果是不是刚跑出来的 */
        private String lastScoredAt;
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