package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 原始病历查看（批F·7.2，对应 openapi RawRecordVO）：21 个原始字段只读 + 结构化数据/质控结果。
 */
@Data
public class RawRecordVO {

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
    private String structuredData;
    private Integer score;
    private String grade;
    private String status;
}
