package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 结果（出参，对应 openapi AiReplyVO；interpret / chat / review 共享）。
 *
 * <p>规则出结论 + LLM 叙述；LLM 不可用时 {@code answer} 为模板/规则降级、{@code summary} 为 null，
 * 规则结论（completeness / coreMissing / normHits / keyHints）始终保留。</p>
 */
@Data
public class AiReplyVO {

    /** 自然语言回答/叙述（interpret 报告叙述 或 chat 问答回复，恒非空） */
    private String answer;

    /** 来源：llm / rule */
    private String source;

    /** LLM 是否可用 */
    private boolean llmAvailable;

    /** 病历要点摘要（interpret 且 LLM 可用时）；否则 null */
    private Summary summary;

    /** 完整性分析（interpret）；chat 时为 null */
    private Completeness completeness;

    /** 核心字段缺失清单（interpret）；**真缺失**口径：结构化结果与原始列都没有记录 */
    private List<String> coreMissing = new ArrayList<>();

    /**
     * 核心字段「漏抽」清单（interpret）：结构化结果里没有、但原始列有记录，可能未被抽取。
     *
     * <p>{@link #coreMissing} 只覆盖真缺失，光看它会得出「核心字段齐全」——而质控侧的
     * 「核心字段缺失」扣分是<b>两档</b>（真缺失 -12 / 漏抽 -6）。两者合起来才与质控同口径，
     * 缺了本字段就会出现「同一份病历质控说缺、AI 说齐全」（审查报告 H1）。</p>
     */
    private List<String> corePartial = new ArrayList<>();

    /** 归一命中分布（interpret）；chat 时为 null */
    private NormHits normHits;

    /** 关键提示（interpret） */
    private List<String> keyHints = new ArrayList<>();

    /**
     * 术语补词建议（仅 termsuggest）；interpret / chat / review 时为 null。
     *
     * <p>与 {@code completeness}、{@code normHits} 同样属于「按用途留空」的字段：
     * 本 VO 被多个端点共用，每个端点只填自己那几项。</p>
     */
    private List<TermSuggestion> termSuggestions;

    /** 声明 */
    private String disclaimer = "AI辅助分析，最终以人工复核为准";

    @Data
    public static class Summary {
        private String chiefComplaint;
        private String diagnosis;
        private String syndrome;
        private String prescription;
    }

    @Data
    public static class Completeness {
        private int total;
        private int present;
        private List<String> missing = new ArrayList<>();
    }

    @Data
    public static class NormHits {
        private int exact;
        private int contain;
        private int fuzzy;
        private int total;
    }

    /**
     * 单条补词建议（termsuggest）。
     *
     * <p>LLM 只出候选，落库必须由人工确认 —— 归一链路本身不消费本对象，
     * 与「判定地基仍是规则引擎」一致。</p>
     */
    @Data
    public static class TermSuggestion {

        /** 待规范的原文（来自质量报告的待补词清单） */
        private String original;

        /**
         * 建议动作：
         * <ul>
         *   <li>{@code alias} —— 原文是某个已有标准词的口语/异名，挂为其别名；</li>
         *   <li>{@code new} —— 原文本身是规范术语但词表漏收，新建标准词；</li>
         *   <li>{@code ignore} —— 抽取碎片或体征错放，不该进词表；</li>
         *   <li>{@code unknown} —— LLM 不可用时的兜底，需人工判断。</li>
         * </ul>
         */
        private String action;

        /** action=alias 时为挂靠的已有标准词；action=new 时为建议的新标准词；ignore 时为空 */
        private String standardTerm;

        /** 建议一并录入的别名（可为空） */
        private List<String> aliases = new ArrayList<>();

        /** 建议来源：{@code dict}=现有词表召回命中 / {@code model}=LLM 依据术语规范给出 */
        private String source;

        /** 判断依据，一句话，供人工复核 */
        private String reason;
    }
}
