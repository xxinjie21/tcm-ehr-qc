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

    @Data
    public static class Entity {
        private String content;
        private String sourceText;
        private Double confidence;
        private String source;
    }

    @Data
    public static class Herb {
        private String name;
        private String dosage;
        private String sourceText;
        private Double confidence;
        private String source;
    }
}
