package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.GroupMember;
import com.tcm.ehr.domain.po.ResearchGroup;
import com.tcm.ehr.mapper.GroupMemberMapper;
import com.tcm.ehr.mapper.ResearchGroupMapper;
import com.tcm.ehr.service.GroupResolution;
import com.tcm.ehr.service.IGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 课题组服务实现（阶段2 R2）。
 *
 * <p>R2 只实现「身份解析」；成员管理 / 审批 / 转让等在 R5 追加。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupServiceImpl extends ServiceImpl<ResearchGroupMapper, ResearchGroup>
        implements IGroupService {

    private final GroupMemberMapper groupMemberMapper;

    @Override
    public GroupResolution resolvePrimaryGroup(String userId) {
        // 1. 无 userId（未登录 / 解析失败）直接判无组
        if (userId == null || userId.isBlank() || "unknown".equals(userId)) {
            return GroupResolution.NONE;
        }
        // 2. 查主组；DB 故障时**降级为无组**而不是抛异常
        //    ⚠️ 这里必须降级：JwtInterceptor 从「不碰 DB」变成「每请求碰 DB」，
        //    直接抛异常会让一次数据库抖动把全站打成 500（含登录与只读接口）。
        //    降级为无组后走 RecordFilter 的 fail-closed，用户看到空态而不是报错 ——
        //    「服务不可用」与「你没权限」都比「全站 500」更接近真相，且不放大故障。
        GroupMember m;
        try {
            m = groupMemberMapper.findPrimaryActive(userId);
        } catch (Exception e) {
            log.error("[课题组] 解析 user={} 的组失败，降级为无组: {}", userId, e.getMessage());
            return GroupResolution.NONE;
        }
        // 3. 无组 / 组已停用（findPrimaryActive 已过滤 status）都走同一分支
        if (m == null || m.getGroupId() == null || m.getGroupId().isBlank()) {
            return GroupResolution.NONE;
        }
        return new GroupResolution(m.getGroupId(), m.getRole());
    }

    /** 当前登录用户是否管理员（系统级角色，不含组内角色） */
    public static boolean isAdmin() {
        return RequestUtils.isAdmin();
    }
}
