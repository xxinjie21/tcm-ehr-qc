package com.tcm.ehr.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组内角色校验（阶段2 R5）。
 *
 * <p>标在 Controller 方法上，由 {@code GroupRoleInterceptor} 读取 request 属性里的
 * {@code currentGroupRole} 与 {@code currentGroupId}。校验两件事：</p>
 * <ul>
 *   <li>当前组内角色必须是声明的值（本计划只有 {@code "owner"}）；</li>
 *   <li>路径中的 {@code groupId} 与当前组 <b>一致</b>（组长只能管自己的组，
 *       拿别人的 groupId 来调成员管理接口属于越权）。</li>
 * </ul>
 *
 * <p>不满足返回 HTTP 403 + Result{code=403}。它与 {@link RequireRole} 是<b>两个维度</b>：
 * 系统级角色用 {@link RequireRole}，组内级角色用本注解 —— 互不替代。</p>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireGroupRole {

    /** 允许访问的组内角色（本计划只有 "owner"） */
    String value();
}