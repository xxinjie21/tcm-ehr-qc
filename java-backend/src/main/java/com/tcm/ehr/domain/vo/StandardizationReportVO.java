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
 * 甲类 · 标准符合度（真正的目标）：词典规模、别名质量、
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
     * 与 {@link #generatedAt} 配对：前者答「按什么算的」，后者答「什么时候算的」。
     * 换过词表之后归一率会变，没有这一项就只能猜变化的原因。
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
        /**
         * 实际生效的导入时间起止（yyyy-MM-dd）；为空表示不限。
         *
         * 与 {@link #start}/{@link #end}（接诊时间）是两个独立维度，可同时生效、取交集。
         * 导入时间过滤下推到 SQL（records.create_time），其排除量<b>不计入</b>
         * {@link #excluded} —— excluded 只反映接诊时间过滤排除的条数。
         */
        private String importStart;
        private String importEnd;
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

        /**
         * 各抽取侧桶的 TOP 词（2026-10-21 新增）。
         *
         * 本页当前定位是<strong>给开发人员看</strong>：他们要根据本报告改抽取规则，
         * 只知道「有 1353 条」无从下手，必须知道「是哪 1353 条」。故每个桶都带 TOP 词与频次。
         * 与 {@link #top} 的区别：top 只收「词表缺口」（补词表可解决）；这四个是抽取侧，
         * 补词表无效，要改的是抽取边界。
         */
        private java.util.Map<String, Integer> inquiryFragmentTop = new java.util.LinkedHashMap<>();
        public java.util.Map<String, Integer> getInquiryFragmentTop() { return inquiryFragmentTop; }
        public void setInquiryFragmentTop(java.util.Map<String, Integer> v) { this.inquiryFragmentTop = v; }

        /** 病程 / 诱因桶的 TOP 词 */
        private java.util.Map<String, Integer> processDescTop = new java.util.LinkedHashMap<>();
        public java.util.Map<String, Integer> getProcessDescTop() { return processDescTop; }
        public void setProcessDescTop(java.util.Map<String, Integer> v) { this.processDescTop = v; }

        /** 脉诊手法 / 舌象碎片桶的 TOP 词 */
        private java.util.Map<String, Integer> mistypeMarkerTop = new java.util.LinkedHashMap<>();
        public java.util.Map<String, Integer> getMistypeMarkerTop() { return mistypeMarkerTop; }
        public void setMistypeMarkerTop(java.util.Map<String, Integer> v) { this.mistypeMarkerTop = v; }

        /** 两字未归一实体桶的 TOP 词 */
        private java.util.Map<String, Integer> fragmentTop = new java.util.LinkedHashMap<>();
        public java.util.Map<String, Integer> getFragmentTop() { return fragmentTop; }
        public void setFragmentTop(java.util.Map<String, Integer> v) { this.fragmentTop = v; }
        public void setTop(java.util.Map<String, Integer> top) { this.top = top; }
        /**
         * 靠 L2（包含）/ L3（模糊）**推断**命中的实体明细（G3）：键=实体原文，值=出现次数，按次数降序。
         *
         * 这批不是「词表缺口」（它们已被归一），但归一结论是猜出来的 —— 与 {@link #top} 同型
         * 单列，供前端提示「需登记别名或人工确认」，避免把推断命中混进词表缺口清单。
         */
        private java.util.Map<String, Integer> inferred = new java.util.LinkedHashMap<>();
        public java.util.Map<String, Integer> getInferred() { return inferred; }
        public void setInferred(java.util.Map<String, Integer> inferred) { this.inferred = inferred; }
        /** 未归一总数 */
        private int total;
        /**
         * 长度 ≤ 2 的未归一实体。
         *
         * <b>不是「抽取残字」的判定结果</b> —— 实测这批词里单字 0 条、也没有截断痕迹，
         * 主体是词表未收录的完整二字症状词（鼻塞、咽痛…）。归属词表侧，见
         * {@code StandardizationReportServiceImpl.unmatched(...)}。
         */
        private int fragment;
        /** 分类错放：脉/舌要素（脉细数 / 舌红）进了症状，责任在抽取侧 */
        private int misrouted;
        /**
         * 脉诊手法 / 舌象碎片被当成症状（不受按 / 中取不受按 / 红点多 / 边有红点…）。
         *
         * 2026-10-21 从 {@link #misrouted} 拆出。两类虽同在抽取侧，但修复方式不同：
         * misrouted 是<strong>路由错</strong>（该去 pulseList/tongueList，改分类即可），
         * 本桶是<strong>本不该成为实体</strong>（脉诊手法是描述脉象的动作、舌象碎片是子要素，
         * 都没有独立标准词，应在抽取阶段就被消费掉）。合成一个数字会让上游改错方向。
         */
        private int mistypeMarker;
        /** 体征错放：压痛等进了症状，责任在抽取侧 */
        private int physicalSign;
        /**
         * 问诊整句被当成症状（大便日一行 / 入睡转快 / 夜寐一般…），责任在抽取侧。
         *
         * 2026-10-21 从 {@link #dictionaryGap} 拆出：这批词的<strong>正确去向是抽取侧的
         * inquiry 字段</strong>（十问子项），补成症状标准词属错映射 —— 把它们算进
         * 「词表缺口」会让报告声称「词表缺 N 条」，而实际补词表根本解决不了。
         * 判定口径与 python-nlp 的 _INQUIRY_LEAD 同源（见 STANDARDIZATION_REPORT 的
         * INQUIRY_LEAD 常量）。
         */
        private int inquiryFragment;
        /**
         * 病程 / 诱因描述（活动后缓解 / 休息后缓解 / 遇劳加重 / 受凉后…），责任在抽取侧。
         *
         * 2026-10-21 从 {@link #dictionaryGap} 拆出：它们是<strong>对症状的修饰</strong>
         * 而非症状本体，没有独立标准词可补（补「活动后缓解」进症状词表会让所有含该子串的
         * 实体被错误归并）。
         */
        private int processDesc;
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