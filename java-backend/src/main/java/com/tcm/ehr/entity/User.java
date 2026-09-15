package com.tcm.ehr.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;
    private String username;
    private String password;
    /** 角色：管理员/审核员 */
    private String role;
    private LocalDateTime createTime;
}
