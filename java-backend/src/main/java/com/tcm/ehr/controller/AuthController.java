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
     * 注册账号。
     *
     * <p>角色由后端固定为审核员，不采信前端传入的角色；用户名重复或参数非法返回 400。</p>
     *
     * @param dto 用户名与密码，字段级校验由 @Valid 触发
     * @return username=注册的用户名；role=固定「审核员」
     */
    @PostMapping("/register")
    public Result<Map<String, String>> register(@Valid @RequestBody RegisterDTO dto) {
        authService.register(dto.getUsername(), dto.getPassword());
        return Result.ok("注册成功", Map.of("username", dto.getUsername(), "role", "审核员"));
    }

    /**
     * 登录并下发访问凭证。
     *
     * <p>用户名或密码错误返回 401，由 GlobalExceptionHandler 统一转换。</p>
     *
     * @param dto 用户名与密码
     * @return token=JWT；role=角色；menus=该角色可见的菜单标题
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok("登录成功", authService.login(dto.getUsername(), dto.getPassword()));
    }
}
