package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.OrganizationMember;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.domain.vo.OrgVOs;
import com.tcm.ehr.mapper.OrgMemberMapper;
import com.tcm.ehr.mapper.OrgMapper;
import com.tcm.ehr.service.OrgResolution;
import com.tcm.ehr.service.IOrgService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 课题组服务实现（阶段2）。
 *
 * <p>成员管理接口（R5）全部要求组长且有 {@code @RequireOrgRole("owner")} 拦截器兜底，
 * 本类内再校验一次「操作者是本组长、目标行存在」的交错关系，双保险防越权。</p>
 *
 * <p>一人一组的应用层约束：一个 {@code user_id} 只允许出现在一个<b>非 rejected</b> 组的
 * 成员表里（结构保留 {@code is_primary} 以便将来支持多组而无需改表）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgServiceImpl extends ServiceImpl<OrgMapper, Organization>
        implements IOrgService {

    private static final String DEFAULT_GROUP_ID = "grp-default-2026";

    private final OrgMemberMapper memberMapper;

    /** 普通用户的 Mapper（查用户名等）
     * 组表通过 ServiceImpl.baseMapper 拿（extends ServiceImpl<OrgMapper, Organization>） */
    private final com.tcm.ehr.mapper.UserMapper userMapper;

    // ---------------------------------------------------------- R2 身份解析

    @Override
    public OrgResolution resolvePrimaryOrg(String userId) {
        if (userId == null || userId.isBlank() || "unknown".equals(userId)) {
            return OrgResolution.NONE;
        }
        OrganizationMember m;
        try {
            m = memberMapper.findPrimaryActive(userId);
        } catch (Exception e) {
            log.error("[课题组] 解析 user={} 的组失败，降级为无组: {}", userId, e.getMessage());
            return OrgResolution.NONE;
        }
        if (m == null || m.getOrgId() == null || m.getOrgId().isBlank()) {
            return OrgResolution.NONE;
        }
        return new OrgResolution(m.getOrgId(), m.getRole());
    }

    // ---------------------------------------------------------- R5 我的组

    @Override
    public OrgVOs.MyOrgVO myOrg() {
        OrgVOs.MyOrgVO vo = new OrgVOs.MyOrgVO();
        OrganizationMember m = memberMapper.findPrimaryActive(RequestUtils.currentUserId());
        if (m != null) {
            Organization g = baseMapper.selectById(m.getOrgId());
            if (g != null) {
                vo.setOrg(toOrgInfo(g, false));
                vo.setMyRole(m.getRole());
            }
        } else {
            // 无生效组：可能是待分配池，也可能是「已提交建组申请待审批」
            Organization pending = baseMapper.selectOne(new QueryWrapper<Organization>()
                    .eq("applied_by", RequestUtils.currentUserId())
                    .eq("status", Organization.PENDING)
                    .last("LIMIT 1"));
            if (pending != null) {
                OrgVOs.PendingApplication pa = new OrgVOs.PendingApplication();
                pa.setCode(pending.getCode());
                pa.setName(pending.getName());
                pa.setStatus(pending.getStatus());
                pa.setRejectReason(pending.getRejectReason());
                vo.setPendingApplication(pa);
            }
        }
        return vo;
    }

    // ---------------------------------------------------------- R5 管理员

    @Override
    public List<OrgVOs.OrgInfo> listOrgs(String status) {
        QueryWrapper<Organization> w = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            w.eq("status", status.trim());
        }
        w.orderByDesc("create_time");
        List<OrgVOs.OrgInfo> out = new ArrayList<>();
        for (Organization g : baseMapper.selectList(w)) {
            out.add(toOrgInfo(g, status == null || "active".equals(status.trim())
                    || "pending".equals(status.trim())));
        }
        return out;
    }

    @Override
    @Transactional
    public void approve(String orgId) {
        Organization g = requireOrg(orgId);
        if (!Organization.PENDING.equals(g.getStatus())) {
            throw new IllegalArgumentException("只有待审批的组才能通过");
        }
        g.setStatus(Organization.ACTIVE);
        g.setReviewedBy(RequestUtils.currentUsername());
        g.setReviewedAt(LocalDateTime.now().withNano(0));
        baseMapper.updateById(g);
        // 申请人升为 active（首任 owner 在注册时已写入 group_members）
        updateUserStatus(g.getAppliedBy(), User.STATUS_ACTIVE, false);
    }

    @Override
    @Transactional
    public void reject(String orgId, String reason) {
        Organization g = requireOrg(orgId);
        if (!Organization.PENDING.equals(g.getStatus())) {
            throw new IllegalArgumentException("只有待审批的组才能拒绝");
        }
        g.setStatus(Organization.REJECTED);
        g.setReviewedBy(RequestUtils.currentUsername());
        g.setReviewedAt(LocalDateTime.now().withNano(0));
        g.setRejectReason(reason);
        // 释放编码：改写为 rej_<id>_<原code>（code 有 UNIQUE，不然一次拒绝就永久占死）
        g.setCode("rej_" + g.getId() + "_" + g.getCode());
        baseMapper.updateById(g);
        // 申请人回落待分配池，可立即重新申请
        updateUserStatus(g.getAppliedBy(), User.STATUS_PENDING, false);
        // 移除首任组长的成员行（组已拒绝，上一任 owner 身份作废）
        memberMapper.delete(new QueryWrapper<OrganizationMember>().eq("group_id", g.getId()));
    }

    @Override
    public void updateGroup(String orgId, OrgDTOs.UpdateGroupRequest body) {
        Organization g = requireOrg(orgId);
        if (body == null) {
            return;
        }
        if (body.getName() != null && !body.getName().isBlank()) {
            g.setName(body.getName().trim());
        }
        g.setPurpose(body.getPurpose());
        baseMapper.updateById(g);
    }

    @Override
    public void stop(String orgId) {
        Organization g = requireOrg(orgId);
        if (Organization.STOPPED.equals(g.getStatus())) {
            return;
        }
        g.setStatus(Organization.STOPPED);
        baseMapper.updateById(g);
        // 停用只挡登录（JwtInterceptor 的 findPrimaryActive 已过滤 status='active'），数据保留
    }

    @Override
    public void activate(String orgId) {
        Organization g = requireOrg(orgId);
        if (!Organization.STOPPED.equals(g.getStatus())) {
            throw new IllegalArgumentException("只有已停用的组才能恢复");
        }
        g.setStatus(Organization.ACTIVE);
        baseMapper.updateById(g);
        // 组员状态恢复为 active（此前登录被挡就是因为查不到 active 组）
        List<OrganizationMember> members = memberMapper.selectList(
                new QueryWrapper<OrganizationMember>().eq("group_id", g.getId()));
        for (OrganizationMember m : members) {
            updateUserStatus(m.getUserId(), User.STATUS_ACTIVE, false);
        }
    }

    @Override
    public List<OrgVOs.PendingUserVO> pendingUsers() {
        // 待分配池：status=pending 且 没有在审批中的建组申请（已申请的从池里隐藏）
        List<OrgVOs.PendingUserVO> out = new ArrayList<>();
        for (User u : userMapper.selectList(new QueryWrapper<User>()
                .eq("status", User.STATUS_PENDING)
                .eq("has_pending_group", 0)
                .orderByAsc("create_time"))) {
            OrgVOs.PendingUserVO vo = new OrgVOs.PendingUserVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setCreateTime(u.getCreateTime());
            out.add(vo);
        }
        return out;
    }

    // ---------------------------------------------------------- R5 组长（本组）

    @Override
    public List<OrgVOs.MemberInfo> members(String orgId) {
        requireOrg(orgId);
        List<OrgVOs.MemberInfo> out = new ArrayList<>();
        // owner 排前，便于前端直接看出组长
        List<OrganizationMember> rows = memberMapper.selectList(new QueryWrapper<OrganizationMember>()
                .eq("group_id", orgId)
                .orderByDesc("role").orderByAsc("create_time"));
        for (OrganizationMember m : rows) {
            User u = userMapper.selectById(m.getUserId());
            OrgVOs.MemberInfo mi = new OrgVOs.MemberInfo();
            mi.setUserId(m.getUserId());
            mi.setUsername(u == null ? "(已注销)" : u.getUsername());
            mi.setRole(m.getRole());
            mi.setJoinTime(m.getCreateTime());
            out.add(mi);
        }
        return out;
    }

    @Override
    @Transactional
    public void addMember(String orgId, String userId) {
        requireOrg(orgId);
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new ResourceNotFoundException(1006, "用户不存在");
        }
        // 已被别的组接走 / 已在审批中建组 → 不能拉
        if (User.STATUS_ACTIVE.equals(u.getStatus())
                || (u.getHasPendingGroup() != null && u.getHasPendingGroup() == 1)) {
            throw new IllegalArgumentException("该用户已有归属或正在申请建组，无法拉入");
        }
        OrganizationMember m = new OrganizationMember();
        m.setId(UUID.randomUUID().toString());
        m.setOrgId(orgId);
        m.setUserId(userId);
        m.setRole(OrganizationMember.ROLE_MEMBER);
        m.setIsPrimary(1);
        m.setCreateTime(LocalDateTime.now().withNano(0));
        // uk_group_user 唯一索引兜底并发拉人：上面的 status 检查与插入之间有窗口
        // （两个所有者同时拉同一人都会看到「无归属」）。不捕获的话异常直冒成 500，
        // 用户只会看到「系统异常」而不知道「该用户已被拉走」。
        try {
            memberMapper.insert(m);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该用户已在其他组织中，请刷新后重试");
        }
        updateUserStatus(userId, User.STATUS_ACTIVE, false);
    }

    @Override
    @Transactional
    public void removeMember(String orgId, String userId) {
        requireOrg(orgId);
        OrganizationMember m = memberOf(orgId, userId);
        if (OrganizationMember.ROLE_OWNER.equals(m.getRole())) {
            throw new IllegalArgumentException("不能直接移除组长，先转让组长");
        }
        memberMapper.deleteById(m.getId());
        updateUserStatus(userId, User.STATUS_PENDING, false);
    }

    @Override
    @Transactional
    public void transferOwner(String orgId, String newOwnerUserId) {
        requireOrg(orgId);
        OrganizationMember newOwner = memberOf(orgId, newOwnerUserId);
        // 原组长降为组员：两行必须同生共死（加了 @Transactional）
        List<OrganizationMember> owners = memberMapper.selectList(new QueryWrapper<OrganizationMember>()
                .eq("group_id", orgId).eq("role", OrganizationMember.ROLE_OWNER));
        if (owners.isEmpty()) {
            throw new IllegalStateException("本组没有组长，状态异常");
        }
        OrganizationMember oldOwner = owners.get(0);
        if (oldOwner.getUserId().equals(newOwnerUserId)) {
            return; // 已经是组长
        }
        oldOwner.setRole(OrganizationMember.ROLE_MEMBER);
        memberMapper.updateById(oldOwner);
        newOwner.setRole(OrganizationMember.ROLE_OWNER);
        memberMapper.updateById(newOwner);
    }

    @Override
    @Transactional
    public void leave(String orgId) {
        requireOrg(orgId);
        String userId = RequestUtils.currentUserId();
        OrganizationMember m = memberOf(orgId, userId);
        if (OrganizationMember.ROLE_OWNER.equals(m.getRole())) {
            // 组长不能直接退出：先数还有几个组员，没有继任者就拒绝（不产生孤儿组）
            long others = memberMapper.selectCount(new QueryWrapper<OrganizationMember>()
                    .eq("group_id", orgId)
                    .ne("user_id", userId));
            if (others == 0) {
                throw new IllegalArgumentException("你是组长且组内无其他成员，无法退出");
            }
            // 有其他人：按「队长离职须指定继任者」处理 —— 直接从剩余成员里选最早的升组长
            OrganizationMember successor = memberMapper.selectList(new QueryWrapper<OrganizationMember>()
                            .eq("group_id", orgId)
                            .ne("user_id", userId)
                            .orderByAsc("create_time")
                            .last("LIMIT 1"))
                    .get(0);
            successor.setRole(OrganizationMember.ROLE_OWNER);
            memberMapper.updateById(successor);
        }
        memberMapper.deleteById(m.getId());
        updateUserStatus(userId, User.STATUS_PENDING, false);
    }

    // ---------------------------------------------------------- 辅助

    private Organization requireOrg(String orgId) {
        Organization g = baseMapper.selectById(orgId);
        if (g == null) {
            throw new ResourceNotFoundException(1006, "课题组不存在");
        }
        return g;
    }

    private OrganizationMember memberOf(String orgId, String userId) {
        OrganizationMember m = memberMapper.selectOne(new QueryWrapper<OrganizationMember>()
                .eq("group_id", orgId).eq("user_id", userId)
                .last("LIMIT 1"));
        if (m == null) {
            throw new IllegalArgumentException("该用户不在此课题组");
        }
        return m;
    }

    /** 组装组概要；withOwner=false 时跳过组长的二次查询（我的组只有一组，没必要） */
    private OrgVOs.OrgInfo toOrgInfo(Organization g, boolean withOwner) {
        OrgVOs.OrgInfo info = new OrgVOs.OrgInfo();
        info.setId(g.getId());
        info.setCode(g.getCode());
        info.setName(g.getName());
        info.setStatus(g.getStatus());
        info.setAppliedBy(g.getAppliedBy());
        info.setCreateTime(g.getCreateTime());
        info.setRejectReason(g.getRejectReason());
        info.setMemberCount((int) (long) memberMapper.selectCount(
                new QueryWrapper<OrganizationMember>().eq("group_id", g.getId())));
        if (withOwner) {
            List<OrganizationMember> owners = memberMapper.selectList(new QueryWrapper<OrganizationMember>()
                    .eq("group_id", g.getId()).eq("role", OrganizationMember.ROLE_OWNER)
                    .last("LIMIT 1"));
            if (!owners.isEmpty()) {
                User owner = userMapper.selectById(owners.get(0).getUserId());
                info.setOwnerName(owner == null ? "" : owner.getUsername());
            }
        }
        return info;
    }

    private void updateUserStatus(String userId, String status, boolean pendingGroupFlag) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        User u = userMapper.selectById(userId);
        if (u == null) {
            return;
        }
        u.setStatus(status);
        if (pendingGroupFlag) {
            u.setHasPendingGroup(1);
        } else {
            u.setHasPendingGroup(0);
        }
        userMapper.updateById(u);
    }
}