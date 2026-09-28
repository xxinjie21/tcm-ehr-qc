package com.tcm.ehr.common.interceptors;

import com.tcm.ehr.common.annotation.RequireGroupRole;
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
 * 组内角色校验：{@link RequireGroupRole} 标注的方法只放组长（owner），
 * 且路径参数 {@code groupId} 必须等于当前组（组长只能管自己的组）。
 *
 * <p>为什么多此一举 —— {@link RequireRole} 只管系统级角色：
 * R5 的成员管理接口（拉人 / 移人 / 转让 / 退出）对「组长」开放，
 * 而组长在 {@code users.role} 里就是人人皆是的「用户」，
 * 用 {@code RequireRole("用户")} 等于没拦。维度不同，不能互相替代。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupRoleInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireGroupRole required = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequireGroupRole.class);
        if (required == null) {
            return true;
        }
        // 1. 组内角色校验
        String groupRole = RequestUtils.currentGroupRole();
        if (!required.value().equals(groupRole)) {
            return reject(response, "需要" + roleLabel(required.value()) + "权限");
        }
        // 2. 路径 groupId 必须等于当前组（防「拿别的组 id 调成员管理」）
        Map<String, String> vars = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String pathGroupId = vars == null ? null : vars.get("groupId");
        String currentGroupId = RequestUtils.currentGroupId();
        if (pathGroupId != null && !pathGroupId.isBlank()
                && !pathGroupId.equals(currentGroupId)) {
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