package com.tcm.ehr.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {

    private String id;
    private String username;
    private String password;
    /** 角色：管理员/审核员 */
    private String role;
    private LocalDateTime createTime;
}
