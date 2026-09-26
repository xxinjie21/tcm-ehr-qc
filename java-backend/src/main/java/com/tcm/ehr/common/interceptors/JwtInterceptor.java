package com.tcm.ehr.common.interceptors;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 全局JWT鉴权（文档必做1/2、需求4.5安全性）：
 * 拦截 /api/**，放行 /api/auth/login；校验通过后把当前操作人放入request属性（供操作日志使用）
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    /**
     * 校验 Authorization 头里的 Bearer token；通过后把当前用户信息写入 request 属性
     * （currentUserId / currentUsername / currentRole），供角色校验与操作日志使用。
     *
     * @return true 放行；false 表示已写出 401 响应，请求到此为止
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 放行预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 1. 取 Authorization 头，缺少 Bearer 前缀即拒绝
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(response);
        }
        // 2. 校验 token 有效性，无效即拒绝
        String token = auth.substring(7);
        if (!jwtUtil.isValid(token)) {
            return reject(response);
        }
        // 3. 解析声明并写入 request 属性，供角色校验与操作日志使用
        Claims claims = jwtUtil.parseToken(token);
        request.setAttribute("currentUserId", claims.getSubject());
        request.setAttribute("currentUsername", String.valueOf(claims.get("username")));
        request.setAttribute("currentRole", String.valueOf(claims.get("role")));
        return true;
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(401, "未登录或token失效")));
        return false;
    }
}
