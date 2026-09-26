package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单条新增病历请求。
 * 契约见 openapi CreateRecordDTO；registrationNo / outpatientNo 必填由服务层校验。
 */
@Data
public class CreateRecordDTO {

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
}
