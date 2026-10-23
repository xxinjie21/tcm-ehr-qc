package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * NLP 批量解析任务。对应 nlp_task 表。
 *
 * <p>进度与失败清单落库，故服务重启后仍可查询；重启时把未完成任务标为 INTERRUPTED（K-c）。
 * 字段见 {@link AbstractBatchTask}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nlp_task")
public class NlpTask extends AbstractBatchTask {

    /** 完整解析：实体抽取（调 Python NER）+ 术语归一。默认模式。 */
    public static final String MODE_EXTRACT = "EXTRACT";

    /**
     * 仅归一：不调 Python，读已有 structured_data、对原始实体重跑 ES 归一。
     *
     * <p>用于「补词典后让新词生效」——抽取过一次的实体（含 sourceText 原文）已落库，
     * 归一化只依赖 ES 词典，无须重跑 NER，省掉整段实体抽取时间。</p>
     */
    public static final String MODE_NORMALIZE_ONLY = "NORMALIZE_ONLY";

    /**
     * 任务模式：{@link #MODE_EXTRACT}（默认）或 {@link #MODE_NORMALIZE_ONLY}。
     *
     * <p>只放 NlpTask 而不放 {@link AbstractBatchTask}：质控没有这个概念，
     * 放基类会连带要求 qc_task 也加列（EntityDdlDriftGuardTest 会强制）。</p>
     */
    private String mode;

    /** mode 为 null（历史任务）时按 {@link #MODE_EXTRACT} 处理。 */
    public String modeOrDefault() {
        return mode == null || mode.isBlank() ? MODE_EXTRACT : mode;
    }
}
