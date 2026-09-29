package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireOrgRole;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.domain.vo.OrgVOs;
import com.tcm.ehr.service.IOrgService;
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
 *   <li><b>成员管理</b>：组长 + 路径 orgId 必须等于当前组
 *       （{@link RequireOrgRole("owner")}，拦截器二次兜底）。</li>
 * </ul>
 *
 * <p>错误码统一走既有约定：参数/状态非法 → 400；资源不存在 → 404（1006）；
 * 越权 → 403（由拦截器与 ForbiddenException 出口）。</p>
 */
@RestController
@RequiredArgsConstructor
public class OrgController {

    private final IOrgService orgService;

    /** 我的组（组长 / 组员 / 申请人 / 待分配池统一入口） */
    @GetMapping({"/api/my-org", "/api/my-group"})
    public Result<OrgVOs.MyOrgVO> myOrg() {
        return Result.ok(orgService.myOrg());
    }

    // ----------------------------------------------------------- 管理员

    @RequireRole(roles = {"管理员"})
    @GetMapping({"/api/orgs", "/api/groups"})
    public Result<List<OrgVOs.OrgInfo>> listOrgs(
            @RequestParam(required = false) String status) {
        return Result.ok(orgService.listOrgs(status));
    }

    @RequireRole(roles = {"管理员"})
    @PostMapping({"/api/orgs/{id}/approve", "/api/groups/{id}/approve"})
    public Result<Void> approve(@PathVariable String id) {
        orgService.approve(id);
        return Result.ok("已通过", null);
    }

    @RequireRole(roles = {"管理员"})
    @PostMapping({"/api/orgs/{id}/reject", "/api/groups/{id}/reject"})
    public Result<Void> reject(@PathVariable String id, @Valid @RequestBody OrgDTOs.RejectRequest body) {
        orgService.reject(id, body.getReason());
        return Result.ok("已拒绝", null);
    }

    @RequireRole(roles = {"管理员"})
    @PutMapping({"/api/orgs/{id}", "/api/groups/{id}"})
    public Result<Void> updateGroup(@PathVariable String id, @Valid @RequestBody OrgDTOs.UpdateGroupRequest body) {
        orgService.updateGroup(id, body);
        return Result.ok("已更新", null);
    }

    @RequireRole(roles = {"管理员"})
    @PostMapping({"/api/orgs/{id}/stop", "/api/groups/{id}/stop"})
    public Result<Void> stop(@PathVariable String id) {
        orgService.stop(id);
        return Result.ok("已停用", null);
    }

    @RequireRole(roles = {"管理员"})
    @PostMapping({"/api/orgs/{id}/activate", "/api/groups/{id}/activate"})
    public Result<Void> activate(@PathVariable String id) {
        orgService.activate(id);
        return Result.ok("已恢复", null);
    }

    @RequireRole(roles = {"管理员"})
    @GetMapping({"/api/orgs/pending-users", "/api/groups/pending-users"})
    public Result<List<OrgVOs.PendingUserVO>> pendingUsers() {
        return Result.ok(orgService.pendingUsers());
    }

    // ----------------------------------------------------------- 组长（本组）

    @RequireOrgRole("owner")
    @GetMapping({"/api/orgs/{id}/members", "/api/groups/{id}/members"})
    public Result<List<OrgVOs.MemberInfo>> members(@PathVariable String id) {
        return Result.ok(orgService.members(id));
    }

    @RequireOrgRole("owner")
    @PostMapping({"/api/orgs/{id}/members", "/api/groups/{id}/members"})
    public Result<Void> addMember(@PathVariable String id, @Valid @RequestBody OrgDTOs.AddMemberRequest body) {
        orgService.addMember(id, body.getUserId());
        return Result.ok("已加入", null);
    }

    @RequireOrgRole("owner")
    @DeleteMapping({"/api/orgs/{id}/members/{userId}", "/api/groups/{id}/members/{userId}"})
    public Result<Void> removeMember(@PathVariable String id, @PathVariable String userId) {
        orgService.removeMember(id, userId);
        return Result.ok("已移除", null);
    }

    @RequireOrgRole("owner")
    @PutMapping({"/api/orgs/{id}/members/{userId}/transfer-owner", "/api/groups/{id}/members/{userId}/transfer-owner"})
    public Result<Void> transferOwner(@PathVariable String id, @PathVariable String userId,
                                      @Valid @RequestBody OrgDTOs.TransferOwnerRequest body) {
        orgService.transferOwner(id, body.getNewOwnerUserId());
        return Result.ok("组长已转让", null);
    }

    @RequireOrgRole("owner")
    @PostMapping({"/api/orgs/{id}/leave", "/api/groups/{id}/leave"})
    public Result<Void> leave(@PathVariable String id) {
        orgService.leave(id);
        return Result.ok("已退出", null);
    }
}