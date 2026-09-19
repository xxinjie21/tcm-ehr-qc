package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    /**
     * 用户名合法形式：字母 / 数字 / 下划线 / 中文，长度 2~20。
     *
     * <p>长度也写进正则、<b>不再单用 {@code @Size}</b>：否则空字符串会同时触发
     * {@code @Size(min=2)} 与 {@code @NotBlank}，用户拿到两条重复提示。
     * 另外正则<b>放行纯空白</b>，空值与空白一律由 {@code @NotBlank} 给出更准确的文案。</p>
     */
    public static final String USERNAME_PATTERN = "^\\s*$|^[A-Za-z0-9_\\u4e00-\\u9fa5]{2,20}$";

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = USERNAME_PATTERN, message = "用户名须为 2~20 位字母、数字、下划线或中文")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
