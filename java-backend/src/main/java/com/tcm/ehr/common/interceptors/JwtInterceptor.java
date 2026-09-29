package com.tcm.ehr.common.interceptors;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.JwtUtil;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.service.OrgResolution;
import com.tcm.ehr.service.IOrgService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 全局 JWT 鉴权（文档必做1、需求4.5安全性）。
 *
 * <p>拦截 /api/**，放行 /api/auth/login；校验通过后把当前操作人与当前组写入 request 属性
 * （供角色校验、数据域过滤与操作日志使用）。</p>
 *
 * <p><b>组为什么不放进 JWT</b>：移出成员 / 转让组长 / 停用组必须<b>立即</b>生效，
 * 而 token 有效期是 24h。所以 {@code userId/username/role} 走 claim，
 * {@code orgId/groupRole} 每请求查 {@code group_members}
 * （走 {@code INDEX(user_id,is_primary)}，本项目规模下开销可忽略）。</p>
 *
 * <p><b>代价与可用性耦合（必须知道）</b>：本类从「纯解析 JWT、不碰 DB」变成<b>每请求查库</b>。
 * DB 不可用时<b>不能抛异常</b> —— 那会让一次数据库抖动把全站打成 500（含登录与只读接口）。
 * 故 {@link IOrgService#resolvePrimaryOrg} 在 DB 故障时降级为「无组」，
 * 由 {@code RecordFilter} 的 fail-closed 兜住：用户看到空态，而不是报错，更不是越权。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final IOrgService groupService;

    /**
     * 管理员能否看到各组数据（阶段2 §4.4）。
     *
     * <p>{@code true}（默认）：管理员按自己所在的组看数据，与其它身份无差别。
     * {@code false}：管理员的 orgId 被<b>置空</b> → 复用 fail-closed → 看不到任何数据。
     * 数据层、SQL、前端、openapi 零改动，收紧只需改这一行配置 —— 这就是选「开关 + 置空」
     * 而不是「逐接口加 isAdmin」的原因：后者散落多处，漏一处就漏一个出口。</p>
     */
    @Value("${auth.admin-can-view-data:true}")
    private boolean adminCanViewData;

    @Override
    /**
     * 校验 Authorization 头里的 Bearer token；通过后把当前用户信息与当前组写入 request 属性
     * （currentUserId / currentUsername / currentRole / currentOrgId / currentOrgRole）。
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
        // 3. 解析声明并写 request 属性，供角色校验与操作日志使用
        Claims claims = jwtUtil.parseToken(token);
        String userId = claims.getSubject();
        String username = String.valueOf(claims.get("username"));
        String role = String.valueOf(claims.get("role"));
        request.setAttribute(RequestUtils.ATTR_USER_ID, userId);
        request.setAttribute(RequestUtils.ATTR_USERNAME, username);
        request.setAttribute(RequestUtils.ATTR_ROLE, role);

        // 4. 每请求解析当前组（不进 JWT，保证移人/停用立即生效）
        OrgResolution g = groupService.resolvePrimaryOrg(userId);
        String orgId = g.hasGroup() ? g.getOrgId() : null;
        // 4.1 管理员开关：置 false 时把 orgId 清空，复用数据层的 fail-closed
        if (RequestUtils.ROLE_ADMIN.equals(role) && !adminCanViewData) {
            orgId = null;
        }
        request.setAttribute(RequestUtils.ATTR_ORG_ID, orgId == null ? "" : orgId);
        request.setAttribute(RequestUtils.ATTR_ORG_ROLE, g.hasGroup() ? g.getGroupRole() : null);
        return true;
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(401, "未登录或token失效")));
        return false;
    }
}
