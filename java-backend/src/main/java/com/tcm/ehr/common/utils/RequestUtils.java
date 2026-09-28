package com.tcm.ehr.common.utils;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前请求上下文工具（操作日志留痕用）：
 * JwtInterceptor 校验通过后把 currentUserId / currentUsername / currentRole 写入 request 属性，
 * 本类统一读取，替代各 Controller 各自复制的 operator() 方法。
 * 无请求上下文（如定时任务、单元测试）时统一返回 "unknown"，不抛异常。
 *
 * <p>§七 L1 已删除 {@code currentIp()}（不记操作 IP），随之删掉只为它服务的
 * {@code currentRequest()} 与两个 servlet import。</p>
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

    /** 取请求属性（鉴权拦截器写入的角色等）；无请求上下文或取不到时返回 unknown */
    private static String attr(String key) {
        try {
            // 1. 从当前请求属性里取
            Object value = RequestContextHolder.currentRequestAttributes()
                    .getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (value == null) {
                return UNKNOWN;
            }
            // 2. 空白与字面 "null" 都算取不到（属性可能被序列化成字符串 "null"）
            String text = String.valueOf(value);
            return text.isBlank() || "null".equals(text) ? UNKNOWN : text;
        } catch (Exception e) {
            // 3. 无请求上下文也返回 unknown，不抛给业务代码
            return UNKNOWN;
        }
    }
}
