package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.domain.vo.OrgVOs;

import java.util.List;

/**
 * 组织服务（批次 6：自助创建 + 成员搜索 / 授权开关 + 归档 / 改派）。
 */
public interface IOrgService extends IService<Organization> {

    // ---------------------------------------------------------- R2 身份解析

    /** 解析某用户当前生效的主组（含组内角色）；DB 故障降级为无组 */
    OrgResolution resolvePrimaryOrg(String userId);

    // ---------------------------------------------------------- R5 我的组

    /** GET /api/my-group */
    OrgVOs.MyOrgVO myOrg();

    // ---------------------------------------------------------- R5 管理员

    /** GET /api/orgs?status= —— 组织列表（可筛选状态） */
    List<OrgVOs.OrgInfo> listOrgs(String status);

    /**
     * POST /api/orgs —— 自助创建组织，<b>创建者自动成为 owner</b>，无审核。
     *
     * <p>创建者同时被写入 {@code users.status=active}（此前他是「无归属」状态）。</p>
     */
    OrgVOs.OrgInfo createOrg(OrgDTOs.CreateOrgRequest body, String creatorUserId);

    /** PUT /api/orgs/{id} —— 改名称 / 用途 */
    void updateOrg(String orgId, OrgDTOs.UpdateGroupRequest body);

    /** POST /api/orgs/{id}/stop —— active → stopped（成员不能登录，数据保留） */
    void stop(String orgId);

    /** POST /api/orgs/{id}/activate —— stopped → active */
    void activate(String orgId);

    /** POST /api/orgs/{id}/archive —— 成员数为 0 时归档（不自动归档，避免误伤沉睡组织） */
    void archive(String orgId, String reason);

    /** POST /api/orgs/{id}/reassign-owner —— 仅管理员；owner 账号丢失时的兜底 */
    void reassignOwner(String orgId, String newOwnerUserId);

    /**
     * GET /api/orgs/users?keyword= —— 按用户名搜索可拉入的候选人。
     *
     * <p>返回刻意<b>只有 id 与 username</b>：不返回角色 / 状态 / 所属组织，
     * 否则就是一个「全站用户名 + 组织归属」的枚举接口。</p>
     */
    List<OrgVOs.UserBriefVO> searchUsers(String keyword);

    // ---------------------------------------------------------- R5 组长（本组）

    /** GET /api/orgs/{id}/members */
    List<OrgVOs.MemberInfo> members(String orgId);

    /** POST /api/orgs/{id}/members —— 按用户名搜索后拉人（写 member） */
    void addMember(String orgId, String userId);

    /** DELETE /api/orgs/{id}/members/{userId} */
    void removeMember(String orgId, String userId);

    /** PUT /api/orgs/{id}/members/{userId}/transfer-owner —— 转让所有者（原子两行） */
    void transferOwner(String orgId, String newOwnerUserId);

    /** PUT /api/orgs/{id}/members/{userId}/permissions —— 授予 / 回收成员的两个写开关 */
    void setPermissions(String orgId, String userId, Boolean canWriteDictionary, Boolean canWriteQcRules);

    /** POST /api/orgs/{id}/leave —— 退出（owner 须先指定继任者） */
    void leave(String orgId);
}