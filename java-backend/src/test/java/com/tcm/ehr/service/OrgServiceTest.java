package com.tcm.ehr.service;

import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.domain.po.OrganizationMember;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.mapper.OrgMapper;
import com.tcm.ehr.mapper.OrgMemberMapper;
import com.tcm.ehr.mapper.UserMapper;
import com.tcm.ehr.service.impl.OrgServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组织服务测试（批次 6）：自助创建（含三道防滥用闸）/ 成员搜索 / 授权开关 /
 * 归档 / 改派所有者 / 停用。
 *
 * <p>审批（approve / reject）已随「去审核」移除，对应用例一并删除。</p>
 */
class OrgServiceTest {

    private OrgMapper orgMapper;
    private OrgMemberMapper memberMapper;
    private UserMapper userMapper;
    private OrgServiceImpl service;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-admin");
        req.setAttribute("currentUsername", "admin");
        req.setAttribute("currentRole", "管理员");
        req.setAttribute("currentOrgId", "");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        orgMapper = mock(OrgMapper.class);
        memberMapper = mock(OrgMemberMapper.class);
        userMapper = mock(UserMapper.class);
        service = new OrgServiceImpl(memberMapper, userMapper);
        ReflectionTestUtils.setField(service, "baseMapper", orgMapper);
        // requireOrg / memberOf 之外，多数用例需要「组织存在」
        when(orgMapper.selectById(any())).thenReturn(org("g1", "NEURO", Organization.ACTIVE));
    }

    private Organization org(String id, String code, String status) {
        Organization g = new Organization();
        g.setId(id);
        g.setCode(code);
        g.setName("测试组织");
        g.setStatus(status);
        g.setOwnerUserId("u-owner");
        return g;
    }

    private User user(String id, String username, String status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole("用户");
        u.setStatus(status);
        return u;
    }

    private OrganizationMember member(String id, String orgId, String userId, String role) {
        OrganizationMember m = new OrganizationMember();
        m.setId(id);
        m.setOrgId(orgId);
        m.setUserId(userId);
        m.setRole(role);
        m.setIsPrimary(1);
        return m;
    }

    private OrgDTOs.CreateOrgRequest createReq(String name, String code) {
        OrgDTOs.CreateOrgRequest r = new OrgDTOs.CreateOrgRequest();
        r.setName(name);
        r.setCode(code);
        return r;
    }

    // ---------------------------------------------------------------- 自助创建

    /** 创建者自动成为 owner，账号被置 active，三个写授权位默认 0 */
    @Test
    void createOrgMakesCreatorOwner() {
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(orgMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectById("u-new")).thenReturn(user("u-new", "zhangsan", User.STATUS_ACTIVE));

        service.createOrg(createReq("针灸组", "ACU-2026"), "u-new");

        ArgumentCaptor<Organization> gcap = ArgumentCaptor.forClass(Organization.class);
        verify(orgMapper).insert(gcap.capture());
        assertEquals("ACU-2026", gcap.getValue().getCode());
        assertEquals(Organization.ACTIVE, gcap.getValue().getStatus(), "自助创建直接生效，无 pending");
        assertEquals("u-new", gcap.getValue().getOwnerUserId());

        ArgumentCaptor<OrganizationMember> mcap = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberMapper).insert(mcap.capture());
        assertEquals(OrganizationMember.ROLE_OWNER, mcap.getValue().getRole());
        assertEquals(0, mcap.getValue().getCanWriteQcRules(), "授权位默认不勾");
    }

    /** 防滥用 1：名下组织数达上限 → 拒绝，且不落库 */
    @Test
    void createOrgRejectsWhenQuotaExceeded() {
        when(memberMapper.selectCount(any())).thenReturn(3L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createOrg(createReq("第四个", null), "u-heavy"));
        assertTrue(e.getMessage().contains("最多同时创建"), e.getMessage());
        verify(orgMapper, never()).insert(any(Organization.class));
    }

    /** 防滥用 2：编码白名单（DTO 侧也校验，服务端再兜一次） */
    @Test
    void createOrgRejectsBadCode() {
        when(memberMapper.selectCount(any())).thenReturn(0L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createOrg(createReq("坏编码", "../../etc"), "u1"));
        assertTrue(e.getMessage().contains("编码"), e.getMessage());
        verify(orgMapper, never()).insert(any(Organization.class));
    }

    /** 防滥用 3：名下同名组织 → 拒绝 */
    @Test
    void createOrgRejectsDuplicateName() {
        when(memberMapper.selectCount(any())).thenReturn(1L);
        OrganizationMember mine = member("m1", "g1", "u-dup", OrganizationMember.ROLE_OWNER);
        when(memberMapper.selectList(any())).thenReturn(List.of(mine));
        when(orgMapper.selectById("g1")).thenReturn(org("g1", "NEURO", Organization.ACTIVE));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createOrg(createReq("测试组织", null), "u-dup"));
        assertTrue(e.getMessage().contains("同名"), e.getMessage());
    }

    /** 编码留空时由服务端生成，避免并发抢同一序列 */
    @Test
    void createOrgGeneratesCodeWhenBlank() {
        when(memberMapper.selectCount(any())).thenReturn(0L);
        service.createOrg(createReq("无编码组", ""), "u2");

        ArgumentCaptor<Organization> cap = ArgumentCaptor.forClass(Organization.class);
        verify(orgMapper).insert(cap.capture());
        assertTrue(cap.getValue().getCode().startsWith("ORG-"), cap.getValue().getCode());
    }

    // ---------------------------------------------------------------- 归档 / 改派

    /** 归档前提「成员数为 0」：还有成员就拒绝（否则那些人会静默登不上） */
    @Test
    void archiveRejectsNonEmptyOrg() {
        when(memberMapper.selectCount(any())).thenReturn(2L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.archive("g1", "不用了"));
        assertTrue(e.getMessage().contains("还有 2 名成员"), e.getMessage());
        verify(orgMapper, never()).updateById(any(Organization.class));
    }

    /** 空组织可归档 */
    @Test
    void archiveEmptyOrgSucceeds() {
        when(memberMapper.selectCount(any())).thenReturn(0L);

        service.archive("g1", "不用了");

        ArgumentCaptor<Organization> cap = ArgumentCaptor.forClass(Organization.class);
        verify(orgMapper).updateById(cap.capture());
        assertEquals(Organization.ARCHIVED, cap.getValue().getStatus());
    }

    /** 改派所有者：旧 owner 降 member、新 owner 升 owner、冗余列同步 */
    @Test
    void reassignOwnerSwapsRolesAndSyncsColumn() {
        OrganizationMember oldOwner = member("m-old", "g1", "u-owner", OrganizationMember.ROLE_OWNER);
        OrganizationMember target = member("m-new", "g1", "u-new", OrganizationMember.ROLE_MEMBER);
        when(memberMapper.selectOne(any()))
                .thenReturn(oldOwner)   // 第一次：找当前 owner
                .thenReturn(target);    // 第二次：找目标成员
        when(userMapper.selectById("u-new")).thenReturn(user("u-new", "new", User.STATUS_ACTIVE));

        service.reassignOwner("g1", "u-new");

        ArgumentCaptor<OrganizationMember> cap = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberMapper, Mockito.times(2)).updateById(cap.capture());
        assertEquals(OrganizationMember.ROLE_MEMBER, cap.getAllValues().get(0).getRole());
        assertEquals(OrganizationMember.ROLE_OWNER, cap.getAllValues().get(1).getRole());

        ArgumentCaptor<Organization> gcap = ArgumentCaptor.forClass(Organization.class);
        verify(orgMapper).updateById(gcap.capture());
        assertEquals("u-new", gcap.getValue().getOwnerUserId(), "冗余列必须同事务同步");
    }

    /** 改派给已在其位的人 → 拒绝 */
    @Test
    void reassignOwnerRejectsSameOwner() {
        when(userMapper.selectById("u-owner")).thenReturn(user("u-owner", "o", User.STATUS_ACTIVE));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.reassignOwner("g1", "u-owner"));
        assertTrue(e.getMessage().contains("已经是"), e.getMessage());
    }

    // ---------------------------------------------------------------- 授权开关

    /** 两个开关独立：只传一个，另一位保持不变（null = 不改） */
    @Test
    void setPermissionsOnlyTouchesProvidedFlag() {
        OrganizationMember m = member("m1", "g1", "u-m", OrganizationMember.ROLE_MEMBER);
        m.setCanWriteDictionary(0);
        m.setCanWriteQcRules(1);
        when(memberMapper.selectOne(any())).thenReturn(m);

        service.setPermissions("g1", "u-m", true, null);

        ArgumentCaptor<OrganizationMember> cap = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberMapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getCanWriteDictionary(), "被指定的一位应更新");
        assertEquals(1, cap.getValue().getCanWriteQcRules(), "未指定的一位必须原样保留");
    }

    /** 不能给 owner 授权：owner 本就拥有全部写权限 */
    @Test
    void setPermissionsRejectsOwner() {
        when(memberMapper.selectOne(any()))
                .thenReturn(member("m1", "g1", "u-owner", OrganizationMember.ROLE_OWNER));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.setPermissions("g1", "u-owner", true, true));
        assertTrue(e.getMessage().contains("所有者"), e.getMessage());
    }

    /** 两个都没给 → 拒绝（一次无意义的写库） */
    @Test
    void setPermissionsRejectsEmptyRequest() {
        when(memberMapper.selectOne(any()))
                .thenReturn(member("m1", "g1", "u-m", OrganizationMember.ROLE_MEMBER));

        assertThrows(IllegalArgumentException.class,
                () -> service.setPermissions("g1", "u-m", null, null));
    }

    // ---------------------------------------------------------------- 成员搜索 / 拉人

    /** 搜索：关键词不足 2 字符直接拒（否则单字符会扫出全站用户） */
    @Test
    void searchUsersRejectsShortKeyword() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.searchUsers("a"));
        assertTrue(e.getMessage().contains("至少 2 个字符"), e.getMessage());
    }

    /** 搜索：只回 id 与 username，不回角色 / 状态 / 所属组织（防枚举） */
    @Test
    void searchUsersReturnsOnlyIdAndUsername() {
        when(userMapper.selectList(any()))
                .thenReturn(List.of(user("u-x", "zhangsan", User.STATUS_ACTIVE)));

        var out = service.searchUsers("zhang");

        assertEquals(1, out.size());
        assertEquals("u-x", out.get(0).getId());
        assertEquals("zhangsan", out.get(0).getUsername());
    }

    /** 拉人：已在其他组织 → 拒（一人一组织） */
    @Test
    void addMemberRejectsUserAlreadyInOrg() {
        when(userMapper.selectById("u-busy")).thenReturn(user("u-busy", "busy", User.STATUS_ACTIVE));
        when(memberMapper.selectCount(any())).thenReturn(1L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.addMember("g1", "u-busy"));
        assertTrue(e.getMessage().contains("已属于其他组织"), e.getMessage());
        verify(memberMapper, never()).insert(any(OrganizationMember.class));
    }

    /** 拉人：停用账号不拉（拉进来也登不了，只会让 owner 以为多了个人） */
    @Test
    void addMemberRejectsDisabledUser() {
        when(userMapper.selectById("u-off")).thenReturn(user("u-off", "off", User.STATUS_DISABLED));
        when(memberMapper.selectCount(any())).thenReturn(0L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.addMember("g1", "u-off"));
        assertTrue(e.getMessage().contains("已被停用"), e.getMessage());
    }

    /** 拉人成功：写 member 并把账号置 active */
    @Test
    void addMemberSucceedsForFreeUser() {
        when(userMapper.selectById("u-free")).thenReturn(user("u-free", "free", User.STATUS_ACTIVE));
        when(memberMapper.selectCount(any())).thenReturn(0L);

        service.addMember("g1", "u-free");

        ArgumentCaptor<OrganizationMember> cap = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberMapper).insert(cap.capture());
        assertEquals(OrganizationMember.ROLE_MEMBER, cap.getValue().getRole());
        assertNotNull(cap.getValue().getOrgId());
    }

    // ---------------------------------------------------------------- 生命周期

    /** 组织不存在 → 1006，不落到 500 */
    @Test
    void requireOrgMissingThrows1006() {
        when(orgMapper.selectById("nope")).thenReturn(null);

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.stop("nope"));
        assertEquals(1006, e.getCode());
    }

    /** 归档态不能停用 / 恢复（语义互斥） */
    @Test
    void stopRejectsArchivedOrg() {
        when(orgMapper.selectById("g-arch")).thenReturn(org("g-arch", "OLD", Organization.ARCHIVED));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.stop("g-arch"));
        assertTrue(e.getMessage().contains("已归档"), e.getMessage());
    }
}
