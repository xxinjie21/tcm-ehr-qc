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
     * <p><b>① 只注册</b> → 账号进「待分配池」（{@code status=pending}），
     * 看不到任何数据，等某个组长从池里拉入。
     * <b>② 同时申请建组</b> → 建 {@code pending} 组 + 本人为首任组长，
     * 等管理员审批；期间 {@code has_pending_group=1} 使其从池中隐藏，
     * 避免被别的组长先拉走。</p>
     *
     * @param dto 用户名与密码；可选 createGroup（编码/名称/用途）
     * @return username / role / groupSubmitted（是否已提交建组申请）
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
}
