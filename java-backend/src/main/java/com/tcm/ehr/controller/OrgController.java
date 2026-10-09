package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireOrgRole;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.common.utils.RequestUtils;
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

    // 28.2：组织 / 权限三条写路径此前完全没有审计日志（全后端 6 个写入方不含它们）
    private final OperationLogger operationLogger;

    /** 我的组织（所有者 / 成员 / 未加入组织，统一入口） */
    @GetMapping({"/api/my-org", "/api/my-group"})
    public Result<OrgVOs.MyOrgVO> myOrg() {
        return Result.ok(orgService.myOrg());
    }

    // ----------------------------------------------------------- 多组织（登录即可）

    /**
     * 当前用户所属的所有 active 组织（含「是否当前组织」标记）。
     *
     * <p>供右上角「切换组织」下拉使用。只列 active 组织。</p>
     */
    @GetMapping("/api/orgs/mine")
    public Result<List<OrgVOs.MyOrgItem>> myOrgs() {
        return Result.ok(orgService.myOrgs());
    }

    /**
     * 切换到指定组织（多组织）。
     *
     * <p>【权限：登录即可，服务层校验「是该成员 且 组织 active」】
     * 注意：<b>不能</b>加 {@code @RequireOrgRole} —— 切换前 currentOrg 仍是旧组织，
     * 加了会被「路径机构 != 当前机构」拦掉，永远切不过去。</p>
     */
    @PostMapping("/api/orgs/{id}/switch")
    public Result<Void> switchOrg(@PathVariable String id) {
        orgService.switchOrg(id);
        return Result.ok("已切换组织", null);
    }

    // ----------------------------------------------------------- 管理员

    @RequireRole(roles = {"管理员"})
    @GetMapping({"/api/orgs", "/api/groups"})
    public Result<List<OrgVOs.OrgInfo>> listOrgs(
            @RequestParam(required = false) String status) {
        return Result.ok(orgService.listOrgs(status));
    }

    /**
     * 自助创建组织（批次 6）。
     *
     * <p>【权限：登录即可】创建者自动成为 owner。<b>只挂新路径</b>：这是新增能力，
     * 旧路径下本来不存在「自助创建」，给两个名字只会让人以为旧路径也能调。</p>
     */
    @PostMapping("/api/orgs")
    public Result<OrgVOs.OrgInfo> createOrg(@Valid @RequestBody OrgDTOs.CreateOrgRequest body) {
        return Result.ok("已创建，你是该组织所有者", orgService.createOrg(body, RequestUtils.currentUserId()));
    }

    @RequireRole(roles = {"管理员"})
    @PutMapping({"/api/orgs/{id}", "/api/groups/{id}"})
    public Result<Void> updateOrg(@PathVariable String id, @Valid @RequestBody OrgDTOs.UpdateGroupRequest body) {
        orgService.updateOrg(id, body);
        return Result.ok("已更新", null);
    }

    /** 归档（仅管理员，前提成员数为 0） */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/orgs/{id}/archive")
    public Result<Void> archive(@PathVariable String id, @Valid @RequestBody OrgDTOs.ArchiveOrgRequest body) {
        orgService.archive(id, body.getReason());
        return Result.ok("已归档", null);
    }

    /**
     * 改派所有者（仅管理员）：owner 账号丢失时的兜底。
     *
     * <p>没有它，唯一能让组织脱离「无人可管」的方式是等原 owner 回来。</p>
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/orgs/{id}/reassign-owner")
    public Result<Void> reassignOwner(@PathVariable String id,
                                      @Valid @RequestBody OrgDTOs.TransferOwnerRequest body) {
        orgService.reassignOwner(id, body.getNewOwnerUserId());
        return Result.ok("已改派所有者", null);
    }

    /**
     * 按用户名搜索可拉入的成员（登录即可）。
     *
     * <p>【权限：登录即可】关键词至少 2 字符、最多回 20 条，且只返回 id 与 username。</p>
     */
    @GetMapping("/api/orgs/users")
    public Result<List<OrgVOs.UserBriefVO>> searchUsers(@RequestParam String keyword) {
        return Result.ok(orgService.searchUsers(keyword));
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

    // ----------------------------------------------------------- 所有者（本组织）

    @RequireOrgRole(value = "owner", pathVar = "id")
    @GetMapping({"/api/orgs/{id}/members", "/api/groups/{id}/members"})
    public Result<List<OrgVOs.MemberInfo>> members(@PathVariable String id) {
        return Result.ok(orgService.members(id));
    }

    @RequireOrgRole(value = "owner", pathVar = "id")
    @PostMapping({"/api/orgs/{id}/members", "/api/groups/{id}/members"})
    public Result<Void> addMember(@PathVariable String id, @Valid @RequestBody OrgDTOs.AddMemberRequest body) {
        orgService.addMember(id, body.getUserId());
        return Result.ok("已加入", null);
    }

    @RequireOrgRole(value = "owner", pathVar = "id")
    @DeleteMapping({"/api/orgs/{id}/members/{userId}", "/api/groups/{id}/members/{userId}"})
    public Result<Void> removeMember(@PathVariable String id, @PathVariable String userId) {
        orgService.removeMember(id, userId);
        operationLogger.log("移除成员", id, "移除成员 " + userId);
        return Result.ok("已移除", null);
    }

    @RequireOrgRole(value = "owner", pathVar = "id")
    @PutMapping({"/api/orgs/{id}/members/{userId}/transfer-owner", "/api/groups/{id}/members/{userId}/transfer-owner"})
    public Result<Void> transferOwner(@PathVariable String id, @PathVariable String userId,
                                      @Valid @RequestBody OrgDTOs.TransferOwnerRequest body) {
        orgService.transferOwner(id, body.getNewOwnerUserId());
        operationLogger.log("转让所有权", id, "新所有者 " + body.getNewOwnerUserId());
        return Result.ok("所有者已转让", null);
    }

    /**
     * 授予 / 回收成员的两个写开关（批次 6）。
     *
     * <p>【权限：所有者（本组织）】两个开关独立，null 表示「这一位不改」。</p>
     */
    @RequireOrgRole(value = "owner", pathVar = "id")
    @PutMapping({"/api/orgs/{id}/members/{userId}/permissions",
                 "/api/groups/{id}/members/{userId}/permissions"})
    public Result<Void> setPermissions(@PathVariable String id, @PathVariable String userId,
                                      @Valid @RequestBody OrgDTOs.SetPermissionsRequest body) {
        orgService.setPermissions(id, userId, body.getCanWriteDictionary(), body.getCanWriteQcRules());
        operationLogger.log("权限变更", id,
                "成员 " + userId + " 词典写 " + switchFlag(body.getCanWriteDictionary())
                        + "、质控规则写 " + switchFlag(body.getCanWriteQcRules()));
        return Result.ok("权限已更新", null);
    }

    @RequireOrgRole(value = "owner", pathVar = "id")
    @PostMapping({"/api/orgs/{id}/leave", "/api/groups/{id}/leave"})
    public Result<Void> leave(@PathVariable String id) {
        orgService.leave(id);
        return Result.ok("已退出组织", null);
    }

    /** 权限开关的审计措辞：null 表示「这一位不改」，不能写成「关」 */
    private static String switchFlag(Boolean v) {
        return v == null ? "不变" : (v ? "开" : "关");
    }
}