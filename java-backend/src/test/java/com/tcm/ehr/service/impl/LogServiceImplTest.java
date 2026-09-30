package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.mapper.OperationLogMapper;
import com.tcm.ehr.mapper.OrgMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日志三档可见范围（批次 7 收口）。
 *
 * <p>这属于<b>数据可见性</b>而非展示逻辑：判错一档就是越权读别人的操作记录，
 * 必须逐档锁死，不能靠「前端本来就看不到」兜底。</p>
 *
 * <p>三档：管理员看全部 / 所有者看本组织 / 成员只看本组织内自己 / 无组织只看自己。</p>
 */
class LogServiceImplTest {

    private OperationLogMapper logMapper;
    private OrgMapper orgMapper;
    private LogServiceImpl service;

    /** 捕获 page() 实际下发的 wrapper —— 可见范围就藏在这个对象里 */
    private QueryWrapper<OperationLog> capturedWrapper;

    @BeforeEach
    void setUp() {
        logMapper = mock(OperationLogMapper.class);
        orgMapper = mock(OrgMapper.class);
        service = new LogServiceImpl(logMapper, orgMapper);

        capturedWrapper = null;
        // selectPage 默认返回 null 会让 page() NPE，这里统一给一个空页。
        // 用 thenAnswer 顺手把 wrapper 记下来：ArgumentCaptor 只在 verify() 时回填，
        // 放在 stubbing() 里捕获永远是 null。
        when(logMapper.selectPage(any(), any())).thenAnswer(inv -> {
            capturedWrapper = inv.getArgument(1);
            return new Page<>(1, 10);
        });
        when(orgMapper.selectBatchIds(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void loginAs(String role, String orgId, String orgRole, String username) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentUsername", username);
        req.setAttribute("currentRole", role);
        req.setAttribute("currentOrgId", orgId == null ? "" : orgId);
        req.setAttribute("currentOrgRole", orgRole);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    /** 跑一次 page() 并把下发的 wrapper 渲染出来（渲染后参数表才会填充） */
    private QueryWrapper<OperationLog> wrapperAfterPage() {
        service.page(null, null, 1, 10);
        QueryWrapper<OperationLog> w = capturedWrapper;
        assertTrue(w != null, "selectPage 未被调用");
        w.getSqlSegment();
        return w;
    }

    // ------------------------------------------------------------------ 三档可见范围

    /** 管理员：无条件，能看到全部组织的日志 */
    @Test
    void adminSeesAllOrgs() {
        loginAs("管理员", "", null, "admin");
        String sql = wrapperAfterPage().getSqlSegment();
        assertFalse(sql.contains("org_id"), "管理员不应带组织过滤：" + sql);
        assertFalse(sql.contains("operator"), "管理员不应带操作人过滤：" + sql);
    }

    /** 所有者：本组织全员，但不收窄到个人 */
    @Test
    void ownerSeesWholeOrg() {
        loginAs("用户", "g1", "owner", "owner1");
        QueryWrapper<OperationLog> w = wrapperAfterPage();
        String sql = w.getSqlSegment();
        assertTrue(sql.contains("org_id"), "所有者应按组织过滤：" + sql);
        assertTrue(w.getParamNameValuePairs().containsValue("g1"), w.getParamNameValuePairs().toString());
        assertFalse(sql.contains("operator"), "所有者不应只看自己：" + sql);
    }

    /** 成员：本组织 + 只看自己（两道条件缺一即越权） */
    @Test
    void memberSeesOnlySelfInOrg() {
        loginAs("用户", "g1", "member", "member1");
        QueryWrapper<OperationLog> w = wrapperAfterPage();
        String sql = w.getSqlSegment();
        Map<String, Object> vals = w.getParamNameValuePairs();
        assertTrue(sql.contains("org_id"), "成员应按组织过滤：" + sql);
        assertTrue(sql.contains("operator"), "成员必须再按操作人收窄：" + sql);
        assertTrue(vals.containsValue("g1"), vals.toString());
        assertTrue(vals.containsValue("member1"), vals.toString());
    }

    /** 无组织：只能看自己，且不能带组织过滤（没有组织可依） */
    @Test
    void noOrgSeesOnlySelf() {
        loginAs("用户", "", null, "nobody");
        QueryWrapper<OperationLog> w = wrapperAfterPage();
        String sql = w.getSqlSegment();
        assertFalse(sql.contains("org_id"), "无组织时不应按组织过滤：" + sql);
        assertTrue(sql.contains("operator"), "无组织时只能看自己：" + sql);
        assertTrue(w.getParamNameValuePairs().containsValue("nobody"), w.getParamNameValuePairs().toString());
    }

    // ------------------------------------------------------------------ 所属组织列

    /** 「所属组织」列：org_id 解析成组织名；查不到的给「—」而不是空串 */
    @Test
    void fillsOrgNameAndUsesDashWhenUnknown() {
        Organization org = new Organization();
        org.setId("g1");
        org.setName("针灸组");
        when(orgMapper.selectBatchIds(any())).thenReturn(List.of(org));

        Page<OperationLog> page = new Page<>(1, 10);
        page.setRecords(List.of(log("g1"), log("g-deleted")));
        when(logMapper.selectPage(any(), any())).thenAnswer(inv -> page);

        Map<String, Object> res = service.page(null, null, 1, 10);

        @SuppressWarnings("unchecked")
        List<OperationLog> rows = (List<OperationLog>) res.get("list");
        assertEquals("针灸组", rows.get(0).getOrgName());
        assertEquals("—", rows.get(1).getOrgName(),
                "组织已删的日志要显示「—」，空串会被误读成「查到了但没值」");
    }

    private OperationLog log(String orgId) {
        OperationLog l = new OperationLog();
        l.setOperator("u1");
        l.setOrgId(orgId);
        return l;
    }

    // ------------------------------------------------------------------ 导出

    /** 导出 CSV 带上「所属组织」列，否则导出与页面对不上 */
    @Test
    void csvContainsOrgColumn() {
        Organization org = new Organization();
        org.setId("g1");
        org.setName("针灸组");
        when(orgMapper.selectBatchIds(any())).thenReturn(List.of(org));
        when(logMapper.selectList(any())).thenReturn(List.of(log("g1")));

        String csv = new String(service.exportCsv(null, null), StandardCharsets.UTF_8);

        assertTrue(csv.contains("所属组织"), csv);
        assertTrue(csv.contains("针灸组"), csv);
    }
}