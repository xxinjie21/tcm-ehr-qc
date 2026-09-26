package com.tcm.ehr.common.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前请求上下文工具（操作日志留痕用）：
 * JwtInterceptor 校验通过后把 currentUserId / currentUsername / currentRole 写入 request 属性，
 * 本类统一读取，替代各 Controller 各自复制的 operator() 方法。
 * 无请求上下文（如定时任务、单元测试）时统一返回 "unknown"，不抛异常。
 */
public final class RequestUtils {

    /** JwtInterceptor 写入的 request 属性名 */
    public static final String ATTR_USERNAME = "currentUsername";
    public static final String ATTR_ROLE = "currentRole";

    private static final String UNKNOWN = "unknown";

    private RequestUtils() {
    }

    /** 当前操作人用户名 */
    public static String currentUsername() {
        return attr(ATTR_USERNAME);
    }

    /** 当前操作人角色（管理员/审核员） */
    public static String currentRole() {
        return attr(ATTR_ROLE);
    }

    /**
     * 客户端 IP：优先 X-Forwarded-For 首个地址，其次 X-Real-IP，最后 remoteAddr。
     * 本项目不经过 nginx，实际取到的就是 remoteAddr（127.0.0.1）。
     */
    public static String currentIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return UNKNOWN;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return first.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? UNKNOWN : remote;
    }

    /** 取请求属性（鉴权拦截器写入的角色等）；无请求上下文或取不到时返回 unknown */
    private static String attr(String key) {
        try {
            Object value = RequestContextHolder.currentRequestAttributes()
                    .getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (value == null) {
                return UNKNOWN;
            }
            String text = String.valueOf(value);
            return text.isBlank() || "null".equals(text) ? UNKNOWN : text;
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /** 取当前请求；非 Web 线程（如定时任务/异步）返回 null */
    private static HttpServletRequest currentRequest() {
        try {
            RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
            return attributes instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
