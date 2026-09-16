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
        authService.register(dto.getUsername(), dto.getPassword(), dto.getRole());
        return Result.ok("注册成功", Map.of("username", dto.getUsername(), "role", dto.getRole()));
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
