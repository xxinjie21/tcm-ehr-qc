package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量解析任务的一条待处理病历（批次 16 工作项 3）。
 *
 * <p>存在的理由：原先待处理 ID 集合只存在内存（{@code NlpBatchServiceImpl.idBatches}），
 * 进程重启即丢 —— 表现为「任务显示进行中，重启后再也不会推进」，取消与重试都拿不到那批 ID。
 * 落这张表让 ID 集合与进度一起存活。</p>
 *
 * <p>唯一键 {@code (task_id, record_id)}：重复提交或重放同一任务不会产生重复行。</p>
 */
@Data
@TableName("nlp_task_items")
public class NlpTaskItem {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    /** 自增主键（表上是 AUTO_INCREMENT，故不手动赋值） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务（nlp_task.id） */
    private String taskId;

    /** 待处理病历（records.id） */
    private String recordId;

    /** 提交顺序（0 起）：进度游标按它推进，不按主键 —— 主键顺序在批量插入下不保证与提交一致 */
    private Integer seq;

    private String status;

    /** 失败原因（仅 FAILED 时写） */
    private String reason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
