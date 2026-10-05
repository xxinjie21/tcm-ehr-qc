package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /** 操作人角色（管理员/用户） */
    private String role;
    /** 操作类型（数据清洗/数据集导出/词典导入/词典回滚/人工复核/批量重算） */
    private String action;
    /**
     * 对象类型（对标 D3）：如 {@code record}。与 {@link #objectId} 成对使用。
     *
     * <p>可为 null，且**批量操作应当为 null**：批量重算/数据清洗没有单一对象，
     * 给它编一个假对象只会让「按对象查历史」查出错误的结果。</p>
     */
    private String objectType;
    /** 对象 ID（对标 D3），如病历 ID；与 {@link #objectType} 成对 */
    private String objectId;
    /** 操作对象（筛选范围/文件名/词典类型/病历ID） */
    private String target;
    /** 操作明细 */
    private String detail;
    /** 操作时所属组织快照（三档可见范围：成员只见本组织自己的）；无组织为 null */
    private String orgId;

    /**
     * 所属组织名称（<b>不落库</b>）：由 LogServiceImpl 按 orgId 批量解析后回填。
     *
     * <p>日志表只存 org_id。直接把这个 UUID 显示给用户没有意义，
     * 而「这条日志该不该被我看」又正是三档可见范围要回答的问题，
     * 所以列表里补一列组织名。</p>
     */
    @TableField(exist = false)
    private String orgName;
}
