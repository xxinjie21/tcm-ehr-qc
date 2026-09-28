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
