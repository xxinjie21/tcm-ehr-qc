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
}
