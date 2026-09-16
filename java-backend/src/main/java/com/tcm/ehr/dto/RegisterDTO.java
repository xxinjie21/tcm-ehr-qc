package com.tcm.ehr.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 注册请求（入参）：用户名 / 密码。
 *
 * <p>安全约束：注册账号角色固定为「审核员」，不开放管理员注册，
 * 故本 DTO 不接收 role 字段（即使前端传入也会被 Jackson 忽略）；
 * 管理员账号由 database-init.sql 预置。</p>
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
