package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireGroupRole;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.GroupDTOs;
import com.tcm.ehr.domain.vo.GroupVOs;
import com.tcm.ehr.service.IGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课题组（阶段2 R5）。
 *
 * <p>权限分三层：</p>
 * <ul>
 *   <li><b>我的组</b>：登录即可（无组也能看自己是待分配池还是审批中）；</li>
 *   <li><b>管理 / 审批</b>：仅管理员（{@link RequireRole}）；</li>
 *   <li><b>成员管理</b>：组长 + 路径 groupId 必须等于当前组
 *       （{@link RequireGroupRole("owner")}，拦截器二次兜底）。</li>
 * </ul>
 *
 * <p>错误码统一走既有约定：参数/状态非法 → 400；资源不存在 → 404（1006）；
 * 越权 → 403（由拦截器与 ForbiddenException 出口）。</p>
 */
@RestController
@RequiredArgsConstructor
public class GroupController {

    private final IGroupService groupService;

    /** 我的组（组长 / 组员 / 申请人 / 待分配池统一入口） */
    @GetMapping("/api/my-group")
    public Result<GroupVOs.MyGroupVO> myGroup() {
        return Result.ok(groupService.myGroup());
    }

    // ----------------------------------------------------------- 管理员

    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/groups")
    public Result<List<GroupVOs.GroupInfo>> listGroups(
            @RequestParam(required = false) String status) {
        return Result.ok(groupService.listGroups(status));
    }

    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/groups/{id}/approve")
    public Result<Void> approve(@PathVariable String id) {
        groupService.approve(id);
        return Result.ok("已通过", null);
    }

    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/groups/{id}/reject")
    public Result<Void> reject(@PathVariable String id, @Valid @RequestBody GroupDTOs.RejectRequest body) {
        groupService.reject(id, body.getReason());
        return Result.ok("已拒绝", null);
    }

    @RequireRole(roles = {"管理员"})
    @PutMapping("/api/groups/{id}")
    public Result<Void> updateGroup(@PathVariable String id, @Valid @RequestBody GroupDTOs.UpdateGroupRequest body) {
        groupService.updateGroup(id, body);
        return Result.ok("已更新", null);
    }

    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/groups/{id}/stop")
    public Result<Void> stop(@PathVariable String id) {
        groupService.stop(id);
        return Result.ok("已停用", null);
    }

    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/groups/{id}/activate")
    public Result<Void> activate(@PathVariable String id) {
        groupService.activate(id);
        return Result.ok("已恢复", null);
    }

    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/groups/pending-users")
    public Result<List<GroupVOs.PendingUserVO>> pendingUsers() {
        return Result.ok(groupService.pendingUsers());
    }

    // ----------------------------------------------------------- 组长（本组）

    @RequireGroupRole("owner")
    @GetMapping("/api/groups/{id}/members")
    public Result<List<GroupVOs.MemberInfo>> members(@PathVariable String id) {
        return Result.ok(groupService.members(id));
    }

    @RequireGroupRole("owner")
    @PostMapping("/api/groups/{id}/members")
    public Result<Void> addMember(@PathVariable String id, @Valid @RequestBody GroupDTOs.AddMemberRequest body) {
        groupService.addMember(id, body.getUserId());
        return Result.ok("已加入", null);
    }

    @RequireGroupRole("owner")
    @DeleteMapping("/api/groups/{id}/members/{userId}")
    public Result<Void> removeMember(@PathVariable String id, @PathVariable String userId) {
        groupService.removeMember(id, userId);
        return Result.ok("已移除", null);
    }

    @RequireGroupRole("owner")
    @PutMapping("/api/groups/{id}/members/{userId}/transfer-owner")
    public Result<Void> transferOwner(@PathVariable String id, @PathVariable String userId,
                                      @Valid @RequestBody GroupDTOs.TransferOwnerRequest body) {
        groupService.transferOwner(id, body.getNewOwnerUserId());
        return Result.ok("组长已转让", null);
    }

    @RequireGroupRole("owner")
    @PostMapping("/api/groups/{id}/leave")
    public Result<Void> leave(@PathVariable String id) {
        groupService.leave(id);
        return Result.ok("已退出", null);
    }
}