package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求参数。
 *
 * 两个字段均必填，缺失由 @NotBlank 在进入 Service 前拦下，避免空值传到认证逻辑。
 *
 * ⚠️ password 为明文传输，依赖 HTTPS；该字段不得出现在任何日志与审计记录中。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "请输入用户名")
    private String username;

    @NotBlank(message = "请输入密码")
    private String password;
}
