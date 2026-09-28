package com.tcm.ehr.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求（入参）：用户名 / 密码，可选「同时申请建组」（阶段2）。
 *
 * <p><b>安全约束</b>：注册账号角色固定为「用户」，不开放管理员注册，
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

    /**
     * 课题组编码：字母 / 数字 / 短横线 / 下划线，长度 2~50。
     *
     * <p>与用户名<b>刻意用不同的字符集</b>：编码会出现在 URL 与组列表里，
     * 放开中文会让路径编码与日志出现转义歧义。强度要求同 USERNAME_PATTERN
     * （空值交由父对象的 {@code @NotBlank} 管）。</p>
     */
    public static final String GROUP_CODE_PATTERN = "^\\s*$|^[A-Za-z0-9_-]{2,50}$";

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = USERNAME_PATTERN, message = "用户名须为 2~20 位字母、数字、下划线或中文")
    private String username;

    @NotBlank(message = "密码不能为空")
    /**
     * 密码最短 6 位 —— 与前端 {@code Register.vue} 的 min=6 对齐。
     * 上限 72 防超长输入拖慢 BCrypt（而非列宽约束；users.password 是 VARCHAR(255)，
     * BCrypt 散列本身恒为 60 字符）。
     */
    @Size(min = 6, max = 72, message = "密码须为 6~72 位")
    private String password;

    /**
     * 可选：同时申请创建课题组。
     *
     * <p>不带 → 只注册，账号进「待分配池」（{@code status=pending}），等组长拉人。
     * 带 → 建 {@code pending} 组 + 本人成为首任组长（待管理员审批），
     * 并置 {@code has_pending_group=1} 使其从待分配池隐藏（避免被别的组长先拉走）。</p>
     */
    @Valid
    private CreateGroup createGroup;

    /** 「同时申请建组」的内嵌表单 */
    @Data
    public static class CreateGroup {

        @NotBlank(message = "课题组编码不能为空")
        @Pattern(regexp = GROUP_CODE_PATTERN, message = "课题组编码须为 2~50 位字母、数字、短横线或下划线")
        private String code;

        @NotBlank(message = "课题组名称不能为空")
        @Size(max = 100, message = "课题组名称最长 100 字")
        private String name;

        /** 用途说明（可选） */
        @Size(max = 500, message = "用途说明最长 500 字")
        private String purpose;
    }
}
