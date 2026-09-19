package com.tcm.ehr.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色校验（批A·1.2 RBAC 双角色落实）：
 * 标注在 Controller 方法或类上，由 {@code RoleInterceptor} 读取 request 属性 currentRole 做
 * <b>包含匹配</b>（任一角色命中即放行），未命中返回 HTTP 403 + Result{code=403}。
 *
 * <p>不标注 = 「登录即可」（JwtInterceptor 已保证鉴权），无需额外注解。</p>
 *
 * <p>用法：</p>
 * <pre>
 * &#64;RequireRole(roles = {"管理员"})                  // 仅管理员
 * &#64;RequireRole(roles = {"管理员", "审核员"})         // 复核双角色
 * </pre>
 *
 * <p>角色取值以 openapi 的【权限：…】标注为准（契约权威），四档中的「公开」由
 * WebMvcConfig 排除路径实现，不使用本注解。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色（包含匹配，任一命中即放行） */
    String[] roles();
}
