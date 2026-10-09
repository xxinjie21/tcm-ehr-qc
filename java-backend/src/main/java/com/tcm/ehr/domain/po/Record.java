package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    private String orgId;

    /**
     * 是否人工修改过结构化数据（列表标记 / 清洗跳过判据）。
     *
     * <p><b>数据库 STORED 生成列</b>：值由 {@code structured_data._meta.manuallyEdited} 派生
     * （见 {@code data/2026-10-08-add-records-manually-edited.sql}），应用侧<b>只读不写</b> ——
     * {@code insertStrategy}/{@code updateStrategy} 都设为 {@code NEVER}，避免 MyBatis-Plus 把
     * 它写进 INSERT/UPDATE（MySQL 对生成列赋值会直接报 3105）。</p>
     *
     * <p>为什么落列而不是继续解析 JSON：列表每行都要为读这一个布尔值解析一次 ~3.7KB JSON
     * （性能审查 P1-2#2，收益最大的一步）。落成生成列后与 {@code _meta.manuallyEdited}
     * <b>严格同源</b>，六条写入路径（导入 / 单条新增 / 写回结构化数据 / 清洗归一 / NLP 重解析 /
     * 复核）都无需各自维护。</p>
     *
     * <p>语义参考实现仍是 {@link com.tcm.ehr.common.utils.StructuredDataMeta#isManuallyEdited}，
     * 生成列表达式与它逐例等价（Boolean true / 非零数字 / 字符串 "true" 为真）。</p>
     */
    @TableField(value = "manually_edited", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private Boolean manuallyEdited;

    /**
     * 21 字段文本哈希（见 {@code RecordUtil.textHash}），去重的 DB 层兜底。
     *
     * <p>与 {@code orgId} 组成联合唯一键 {@code uk_records_org_text_hash}：
     * 同一份病历在同一组织内只能存一份，跨组织各存一份互不冲突。
     * <b>存量行为 NULL</b>（不为老数据回填，见 migration-03 注）——
     * MySQL 唯一索引视多个 NULL 互不相等，故存量行不受此键约束。</p>
     */
    private String textHash;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
