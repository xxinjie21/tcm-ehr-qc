package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户表 {@code users} */
@Data
@TableName("users")
public class User {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;
    private String username;
    private String password;
    /** 系统级角色：管理员 / 用户（组长、组员、待分配池都是「用户」） */
    private String role;
    private LocalDateTime createTime;
    /** pending=待分配池或审批中 / active=有生效组 / disabled=停用 */
    private String status;
    /** 1=已申请建组待审批，从待分配池隐藏（避免被组长重复拉走） */
    private Integer hasPendingGroup;
}
