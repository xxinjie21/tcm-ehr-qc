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
 * 「管理员看全部」开关的回归测试。
 *
 * <p>事故背景：{@code RequestUtils.viewAllOrgs()} 写成
 * {@code Boolean.TRUE.equals(attr(ATTR_VIEW_ALL_ORGS))}，而 {@code attr()} 是给
 * 角色 / 用户名这类<b>文本</b>属性用的 —— 它把值 {@code String.valueOf} 成字符串、
 * 并把空值兜成 {@code "unknown"}。于是比较的是 {@code Boolean.TRUE.equals("true")}，
 * <b>恒为 false</b>：{@code auth.admin-can-view-data} 这个配置项从来就没生效过，
 * 管理员被永久限定在自己所属组织里（实测：库里 1000 条、两个组织各 500，
 * 管理员只能看到自己那 500 条）。</p>
 *
 * <p>这类 bug 不会报错、不会抛异常，只是让一个权限开关静默失效 ——
 * 所以必须用测试把它钉住。</p>
 */
class ViewAllOrgsFlagTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bind(Object value) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (value != null) {
            req.setAttribute(RequestUtils.ATTR_VIEW_ALL_ORGS, value);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("标记为 Boolean.TRUE 时必须读成 true（这正是修复前的失效点）")
    void booleanTrueIsReadAsTrue() {
        bind(Boolean.TRUE);
        assertTrue(RequestUtils.viewAllOrgs(),
                "拦截器写入的是 Boolean.TRUE，读取也必须得到 true；"
                        + "若这里为 false，说明又退回了用会字符串化的 attr()");
    }

    @Test
    @DisplayName("标记为 Boolean.FALSE 时读成 false")
    void booleanFalseIsReadAsFalse() {
        bind(Boolean.FALSE);
        assertFalse(RequestUtils.viewAllOrgs());
    }

    @Test
    @DisplayName("未设置标记时读成 false（fail-closed：看不到比看到安全）")
    void missingFlagIsFalse() {
        bind(null);
        assertFalse(RequestUtils.viewAllOrgs());
    }

    @Test
    @DisplayName("无请求上下文时读成 false，不抛异常")
    void noRequestContextIsFalse() {
        RequestContextHolder.resetRequestAttributes();
        assertFalse(RequestUtils.viewAllOrgs());
    }

    @Test
    @DisplayName("文本属性仍走 attr()，不受本次修复影响（角色/用户名语义不变）")
    void textAttributesStillWork() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_ROLE, "管理员");
        req.setAttribute(RequestUtils.ATTR_USERNAME, "alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertTrue(RequestUtils.isAdmin());
        assertTrue("alice".equals(RequestUtils.currentUsername()));
    }
}
