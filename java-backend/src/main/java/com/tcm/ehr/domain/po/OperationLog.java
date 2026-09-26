package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志：与 logs/operation.log 文件双写，审计页读本表做分页/筛选。
 * 字段与 database-init.sql 的 operation_log 表一一对应。
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
    /** 客户端IP */
    private String ip;
}
