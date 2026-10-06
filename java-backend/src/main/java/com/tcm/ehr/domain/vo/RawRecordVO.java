package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 原始病历查看：21 个原始字段只读 + 结构化数据/质控结果。
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

    /**
     * 读时指纹（批次 25.15）：复核页提交修正时原样回传，服务端据此判断
     * 「这条病历自你读取后有没有被别人改过」。由 {@link com.tcm.ehr.common.utils.ReviewTaskUtil#fingerprint}
     * 对结构化数据与评分结论计算；不一致时复核提交以 409 拒绝。
     */
    private String fingerprint;
}
