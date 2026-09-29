package com.tcm.ehr.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.domain.po.OrganizationMember;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.mapper.OrgMemberMapper;
import com.tcm.ehr.mapper.OrgMapper;
import com.tcm.ehr.mapper.UserMapper;
import com.tcm.ehr.service.impl.OrgServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 课题组服务测试（阶段2 R5）：审批通过 / 拒绝（code 改写）/ 转让组长 /
 * 组员晋升 / 拉人约束 / 停用。
 */
class OrgServiceTest {

    private OrgMapper groupMapper;
    private OrgMemberMapper memberMapper;
    private UserMapper userMapper;
    private OrgServiceImpl service;

    @BeforeEach
    void setUp() {
        // 业务用 RequestUtils.currentUsername() 记 reviewedBy，测试需挂请求上下文
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-admin");
        req.setAttribute("currentUsername", "admin");
        req.setAttribute("currentRole", "管理员");
        req.setAttribute("currentOrgId", "");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));

        groupMapper = mock(OrgMapper.class);
        memberMapper = mock(OrgMemberMapper.class);
        userMapper = mock(UserMapper.class);
        service = new OrgServiceImpl(memberMapper, userMapper);
        ReflectionTestUtils.setField(service, "baseMapper", groupMapper);
    }

    private Organization group(String id, String code, String status) {
        Organization g = new Organization();
        g.setId(id);
        g.setCode(code);
        g.setStatus(status);
        g.setAppliedBy("u-applicant");
        return g;
    }

    private User user(String id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole("用户");
        u.setStatus(User.STATUS_PENDING);
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

    /** 审批通过：状态转 active，申请人升 active */
    @Test
    void approveActivatesGroupAndApplicant() {
        when(groupMapper.selectById("g1")).thenReturn(group("g1", "NEURO-2026", Organization.PENDING));
        when(userMapper.selectById("u-applicant")).thenReturn(user("u-applicant", "zhangsan"));

        service.approve("g1");

        ArgumentCaptor<Organization> cap = ArgumentCaptor.forClass(Organization.class);
        verify(groupMapper).updateById(cap.capture());
        assertEquals(Organization.ACTIVE, cap.getValue().getStatus());
        assertEquals("admin", cap.getValue().getReviewedBy(), "审批人 = 当前操作人（管理员）");
        ArgumentCaptor<User> ucap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(ucap.capture());
        assertEquals(User.STATUS_ACTIVE, ucap.getValue().getStatus());
    }

    /** 拒绝：state=rejected + code 改写为 rej_<id>_<原code>（释放 UNIQUE 编码） */
    @Test
    void rejectRewritesCodeToReleaseUnique() {
        when(groupMapper.selectById("g1")).thenReturn(group("g1", "NEURO-2026", Organization.PENDING));
        when(userMapper.selectById("u-applicant")).thenReturn(user("u-applicant", "zhangsan"));

        service.reject("g1", "重复申请");

        ArgumentCaptor<Organization> cap = ArgumentCaptor.forClass(Organization.class);
        verify(groupMapper).updateById(cap.capture());
        Organization g = cap.getValue();
        assertEquals(Organization.REJECTED, g.getStatus());
        assertTrue(g.getCode().startsWith("rej_"), "code 必须改写释放编码：" + g.getCode());
        assertEquals("重复申请", g.getRejectReason());
    }

    /** 非 pending 状态不能获批 */
    @Test
    void approveRejectsNonPendingGroup() {
        when(groupMapper.selectById("g2")).thenReturn(group("g2", "X", Organization.ACTIVE));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.approve("g2"));
        assertEquals("只有待审批的组才能通过", ex.getMessage());
    }

    /** 转让组长：原子改两行（旧 owner→member，新 owner→owner） */
    @Test
    void transferOwnerSwapsRolesAtomically() {
        when(groupMapper.selectById("g1")).thenReturn(group("g1", "NEURO", Organization.ACTIVE));
        // 一次 selectList(组长)、一次 selectOne(memberOf)：分别返回旧组长与新组员
        when(memberMapper.selectList(any())).thenReturn(
                List.of(member("m-old", "g1", "u-old", OrganizationMember.ROLE_OWNER)));
        when(memberMapper.selectOne(any())).thenReturn(
                member("m-new", "g1", "u-new", OrganizationMember.ROLE_MEMBER));

        service.transferOwner("g1", "u-new");

        // 两次 updateById：旧组长降 member、新组长升 owner
        ArgumentCaptor<OrganizationMember> cap = ArgumentCaptor.forClass(OrganizationMember.class);
        verify(memberMapper, Mockito.times(2)).updateById(cap.capture());
        OrganizationMember first = cap.getAllValues().get(0);
        OrganizationMember second = cap.getAllValues().get(1);
        // 顺序：先降旧组长，再升新组长（两个调用里必然各含一个 owner 转变）
        assertTrue(first.getUserId().equals("u-old") && "member".equals(first.getRole())
                || second.getUserId().equals("u-old") && "member".equals(second.getRole()));
    }

    /** 拉人约束：已被其它组接走（active）或正在申请建组的用户不能拉 */
    @Test
    void addMemberRejectsActiveUser() {
        when(groupMapper.selectById("g1")).thenReturn(group("g1", "NEURO", Organization.ACTIVE));
        User activeUser = user("u-busy", "busy");
        activeUser.setStatus(User.STATUS_ACTIVE);
        when(userMapper.selectById("u-busy")).thenReturn(activeUser);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.addMember("g1", "u-busy"));
        assertTrue(e.getMessage().contains("无法拉入"), e.getMessage());
        verify(memberMapper, never()).insert(any(OrganizationMember.class));
    }

    /** 组不存在 → 1006，不落到 500 */
    @Test
    void requireGroupMissingThrows1006() {
        when(groupMapper.selectById("nope")).thenReturn(null);
        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.stop("nope"));
        assertEquals(1006, e.getCode());
    }
}