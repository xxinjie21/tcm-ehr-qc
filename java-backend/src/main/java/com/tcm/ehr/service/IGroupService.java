package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.GroupDTOs;
import com.tcm.ehr.domain.po.ResearchGroup;
import com.tcm.ehr.domain.vo.GroupVOs;

import java.util.List;

/**
 * 课题组服务（阶段2 R2 身份解析 + R5 成员管理 / 审批）。
 */
public interface IGroupService extends IService<ResearchGroup> {

    // ---------------------------------------------------------- R2 身份解析

    /** 解析某用户当前生效的主组（含组内角色）；DB 故障降级为无组 */
    GroupResolution resolvePrimaryGroup(String userId);

    // ---------------------------------------------------------- R5 我的组

    /** GET /api/my-group */
    GroupVOs.MyGroupVO myGroup();

    // ---------------------------------------------------------- R5 管理员

    /** GET /api/groups?status= —— 组列表（可筛选状态） */
    List<GroupVOs.GroupInfo> listGroups(String status);

    /** POST /api/groups/{id}/approve —— pending → active，申请人成为组长（首任 owner 已是） */
    void approve(String groupId);

    /** POST /api/groups/{id}/reject —— pending → rejected，code 改写释放编码 */
    void reject(String groupId, String reason);

    /** PUT /api/groups/{id} —— 改名称 / 用途 */
    void updateGroup(String groupId, GroupDTOs.UpdateGroupRequest body);

    /** POST /api/groups/{id}/stop —— active → stopped（组员不能登录，数据保留） */
    void stop(String groupId);

    /** POST /api/groups/{id}/activate —— stopped → active */
    void activate(String groupId);

    /** GET /api/groups/pending-users —— 待分配池（排除 has_pending_group=1） */
    List<GroupVOs.PendingUserVO> pendingUsers();

    // ---------------------------------------------------------- R5 组长（本组）

    /** GET /api/groups/{id}/members */
    List<GroupVOs.MemberInfo> members(String groupId);

    /** POST /api/groups/{id}/members —— 从待分配池拉人（写 member） */
    void addMember(String groupId, String userId);

    /** DELETE /api/groups/{id}/members/{userId} */
    void removeMember(String groupId, String userId);

    /** PUT /api/groups/{id}/members/{userId}/transfer-owner —— 转让组长（原子两行） */
    void transferOwner(String groupId, String newOwnerUserId);

    /** POST /api/groups/{id}/leave —— 退出（组长须先指定继任者） */
    void leave(String groupId);
}