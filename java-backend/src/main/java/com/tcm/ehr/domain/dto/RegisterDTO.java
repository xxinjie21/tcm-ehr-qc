package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
     * 用户名合法字符：字母 / 数字 / 下划线 / 中文。
     *
     * <p>正则<b>额外放行纯空白</b>：空值与纯空白由 {@code @NotBlank} 负责拦截，
     * 好让用户拿到「用户名不能为空」这条更准确的提示；此处若不放行，
     * 纯空白会同时触发两条规则，报错信息可能变成字符集提示（语义更差）。</p>
     */
    public static final String USERNAME_PATTERN = "^\\s*$|^[A-Za-z0-9_\\u4e00-\\u9fa5]+$";

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 20, message = "用户名长度须为 2~20 个字符")
    @Pattern(regexp = USERNAME_PATTERN, message = "用户名只能包含字母、数字、下划线或中文")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
