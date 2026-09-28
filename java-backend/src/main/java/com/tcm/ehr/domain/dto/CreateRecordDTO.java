package com.tcm.ehr.domain.dto;

import com.tcm.ehr.common.config.QcRuleSet;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单条新增病历请求。
 * 契约见 openapi CreateRecordDTO；registrationNo / outpatientNo 必填由服务层校验。
 *
 * <p>校验注解与 DB 列宽、质控规则对齐，避免「前端拦得住、直调 API 绕得过」：</p>
 * <ul>
 *   <li>就诊次数 {@code @Min(1)} —— 与前端 {@code Records.vue} 的 min=1 一致</li>
 *   <li>性别 {@code @Pattern} —— 白名单取 {@link QcRuleSet#GENDER_VALUES}（唯一权威）</li>
 *   <li>{@code @Size} 只加在 VARCHAR 列上，长度即 {@code database-init.sql} 的列宽：
 *       registration_no / outpatient_no / department / doctor_id = 50、age = 20。
 *       临床正文列（western_diagnosis … treatment_effect）是 {@code TEXT}，不限长度，不加约束。</li>
 * </ul>
 */
@Data
public class CreateRecordDTO {

    @Size(max = 50, message = "登记号最长 50 字")
    private String registrationNo;

    @Size(max = 50, message = "门诊号最长 50 字")
    private String outpatientNo;

    @Pattern(regexp = QcRuleSet.GENDER_PATTERN, message = "性别只能填「男」或「女」")
    private String gender;

    @Size(max = 20, message = "年龄最长 20 字")
    private String age;

    /** 就诊次数：第 1 次起算，不允许 0 或负数（与前端 Records.vue 的 min=1 一致） */
    @Min(value = 1, message = "就诊次数不能小于 1")
    @Max(value = 999, message = "就诊次数不能大于 999")
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

    @Size(max = 50, message = "开单科室最长 50 字")
    private String department;

    @Size(max = 50, message = "医生工号最长 50 字")
    private String doctorId;

    private LocalDateTime visitTime;
}
