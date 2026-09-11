package com.tcm.ehr.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 病历表：21个原始字段 + NLP/质控结果
 */
@Data
public class Record {

    private String id;
    private String registrationNo;
    private String outpatientNo;
    private String gender;
    private String age;
    private Integer visitCount;
    private String westernDiagnosis;
    private String tcmDiagnosis;
    private String presentIllness;
    private String chiefComplaint;
    private String selfReport;
    private String inspection;
    private String pulse;
    private String tongue;
    private String physicalExam;
    private String pattern;
    private String prescription;
    private String followUp;
    private String treatmentEffect;
    private String department;
    private String doctorId;
    private LocalDateTime visitTime;
    /** 结构化数据（NLP抽取结果，JSON） */
    private String structuredData;
    /** 质控检查结果（JSON） */
    private String qcResults;
    /** 质控评分（满分100） */
    private Integer score;
    /** 分级：合格/待复核/无效 */
    private String grade;
    /** 状态：pending/reviewing/completed/invalid */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
