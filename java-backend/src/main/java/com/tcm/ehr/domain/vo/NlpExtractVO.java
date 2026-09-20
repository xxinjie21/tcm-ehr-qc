package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * NLP 抽取结果（批G·8.1，对应 openapi NlpExtractVO，结构同 StructuredData）。
 * 9 类实体 + modelAvailable（服务/模型是否可用，false 表示降级结果）。
 */
@Data
public class NlpExtractVO {

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
