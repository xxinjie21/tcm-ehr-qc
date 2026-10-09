package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.LoginDTO;
import com.tcm.ehr.domain.dto.RegisterDTO;
import com.tcm.ehr.domain.vo.LoginVO;
import com.tcm.ehr.service.IAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口：注册与登录。
 *
 * <p>这两个接口在 WebMvcConfig 中显式放行，其余接口一律先过 JWT 鉴权。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IAuthService authService;

    /**
     * 注册账号（阶段2 起有两种形态）。
     *
     * <p>角色由后端固定为「用户」，不采信前端传入的角色；用户名重复、参数非法、
     * 课题组编码被占用时返回 400。</p>
     *
     * <p><b>① 只注册</b> → 账号一律 {@code active}，无组织时登录后进入「我的组织」引导页。
     * <b>② 同时创建组织</b> → 创建者即首任所有者，组织直接生效（批次 6 起取消审核，
     * 已无 {@code pending} 中间态，也不再使用 has_pending_group 标记）。</p>
     *
     * @param dto 用户名与密码；可选 createGroup（编码/名称/用途）
     * @return username / role / groupSubmitted（是否已创建组织）
     */
    @PostMapping("/register")
    public Result<Map<String, String>> register(@Valid @RequestBody RegisterDTO dto) {
        boolean applied = authService.register(dto.getUsername(), dto.getPassword(), dto.getCreateGroup());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("username", dto.getUsername());
        data.put("role", "用户");
        data.put("groupSubmitted", String.valueOf(applied));
        return Result.ok(applied ? "课题组申请已提交，等待管理员审批" : "注册成功，请等待课题组接收", data);
    }

    /**
     * 登录并下发访问凭证。
     *
     * <p>用户名或密码错误返回 401；账号被停用（{@code status=disabled}）
     * 或所属课题组被停用（{@code status=stopped}）同样拒绝，
     * 由 GlobalExceptionHandler 统一转换。</p>
     *
     * <p>菜单四套：管理员 / 组长 / 组员 / 待分配池（空菜单 + 前端引导页）。</p>
     *
     * @param dto 用户名与密码
     * @return token=JWT；role=系统级角色；menus=可见菜单；orgId/groupRole/status=当前组上下文
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok("登录成功", authService.login(dto.getUsername(), dto.getPassword()));
    }

    /**
     * 退出登录：作废当前用户全部已签发的令牌。
     *
     * <p>【权限：登录即可】需要有效 token —— 挂在 {@code /api/auth} 下但
     * <b>不</b>加入拦截器白名单：白名单里的接口拿不到当前用户，只能凭参数操作，
     * 登出必须以「你是谁」为前提。</p>
     *
     * <p>作废的是<b>该用户的所有</b>令牌（含其它设备上的）：令牌按用户签发，
     * 做不到只废这一张，而这正是「退出登录」该有的效果。</p>
     *
     * @return 固定成功（令牌已失效后前端即可清本地登录态）
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(com.tcm.ehr.common.utils.RequestUtils.currentUserId());
        return Result.ok("已退出登录", null);
    }
}
