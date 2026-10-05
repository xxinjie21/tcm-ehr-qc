package com.tcm.ehr.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组内角色校验（阶段2 R5）。
 *
 * 标在 Controller 方法上，由 OrgRoleInterceptor 读取 request 属性里的 currentOrgRole
 * 与 currentOrgId，校验两件事：当前组内角色必须是声明的值（本计划只有 "owner"）；
 * pathVar 指向的路径变量必须等于当前机构（组长只能管自己的组）。
 *
 * pathVar 刻意不给默认值：有默认值的话，漏写的路由会静默失去第二项校验 ——
 * 2026-10-05 修掉的正是这个形态的缺陷（拦截器写死取 "orgId"，而所有被保护路由
 * 的变量都叫 "id"，校验自上线起从未执行）。不给默认值，漏写即编译不过。
 *
 * 不满足返回 HTTP 403 + Result{code=403}。它与 RequireRole 是两个维度：
 * 系统级角色用 RequireRole，组内级角色用本注解，互不替代。
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireOrgRole {

    /**
     * pathVar 的哨兵值：该路由的路径里没有机构 id（如 {id} 是提案号、{versionNo} 是版本号）。
     *
     * 写它表示「不做路径机构比对」，此时归属校验必须由 service 按资源自身的机构完成，
     * 否则等于少一道校验。要求显式写出哨兵而不是省略 pathVar，就是为了让这个选择出现在代码里。
     */
    String NO_PATH_VAR = "";

    /** 允许访问的组内角色（本计划只有 "owner"） */
    String value();

    /**
     * 路径变量名，其值必须等于当前机构（路由 /api/orgs/{id}/members 就写 "id"）；
     * 路径里没有机构 id 时写 {@link #NO_PATH_VAR}。
     *
     * 必须与 @PathVariable 的名字逐字一致：取不到值时拦截器会直接拒绝请求，
     * 而不是跳过校验。
     */
    String pathVar();
}
