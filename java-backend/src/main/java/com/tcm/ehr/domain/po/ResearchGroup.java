package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课题组。对应 {@code research_groups} 表。
 *
 * <p>状态机：{@code pending}（已申请待审批）→ {@code active}（生效）/ {@code rejected}（被拒），
 * {@code active} ↔ {@code stopped}（管理员停用，只挡登录、数据保留）。</p>
 *
 * <p>{@code code} 有 UNIQUE 约束，被拒时改写为 {@code rej_<groupId>_<原code>} 释放编码，
 * 否则一次拒绝会让该编码被永久占死。</p>
 */
@Data
@TableName("research_groups")
public class ResearchGroup {

    public static final String PENDING = "pending";
    public static final String ACTIVE = "active";
    public static final String REJECTED = "rejected";
    public static final String STOPPED = "stopped";
    /** 归档：管理员手动，前提「成员数为 0」；不自动归档（自动归档会误伤沉睡组织） */
    public static final String ARCHIVED = "archived";

    // ⚠️ PENDING / REJECTED 在批次 4 **刻意保留**：DB 侧已按最终口径把 status 默认值
    //    改成 active，但「取消审核」是批次 6 才落地的 —— 提前删常量会让 register 的
    //    建组申请流程当场编译不过。批次 6 移除审批流时一并删除这两个常量。

    private String id;
    /** 组唯一编码；申请时即校验，防抢占 */
    private String code;
    private String name;
    /** 用途说明（可选） */
    private String purpose;
    private String status;
    /** 申请人（= 首任组长） */
    private String appliedBy;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String rejectReason;
    private LocalDateTime createTime;
}
