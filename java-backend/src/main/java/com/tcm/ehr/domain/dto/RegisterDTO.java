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
    /**
     * 密码最短 6 位 —— 与前端 {@code Register.vue} 的 min=6 对齐。
     * 此前只有前端拦得住，直调 {@code POST /api/auth/register} 可传 1 位密码建号。
     * 上限取 {@code users.password VARCHAR(255)} 的一半再留余量（BCrypt 散列固定 60 字符，
     * 这里的 72 是防超长输入拖慢 BCrypt，而非列宽约束）。
     */
    @Size(min = 6, max = 72, message = "密码须为 6~72 位")
    private String password;
}
