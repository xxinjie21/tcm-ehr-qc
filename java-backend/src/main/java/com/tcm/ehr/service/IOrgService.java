package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.domain.vo.OrgVOs;

import java.util.List;

/**
 * 课题组服务（阶段2 R2 身份解析 + R5 成员管理 / 审批）。
 */
public interface IOrgService extends IService<Organization> {

    // ---------------------------------------------------------- R2 身份解析

    /** 解析某用户当前生效的主组（含组内角色）；DB 故障降级为无组 */
    OrgResolution resolvePrimaryOrg(String userId);

    // ---------------------------------------------------------- R5 我的组

    /** GET /api/my-group */
    OrgVOs.MyOrgVO myOrg();

    // ---------------------------------------------------------- R5 管理员

    /** GET /api/groups?status= —— 组列表（可筛选状态） */
    List<OrgVOs.OrgInfo> listOrgs(String status);

    /** POST /api/groups/{id}/approve —— pending → active，申请人成为组长（首任 owner 已是） */
    void approve(String orgId);

    /** POST /api/groups/{id}/reject —— pending → rejected，code 改写释放编码 */
    void reject(String orgId, String reason);

    /** PUT /api/groups/{id} —— 改名称 / 用途 */
    void updateGroup(String orgId, OrgDTOs.UpdateGroupRequest body);

    /** POST /api/groups/{id}/stop —— active → stopped（组员不能登录，数据保留） */
    void stop(String orgId);

    /** POST /api/groups/{id}/activate —— stopped → active */
    void activate(String orgId);

    /** GET /api/groups/pending-users —— 待分配池（排除 has_pending_group=1） */
    List<OrgVOs.PendingUserVO> pendingUsers();

    // ---------------------------------------------------------- R5 组长（本组）

    /** GET /api/groups/{id}/members */
    List<OrgVOs.MemberInfo> members(String orgId);

    /** POST /api/groups/{id}/members —— 从待分配池拉人（写 member） */
    void addMember(String orgId, String userId);

    /** DELETE /api/groups/{id}/members/{userId} */
    void removeMember(String orgId, String userId);

    /** PUT /api/groups/{id}/members/{userId}/transfer-owner —— 转让组长（原子两行） */
    void transferOwner(String orgId, String newOwnerUserId);

    /** POST /api/groups/{id}/leave —— 退出（组长须先指定继任者） */
    void leave(String orgId);
}