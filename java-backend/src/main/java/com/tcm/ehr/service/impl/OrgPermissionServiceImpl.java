package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.OrganizationMember;
import com.tcm.ehr.mapper.OrgMemberMapper;
import com.tcm.ehr.service.IOrgPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * 组织内写权限查询实现。
 *
 * <p>判定口径：<b>管理员与组织所有者天然拥有</b>；member 看对应授权位。
 * 两个开关相互独立 —— 授权位是 {@code organization_members} 上的两列。</p>
 *
 * <p>一次查询定论：不在「查一次授权位、又查一次成员行」里分两趟 ——
 * 两趟之间成员可能被移除，会出现「已判定有权限、实际已不在组织」的窗口。</p>
 *
 * <p>取不到成员行（无组织 / 组织被停用）一律 false：宁可少给权限，不可多给。</p>
 */
@Service
@RequiredArgsConstructor
public class OrgPermissionServiceImpl implements IOrgPermissionService {

    private final OrgMemberMapper memberMapper;

    @Override
    public boolean canWriteQcRules(String userId) {
        return allowed(userId, OrganizationMember::getCanWriteQcRules);
    }

    @Override
    public boolean canWriteDictionary(String userId) {
        return allowed(userId, OrganizationMember::getCanWriteDictionary);
    }

    /** owner 恒放行；member 看授权位；无成员行 → false */
    private boolean allowed(String userId, Function<OrganizationMember, Integer> flag) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        OrganizationMember m = memberMapper.findPrimaryActive(userId);
        if (m == null) {
            return false;
        }
        if (OrganizationMember.ROLE_OWNER.equals(m.getRole())) {
            return true;
        }
        return Integer.valueOf(1).equals(flag.apply(m));
    }
}
