package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级判权口径 {@link RequestUtils#sameOrgScope} 的回归测试。
 *
 * <p>为什么需要它：批任务详情 / 取消用的是「按行判权」，而列表用的是
 * {@code org_id = currentOrgId()} 等值查询。两处口径一旦不一致，就会出现
 * 「列表里看得见、点进去说不存在」的自相矛盾 —— 无组织用户（{@code org_id=''}）
 * 正是这个形状：判权要求「当前组织非空」恒判否，他自己的任务也点不开、取消不掉。</p>
 *
 * <p>这条规则四个角都要钉住：空对空放行、空对非空判否、非空对空判否、非空对非空严格相等。</p>
 */
class OrgScopeRuleTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindOrg(String currentOrgId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (currentOrgId != null) {
            req.setAttribute(RequestUtils.ATTR_ORG_ID, currentOrgId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("两边都空 = 同一个空组织域：放行（与列表的 org_id='' 对得上）")
    void emptyOnBothSidesPasses() {
        bindOrg("");
        assertTrue(RequestUtils.sameOrgScope(""), "他自己的无组织任务必须能看、能取消");
        assertTrue(RequestUtils.sameOrgScope(null), "org_id 为 NULL 的老行同样属于空组织域");
    }

    @Test
    @DisplayName("一边空一边非空：判否（不能拿空组织去看真实组织的行）")
    void oneSideEmptyFails() {
        bindOrg("");
        assertFalse(RequestUtils.sameOrgScope("grp-default-2026"));

        bindOrg("grp-default-2026");
        assertFalse(RequestUtils.sameOrgScope(""));
        assertFalse(RequestUtils.sameOrgScope(null));
    }

    @Test
    @DisplayName("两边都非空：严格相等")
    void nonEmptyRequiresEquality() {
        bindOrg("grp-default-2026");
        assertTrue(RequestUtils.sameOrgScope("grp-default-2026"));
        assertFalse(RequestUtils.sameOrgScope("grp-other-2026"));
    }

    @Test
    @DisplayName("无请求上下文（后台线程未捕获组织）：一律判否，fail-closed")
    void noRequestContextFailsClosed() {
        RequestContextHolder.resetRequestAttributes();
        assertFalse(RequestUtils.sameOrgScope(""));
        assertFalse(RequestUtils.sameOrgScope("grp-default-2026"));
    }
}
