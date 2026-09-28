package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志：审计页（{@code GET /api/logs}）的唯一数据源，读 {@code operation_log} 表做分页 / 筛选 / 导出。
 * 字段与 database-init.sql 的 operation_log 表一一对应。
 *
 * <p><b>不记 IP</b>（PIPL 最小必要，消费方仅 2 处且都不依赖 IP）：原 {@code ip} 列已由
 * {@code data/migration-01-drop-operation-log-ip.sql} 从存量库删除。</p>
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;
    /** 操作时间 */
    private LocalDateTime logTime;
    /** 操作人用户名 */
    private String operator;
    /** 操作人角色（管理员/审核员） */
    private String role;
    /** 操作类型（数据清洗/数据集导出/词典导入/词典回滚/人工复核/批量重算） */
    private String action;
    /** 操作对象（筛选范围/文件名/词典类型/病历ID） */
    private String target;
    /** 操作明细 */
    private String detail;
    /** 操作时所属组快照（§七 L7：组员只见本组自己）；无组为 null */
    private String groupId;
}
