package com.tcm.ehr.common.interceptors;

import com.tcm.ehr.common.domain.Result;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 拦截器拒绝响应：统一写出 {@code HTTP 状态码 + Result{code,msg}} JSON。
 *
 * 三个拦截器（{@link JwtInterceptor} 401 / {@link RoleInterceptor} 403 /
 * {@link OrgRoleInterceptor} 403）原本各写一份逐字相同的「setStatus + setContentType +
 * getWriter().write(objectMapper.writeValueAsString(Result.error(...)))」。响应形态必须一致
 * —— 前端靠 Result 包体取 msg —— 故收敛到一处（审查报告 §2.7）。
 *
 * status 同时用作 HTTP 状态码与 Result.code：三处调用点本来就是这个口径
 * （401/401、403/403），不另设冗余的 code 参数。
 */
final class JsonResponses {

    private JsonResponses() {
    }

    /**
     * 写 JSON 拒绝响应。
     *
     * @return 恒为 {@code false}，供 preHandle 直接 {@code return}（表示请求到此为止）
     */
    static boolean write(ObjectMapper objectMapper, HttpServletResponse response, int status, String msg)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(status, msg)));
        return false;
    }
}
