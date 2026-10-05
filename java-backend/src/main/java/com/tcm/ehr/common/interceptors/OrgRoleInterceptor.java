package com.tcm.ehr.common.interceptors;

import com.tcm.ehr.common.annotation.RequireOrgRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 组内角色校验：{@link RequireOrgRole} 标注的方法只放组长（owner），
 * 且注解声明的路径变量必须等于当前机构（组长只能管自己的组）。
 *
 * <p>为什么多此一举 —— {@link RequireRole} 只管系统级角色：
 * R5 的成员管理接口（拉人 / 移人 / 转让 / 退出）对「组长」开放，
 * 而组长在 {@code users.role} 里就是人人皆是的「用户」，
 * 用 {@code RequireRole("用户")} 等于没拦。维度不同，不能互相替代。</p>
 *
 * ⚠️ 路径变量名由注解的 pathVar 声明，不能在这里写死：原实现写死
 * {@code vars.get("orgId")}，而所有被保护路由的变量都叫 id，
 * 于是第二项校验自上线起从未执行（2026-10-05 修）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgRoleInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireOrgRole required = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequireOrgRole.class);
        if (required == null) {
            return true;
        }
        // 1. 组内角色校验
        String groupRole = RequestUtils.currentOrgRole();
        if (!required.value().equals(groupRole)) {
            return reject(response, "需要" + roleLabel(required.value()) + "权限");
        }
        // 2. 路径里的机构必须等于当前机构（防「拿别的组 id 调成员管理」）。
        //    pathVar 为哨兵值时表示该路由路径里没有机构 id（如提案号、版本号），
        //    此时归属校验由 service 按资源自身的机构完成
        if (RequireOrgRole.NO_PATH_VAR.equals(required.pathVar())) {
            return true;
        }
        Map<String, String> vars = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String pathGroupId = vars == null ? null : vars.get(required.pathVar());
        String currentOrgId = RequestUtils.currentOrgId();
        // 取不到声明的变量时拒绝，而不是放行：取不到基本就是注解写的变量名与路由不一致，
        // 放行等于把这项校验静默关掉 —— 本项缺陷的成因正是「取不到就跳过」
        if (pathGroupId == null || pathGroupId.isBlank()) {
            log.error("[组内角色] 注解声明的路径变量 {} 取不到值，请核对 RequireOrgRole.pathVar 与路由是否一致",
                    required.pathVar());
            return reject(response, "无权操作其它课题组");
        }
        if (currentOrgId == null || currentOrgId.isBlank() || !pathGroupId.equals(currentOrgId)) {
            return reject(response, "无权操作其它课题组");
        }
        return true;
    }

    private static String roleLabel(String role) {
        return "owner".equals(role) ? "组长" : role;
    }

    private boolean reject(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(403, msg)));
        return false;
    }
}