package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 病历表：21个原始字段 + NLP/质控结果
 */
@Data
@TableName("records")
public class Record {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
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
    /** 归属课题组；空 = 无组，代码层降级为「无数据」（fail-closed） */
    private String groupId;

    /**
     * 21 字段文本哈希（见 {@code RecordUtil.textHash}），去重的 DB 层兜底。
     *
     * <p>与 {@code groupId} 组成联合唯一键 {@code uk_records_org_text_hash}：
     * 同一份病历在同一组织内只能存一份，跨组织各存一份互不冲突。
     * <b>存量行为 NULL</b>（不为老数据回填，见 migration-03 注）——
     * MySQL 唯一索引视多个 NULL 互不相等，故存量行不受此键约束。</p>
     */
    private String textHash;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
