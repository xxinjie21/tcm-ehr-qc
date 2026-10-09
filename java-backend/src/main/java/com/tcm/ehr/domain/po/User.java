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

    /** 生效账号：可以是「无组织」状态（未加入组织时可见范围由 RecordFilter fail-closed 兜底） */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;
    private String username;
    private String password;
    /** 系统级角色：管理员 / 用户（组长、组员、待分配池都是「用户」） */
    private String role;
    private LocalDateTime createTime;
    /** active=有生效组 / disabled=停用 */
    private String status;
}
