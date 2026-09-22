package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * NLP 抽取结果（批G·8.1，对应 openapi NlpExtractVO，结构同 StructuredData）。
 * 9 类实体 + modelAvailable（服务/模型是否可用，false 表示降级结果）+ unavailableReason（降级原因）。
 */
@Data
public class NlpExtractVO {

    /** 降级原因：抽取总开关未开启（服务端配置，页面上无法自助打开） */
    public static final String REASON_DISABLED = "DISABLED";

    /** 降级原因：开关已开但抽取服务连不上（未启动 / 中途停止 / 返回非 200 / 调用异常） */
    public static final String REASON_UNREACHABLE = "SERVICE_UNREACHABLE";

    /** 降级原因：服务在跑但模型未加载，本次仅规则兜底（舌象 / 脉象 / 病因 / 治法） */
    public static final String REASON_MODEL_MISSING = "MODEL_MISSING";

    private List<Entity> diseases = new ArrayList<>();
    private List<Entity> symptoms = new ArrayList<>();
    private List<Entity> tongueList = new ArrayList<>();
    private List<Entity> pulseList = new ArrayList<>();
    private List<Entity> patternList = new ArrayList<>();
    private List<Entity> causeList = new ArrayList<>();
    private List<Entity> treatmentList = new ArrayList<>();
    private List<Entity> formulaList = new ArrayList<>();
    private List<Herb> herbs = new ArrayList<>();
    private boolean modelAvailable;

    /**
     * 降级原因（{@link #REASON_DISABLED} / {@link #REASON_UNREACHABLE} / {@link #REASON_MODEL_MISSING}）；
     * 正常有产出时为 {@code null}。
     *
     * <p>第八轮之前只下发一个 {@code modelAvailable} 布尔值，「功能没开」与「服务挂了」在前端
     * 无法区分，页面横幅只能写成「未开启，或抽取服务暂时不可用」，用户看不出该找谁、也不知道
     * 能不能自助解决。这里把判定结果显式下发，前端即可按原因给不同的结论与下一步。</p>
     */
    private String unavailableReason;

    /** 降级空结果（服务未启动 / nlp.enabled=false） */
    public static NlpExtractVO empty() {
        return new NlpExtractVO();
    }

    /**
     * 实体（结构同附录A Entity）。归一后 {@code content}=标准术语、{@code sourceText}=归一前原文，
     * 两者构成「归一前后对照」；未命中词典时 {@code content} 保持原文、{@code normLevel} 为空。
     */
    @Data
    public static class Entity {
        private String content;
        private String sourceText;
        private Double confidence;
        private String source;
        /** 归一命中层级：1=精确 / 2=包含 / 3=模糊；未归一或未命中为 null */
        private Integer normLevel;
        /** 归一命中的术语来源（词典名），未命中为 null */
        private String normSource;
        /** 国标代码，词典未收录则为 null */
        private String normCode;
    }

    @Data
    public static class Herb {
        private String name;
        private String dosage;
        private String sourceText;
        private Double confidence;
        private String source;
        /** 归一命中层级：1=精确 / 2=包含 / 3=模糊；未归一或未命中为 null */
        private Integer normLevel;
        /** 归一命中的术语来源（词典名），未命中为 null */
        private String normSource;
        /** 国标代码，词典未收录则为 null */
        private String normCode;
    }
}
