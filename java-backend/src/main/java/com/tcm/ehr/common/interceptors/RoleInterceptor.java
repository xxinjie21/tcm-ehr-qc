package com.tcm.ehr.common.interceptors;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 角色校验：
 * 读取 JwtInterceptor 写入的 currentRole，与 {@link RequireRole} 声明的角色做<b>包含匹配</b>。
 *
 * <ul>
 *   <li>注册在 JwtInterceptor <b>之后</b>，进入本拦截器时 token 已校验通过、角色已就位；</li>
 *   <li>方法级注解优先，其次取类级注解（便于未来 qc/* 整类收紧）；</li>
 *   <li>未标注 {@code @RequireRole} 的接口一律放行（= 「登录即可」）；</li>
 *   <li>未命中 → HTTP 403 + {@code Result{code=403}}，不抛异常、不进业务层。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RoleInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    /**
     * 取「方法级优先、类级兜底」的 {@link RequireRole}，与 JWT 里的当前角色做包含匹配。
     *
     * @return true 放行（未标注注解 = 登录即可）；false 表示已写出 403 响应
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 放行预检请求（与 JwtInterceptor 一致：此时 request 属性尚未写入）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 仅对控制器方法生效：静态资源等 handler 直接放行，避免误伤
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 1. 取生效注解：方法级优先，其次类级
        RequireRole required = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequireRole.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireRole.class);
        }
        // 2. 未标注或未声明角色 → 一律放行（登录即可）
        if (required == null || required.roles().length == 0) {
            return true;
        }
        // 3. 与 JWT 当前角色做包含匹配，命中即放行
        String currentRole = RequestUtils.currentRole();
        for (String allowed : required.roles()) {
            if (allowed != null && allowed.equals(currentRole)) {
                return true;
            }
        }
        // 4. 未命中 → 写出 403
        return reject(response, required.roles());
    }

    /** 403：与 JwtInterceptor 的 401 保持同一响应形态（HTTP 状态码 + Result 包装） */
    private boolean reject(HttpServletResponse response, String[] roles) throws Exception {
        String need = Arrays.stream(roles).collect(Collectors.joining(" / "));
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(403, "权限不足，需要角色：" + need)));
        return false;
    }
}
