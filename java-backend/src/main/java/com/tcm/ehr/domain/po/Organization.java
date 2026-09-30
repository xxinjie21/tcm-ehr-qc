package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课题组。对应 {@code organizations} 表。
 *
 * <p>状态机（批次 6 起取消审核，只剩三态）：{@code active} ↔ {@code stopped}
 * （管理员停用，只挡登录、数据保留）→ {@code archived}（管理员手动，前提成员数为 0）。</p>
 *
 * <p>{@code code} 有 UNIQUE 约束，被拒时改写为 {@code rej_<orgId>_<原code>} 释放编码，
 * 否则一次拒绝会让该编码被永久占死。</p>
 */
@Data
@TableName("organizations")
public class Organization {

    public static final String ACTIVE = "active";
    public static final String STOPPED = "stopped";
    /** 归档：管理员手动，前提「成员数为 0」；不自动归档（自动归档会误伤沉睡组织） */
    public static final String ARCHIVED = "archived";


    private String id;
    /** 组唯一编码；申请时即校验，防抢占 */
    private String code;
    private String name;
    /** 用途说明（可选） */
    private String purpose;
    private String status;

    /**
     * 所有者用户 ID（批次 4 加列）。
     *
     * <p><b>冗余展示字段</b>：权威是 {@code organization_members.role=owner}，
     * 两者在任何写路径下都同事务更新（批次 6 的创建 / 转让 / 改派）。</p>
     */
    private String ownerUserId;
    /** 申请人（= 首任组长） */
    // ⚠️ 批次 6 去审核：appliedBy / reviewedBy / reviewedAt / rejectReason 四列
    //    已在批次 4 的 DDL 里删除，实体字段同步移除。留着会被 MyBatis 拼进 SELECT，
    //    报 Unknown column —— 这正是「DDL 删了、实体没删」那类不一致。
    private LocalDateTime createTime;
}
