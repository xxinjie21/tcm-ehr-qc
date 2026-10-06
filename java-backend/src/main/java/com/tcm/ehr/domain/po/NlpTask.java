package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * NLP 批量解析任务。对应 {@code nlp_task} 表。
 *
 * <p>进度与失败清单落库，故服务重启后仍可查询；重启时把未完成任务标为 {@code INTERRUPTED}（K-c）。
 * 字段见 {@link AbstractBatchTask}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nlp_task")
public class NlpTask extends AbstractBatchTask {
}
