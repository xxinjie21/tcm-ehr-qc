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
 * 这两个接口在 WebMvcConfig 里被显式放行（无需 token），其余接口一律先过 JWT 鉴权。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IAuthService authService;

    /**
     * 用户注册：用户名/密码/角色（管理员、审核员）。
     * 成功：HTTP 200 + code=200；
     * 参数非法 / 用户名已存在：由 GlobalExceptionHandler 统一返回 HTTP 400 + code=400。
     */
    @PostMapping("/register")
    public Result<Map<String, String>> register(@Valid @RequestBody RegisterDTO dto) {
        // 注册角色后端固定为「审核员」，不接受前端传入的角色（伪传 role 也会被忽略）
        authService.register(dto.getUsername(), dto.getPassword());
        return Result.ok("注册成功", Map.of("username", dto.getUsername(), "role", "审核员"));
    }

    /**
     * 用户登录（必做1）。
     * 成功：HTTP 200 + code=200，下发 JWT、角色与按角色分配的菜单；
     * 参数非法：HTTP 400 + code=400；用户名或密码错误：HTTP 401 + code=401（均由 GlobalExceptionHandler 统一处理）。
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok("登录成功", authService.login(dto.getUsername(), dto.getPassword()));
    }
}
