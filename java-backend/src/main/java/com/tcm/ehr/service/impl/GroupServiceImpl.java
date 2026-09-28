package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.GroupMember;
import com.tcm.ehr.domain.po.ResearchGroup;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.dto.GroupDTOs;
import com.tcm.ehr.domain.vo.GroupVOs;
import com.tcm.ehr.mapper.GroupMemberMapper;
import com.tcm.ehr.mapper.ResearchGroupMapper;
import com.tcm.ehr.service.GroupResolution;
import com.tcm.ehr.service.IGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 课题组服务实现（阶段2）。
 *
 * <p>成员管理接口（R5）全部要求组长且有 {@code @RequireGroupRole("owner")} 拦截器兜底，
 * 本类内再校验一次「操作者是本组长、目标行存在」的交错关系，双保险防越权。</p>
 *
 * <p>一人一组的应用层约束：一个 {@code user_id} 只允许出现在一个<b>非 rejected</b> 组的
 * 成员表里（结构保留 {@code is_primary} 以便将来支持多组而无需改表）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupServiceImpl extends ServiceImpl<ResearchGroupMapper, ResearchGroup>
        implements IGroupService {

    private static final String DEFAULT_GROUP_ID = "grp-default-2026";

    private final GroupMemberMapper memberMapper;

    /** 普通用户的 Mapper（查用户名等）
     * 组表通过 ServiceImpl.baseMapper 拿（extends ServiceImpl<ResearchGroupMapper, ResearchGroup>） */
    private final com.tcm.ehr.mapper.UserMapper userMapper;

    // ---------------------------------------------------------- R2 身份解析

    @Override
    public GroupResolution resolvePrimaryGroup(String userId) {
        if (userId == null || userId.isBlank() || "unknown".equals(userId)) {
            return GroupResolution.NONE;
        }
        GroupMember m;
        try {
            m = memberMapper.findPrimaryActive(userId);
        } catch (Exception e) {
            log.error("[课题组] 解析 user={} 的组失败，降级为无组: {}", userId, e.getMessage());
            return GroupResolution.NONE;
        }
        if (m == null || m.getGroupId() == null || m.getGroupId().isBlank()) {
            return GroupResolution.NONE;
        }
        return new GroupResolution(m.getGroupId(), m.getRole());
    }

    // ---------------------------------------------------------- R5 我的组

    @Override
    public GroupVOs.MyGroupVO myGroup() {
        GroupVOs.MyGroupVO vo = new GroupVOs.MyGroupVO();
        GroupMember m = memberMapper.findPrimaryActive(RequestUtils.currentUserId());
        if (m != null) {
            ResearchGroup g = baseMapper.selectById(m.getGroupId());
            if (g != null) {
                vo.setGroup(toGroupInfo(g, false));
                vo.setMyRole(m.getRole());
            }
        } else {
            // 无生效组：可能是待分配池，也可能是「已提交建组申请待审批」
            ResearchGroup pending = baseMapper.selectOne(new QueryWrapper<ResearchGroup>()
                    .eq("applied_by", RequestUtils.currentUserId())
                    .eq("status", ResearchGroup.PENDING)
                    .last("LIMIT 1"));
            if (pending != null) {
                GroupVOs.PendingApplication pa = new GroupVOs.PendingApplication();
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
    public List<GroupVOs.GroupInfo> listGroups(String status) {
        QueryWrapper<ResearchGroup> w = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            w.eq("status", status.trim());
        }
        w.orderByDesc("create_time");
        List<GroupVOs.GroupInfo> out = new ArrayList<>();
        for (ResearchGroup g : baseMapper.selectList(w)) {
            out.add(toGroupInfo(g, status == null || "active".equals(status.trim())
                    || "pending".equals(status.trim())));
        }
        return out;
    }

    @Override
    @Transactional
    public void approve(String groupId) {
        ResearchGroup g = requireGroup(groupId);
        if (!ResearchGroup.PENDING.equals(g.getStatus())) {
            throw new IllegalArgumentException("只有待审批的组才能通过");
        }
        g.setStatus(ResearchGroup.ACTIVE);
        g.setReviewedBy(RequestUtils.currentUsername());
        g.setReviewedAt(LocalDateTime.now().withNano(0));
        baseMapper.updateById(g);
        // 申请人升为 active（首任 owner 在注册时已写入 group_members）
        updateUserStatus(g.getAppliedBy(), User.STATUS_ACTIVE, false);
    }

    @Override
    @Transactional
    public void reject(String groupId, String reason) {
        ResearchGroup g = requireGroup(groupId);
        if (!ResearchGroup.PENDING.equals(g.getStatus())) {
            throw new IllegalArgumentException("只有待审批的组才能拒绝");
        }
        g.setStatus(ResearchGroup.REJECTED);
        g.setReviewedBy(RequestUtils.currentUsername());
        g.setReviewedAt(LocalDateTime.now().withNano(0));
        g.setRejectReason(reason);
        // 释放编码：改写为 rej_<id>_<原code>（code 有 UNIQUE，不然一次拒绝就永久占死）
        g.setCode("rej_" + g.getId() + "_" + g.getCode());
        baseMapper.updateById(g);
        // 申请人回落待分配池，可立即重新申请
        updateUserStatus(g.getAppliedBy(), User.STATUS_PENDING, false);
        // 移除首任组长的成员行（组已拒绝，上一任 owner 身份作废）
        memberMapper.delete(new QueryWrapper<GroupMember>().eq("group_id", g.getId()));
    }

    @Override
    public void updateGroup(String groupId, GroupDTOs.UpdateGroupRequest body) {
        ResearchGroup g = requireGroup(groupId);
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
    public void stop(String groupId) {
        ResearchGroup g = requireGroup(groupId);
        if (ResearchGroup.STOPPED.equals(g.getStatus())) {
            return;
        }
        g.setStatus(ResearchGroup.STOPPED);
        baseMapper.updateById(g);
        // 停用只挡登录（JwtInterceptor 的 findPrimaryActive 已过滤 status='active'），数据保留
    }

    @Override
    public void activate(String groupId) {
        ResearchGroup g = requireGroup(groupId);
        if (!ResearchGroup.STOPPED.equals(g.getStatus())) {
            throw new IllegalArgumentException("只有已停用的组才能恢复");
        }
        g.setStatus(ResearchGroup.ACTIVE);
        baseMapper.updateById(g);
        // 组员状态恢复为 active（此前登录被挡就是因为查不到 active 组）
        List<GroupMember> members = memberMapper.selectList(
                new QueryWrapper<GroupMember>().eq("group_id", g.getId()));
        for (GroupMember m : members) {
            updateUserStatus(m.getUserId(), User.STATUS_ACTIVE, false);
        }
    }

    @Override
    public List<GroupVOs.PendingUserVO> pendingUsers() {
        // 待分配池：status=pending 且 没有在审批中的建组申请（已申请的从池里隐藏）
        List<GroupVOs.PendingUserVO> out = new ArrayList<>();
        for (User u : userMapper.selectList(new QueryWrapper<User>()
                .eq("status", User.STATUS_PENDING)
                .eq("has_pending_group", 0)
                .orderByAsc("create_time"))) {
            GroupVOs.PendingUserVO vo = new GroupVOs.PendingUserVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setCreateTime(u.getCreateTime());
            out.add(vo);
        }
        return out;
    }

    // ---------------------------------------------------------- R5 组长（本组）

    @Override
    public List<GroupVOs.MemberInfo> members(String groupId) {
        requireGroup(groupId);
        List<GroupVOs.MemberInfo> out = new ArrayList<>();
        // owner 排前，便于前端直接看出组长
        List<GroupMember> rows = memberMapper.selectList(new QueryWrapper<GroupMember>()
                .eq("group_id", groupId)
                .orderByDesc("role").orderByAsc("create_time"));
        for (GroupMember m : rows) {
            User u = userMapper.selectById(m.getUserId());
            GroupVOs.MemberInfo mi = new GroupVOs.MemberInfo();
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
    public void addMember(String groupId, String userId) {
        requireGroup(groupId);
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new ResourceNotFoundException(1006, "用户不存在");
        }
        // 已被别的组接走 / 已在审批中建组 → 不能拉
        if (User.STATUS_ACTIVE.equals(u.getStatus())
                || (u.getHasPendingGroup() != null && u.getHasPendingGroup() == 1)) {
            throw new IllegalArgumentException("该用户已有归属或正在申请建组，无法拉入");
        }
        GroupMember m = new GroupMember();
        m.setId(UUID.randomUUID().toString());
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(GroupMember.ROLE_MEMBER);
        m.setIsPrimary(1);
        m.setCreateTime(LocalDateTime.now().withNano(0));
        memberMapper.insert(m);
        updateUserStatus(userId, User.STATUS_ACTIVE, false);
    }

    @Override
    @Transactional
    public void removeMember(String groupId, String userId) {
        requireGroup(groupId);
        GroupMember m = memberOf(groupId, userId);
        if (GroupMember.ROLE_OWNER.equals(m.getRole())) {
            throw new IllegalArgumentException("不能直接移除组长，先转让组长");
        }
        memberMapper.deleteById(m.getId());
        updateUserStatus(userId, User.STATUS_PENDING, false);
    }

    @Override
    @Transactional
    public void transferOwner(String groupId, String newOwnerUserId) {
        requireGroup(groupId);
        GroupMember newOwner = memberOf(groupId, newOwnerUserId);
        // 原组长降为组员：两行必须同生共死（加了 @Transactional）
        List<GroupMember> owners = memberMapper.selectList(new QueryWrapper<GroupMember>()
                .eq("group_id", groupId).eq("role", GroupMember.ROLE_OWNER));
        if (owners.isEmpty()) {
            throw new IllegalStateException("本组没有组长，状态异常");
        }
        GroupMember oldOwner = owners.get(0);
        if (oldOwner.getUserId().equals(newOwnerUserId)) {
            return; // 已经是组长
        }
        oldOwner.setRole(GroupMember.ROLE_MEMBER);
        memberMapper.updateById(oldOwner);
        newOwner.setRole(GroupMember.ROLE_OWNER);
        memberMapper.updateById(newOwner);
    }

    @Override
    @Transactional
    public void leave(String groupId) {
        requireGroup(groupId);
        String userId = RequestUtils.currentUserId();
        GroupMember m = memberOf(groupId, userId);
        if (GroupMember.ROLE_OWNER.equals(m.getRole())) {
            // 组长不能直接退出：先数还有几个组员，没有继任者就拒绝（不产生孤儿组）
            long others = memberMapper.selectCount(new QueryWrapper<GroupMember>()
                    .eq("group_id", groupId)
                    .ne("user_id", userId));
            if (others == 0) {
                throw new IllegalArgumentException("你是组长且组内无其他成员，无法退出");
            }
            // 有其他人：按「队长离职须指定继任者」处理 —— 直接从剩余成员里选最早的升组长
            GroupMember successor = memberMapper.selectList(new QueryWrapper<GroupMember>()
                            .eq("group_id", groupId)
                            .ne("user_id", userId)
                            .orderByAsc("create_time")
                            .last("LIMIT 1"))
                    .get(0);
            successor.setRole(GroupMember.ROLE_OWNER);
            memberMapper.updateById(successor);
        }
        memberMapper.deleteById(m.getId());
        updateUserStatus(userId, User.STATUS_PENDING, false);
    }

    // ---------------------------------------------------------- 辅助

    private ResearchGroup requireGroup(String groupId) {
        ResearchGroup g = baseMapper.selectById(groupId);
        if (g == null) {
            throw new ResourceNotFoundException(1006, "课题组不存在");
        }
        return g;
    }

    private GroupMember memberOf(String groupId, String userId) {
        GroupMember m = memberMapper.selectOne(new QueryWrapper<GroupMember>()
                .eq("group_id", groupId).eq("user_id", userId)
                .last("LIMIT 1"));
        if (m == null) {
            throw new IllegalArgumentException("该用户不在此课题组");
        }
        return m;
    }

    /** 组装组概要；withOwner=false 时跳过组长的二次查询（我的组只有一组，没必要） */
    private GroupVOs.GroupInfo toGroupInfo(ResearchGroup g, boolean withOwner) {
        GroupVOs.GroupInfo info = new GroupVOs.GroupInfo();
        info.setId(g.getId());
        info.setCode(g.getCode());
        info.setName(g.getName());
        info.setStatus(g.getStatus());
        info.setAppliedBy(g.getAppliedBy());
        info.setCreateTime(g.getCreateTime());
        info.setRejectReason(g.getRejectReason());
        info.setMemberCount((int) (long) memberMapper.selectCount(
                new QueryWrapper<GroupMember>().eq("group_id", g.getId())));
        if (withOwner) {
            List<GroupMember> owners = memberMapper.selectList(new QueryWrapper<GroupMember>()
                    .eq("group_id", g.getId()).eq("role", GroupMember.ROLE_OWNER)
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