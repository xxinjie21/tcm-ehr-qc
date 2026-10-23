package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据清洗任务。对应 clean_task 表。
 *
 * <p><b>为什么由同步改异步</b>：原 {@code POST /api/governance/clean} 一个请求线程从头跑到尾，
 * 全库 3.4 万条须数分钟，前端只能挂到超时、且<b>无法停止</b>。改为后台任务后：
 * 提交即返回任务 ID，可轮询进度、可取消（{@link AbstractBatchTask#cancelRequested}）、
 * 关页面也不影响执行。</p>
 *
 * <p><b>与 qc_task / nlp_task 同构</b>：状态机与进度字段全部继承自 {@link AbstractBatchTask}，
 * 本类只加清洗特有的四个计数与「是否归一」开关。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("clean_task")
public class CleanTask extends AbstractBatchTask {

    /**
     * 是否跳过术语归一（1=跳过；0=执行，默认）。
     *
     * <p>术语归一要逐条查 ES，是清洗五步里最耗时的一步。不需要归一时（如只想去重/清理），
     * 勾上此项可显著缩短清洗时间。注意：跳过归一后 structured_data 的 normLevel 保持原样，
     * 导出件也不会产生新的标准词替换。</p>
     */
    private Integer skipNormalize;

    /** 去重标记数 */
    private Integer deduped;
    /** 字段清理数 */
    private Integer repaired;
    /** 脏数据隔离数 */
    private Integer isolated;
    /** 术语归一实体数（skipNormalize=1 时为 0） */
    private Integer normalized;
}
