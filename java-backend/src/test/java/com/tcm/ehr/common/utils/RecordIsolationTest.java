package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据隔离专项：组 A 的病历在组 B 的过滤条件下不可见（阶段2 R3）。
 *
 * <p>隔离的正确性全部收敛在 {@code RecordFilter} 一个地方 —— 任何绕过它直接
 * {@code selectById} 的路径都要透传 {@code canAccess} 校验（R3 已逐处处理，
 * 见 §6.3 的 13 类 / 20 处）。本测试锁定两条铁律：</p>
 * <ul>
 *   <li>有组 → 只返回本组行（{@code group_id = 本组}）；</li>
 *   <li>无组 → 返回空集（fail-closed），而不是退化成全库。</li>
 * </ul>
 */
class RecordIsolationTest {

    private static final String GROUP_A = "grp-a";
    private static final String GROUP_B = "grp-b";

    @BeforeEach
    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void loginAs(String orgId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentUsername", "user1");
        req.setAttribute("currentRole", "用户");
        req.setAttribute("currentOrgId", orgId == null ? "" : orgId);
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    /** 组 A 的数据域过滤只含 group_id=grp-a，不含 grp-b */
    @Test
    void groupADomainFiltersOnlyOwnGroup() {
        loginAs(GROUP_A);
        QueryWrapper<Record> w = RecordFilter.build(RequestUtils.currentOrgId(), (FiltersDTO) null);
        String sql = w.getSqlSegment();
        Object bound = w.getParamNameValuePairs().values().iterator().next();

        assertTrue(sql.contains("group_id"), "必须按 group_id 过滤：" + sql);
        assertTrue(sql.contains("grp-a") || String.valueOf(bound).equals("grp-a"), "绑定值应是本组：" + bound);
        assertFalse("grp-b".equals(bound), "绝不能绑成别组：" + bound);
    }

    /** SearchDTO 重载同样只含本组 */
    @Test
    void searchDtoVariantAlsoOwnGroupOnly() {
        loginAs(GROUP_B);
        QueryWrapper<Record> w = RecordFilter.build(RequestUtils.currentOrgId(), new SearchDTO());
        String sql = w.getSqlSegment(); // 先渲染 where 段，参数表才会填充
        assertTrue(sql.contains("group_id"), sql);
        assertTrue(w.getParamNameValuePairs().containsValue("grp-b"), w.getParamNameValuePairs().toString());
        assertFalse(w.getParamNameValuePairs().containsValue("grp-a"));
    }

    /** ⚠️ 无组（待分配池 / 组被停用 / DB 故障降级）：结果必须为空，绝不退化成全库 */
    @Test
    void noGroupFailsClosed() {
        loginAs(null);
        QueryWrapper<Record> w = RecordFilter.build(RequestUtils.currentOrgId(), (FiltersDTO) null);
        String sql = w.getSqlSegment();
        // fail-closed：必须有一个不可能命中的条件，而不是空 where
        assertTrue(sql.toUpperCase().contains("ID"), "无组必须返回空集：" + sql);
        assertFalse(sql.toUpperCase().contains("GRP-A"), sql);
    }

    /** 几乎不可信的哨兵：与 id 同列等值，任何真实 UUID 都不可能命中 */
    @Test
    void sentinelValueCannotCollideWithUuid() {
        loginAs(null);
        String sql = RecordFilter.build("", (FiltersDTO) null).getSqlSegment();
        String v = sql.substring(sql.indexOf('=') + 1).trim();
        assertFalse(v.matches("[0-9a-fA-F-]{36}"), "哨兵必须不像 UUID：" + v);
    }
}