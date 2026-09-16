package com.tcm.ehr.controller;

import com.tcm.ehr.common.BadCredentialsException;
import com.tcm.ehr.common.Result;
import com.tcm.ehr.dto.LoginDTO;
import com.tcm.ehr.dto.RegisterDTO;
import com.tcm.ehr.service.AuthService;
import com.tcm.ehr.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户注册：用户名/密码/角色（管理员、审核员）。
     * 成功：HTTP 200 + code=200；
     * 参数非法（用户名/密码/角色为空或角色非法）：HTTP 400 + code=400；
     * 用户名已存在：HTTP 400 + code=400。
     */
    @PostMapping("/register")
    public ResponseEntity<Result<Map<String, String>>> register(@Valid @RequestBody RegisterDTO dto,
                                                                BindingResult bindingResult) {
        // 参数校验失败：返回统一错误体（不使用 Spring 默认 400 错误页）
        if (bindingResult.hasErrors()) {
            String msg = bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .findFirst()
                    .orElse("请求参数非法");
            return ResponseEntity.badRequest().body(Result.error(400, msg));
        }
        try {
            authService.register(dto.getUsername(), dto.getPassword(), dto.getRole());
            return ResponseEntity.ok(Result.ok("注册成功",
                    Map.of("username", dto.getUsername(), "role", dto.getRole())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    /**
     * 用户登录（必做1）。
     * 成功：HTTP 200 + code=200，下发 JWT、角色与按角色分配的菜单；
     * 参数非法（用户名/密码为空）：HTTP 400 + code=400；
     * 用户名或密码错误：HTTP 401 + code=401。
     */
    @PostMapping("/login")
    public ResponseEntity<Result<LoginVO>> login(@Valid @RequestBody LoginDTO dto,
                                                 BindingResult bindingResult) {
        // 参数校验失败：返回统一错误体（不使用 Spring 默认 400 错误页）
        if (bindingResult.hasErrors()) {
            String msg = bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .findFirst()
                    .orElse("请求参数非法");
            return ResponseEntity.badRequest().body(Result.error(400, msg));
        }
        try {
            LoginVO vo = authService.login(dto.getUsername(), dto.getPassword());
            return ResponseEntity.ok(Result.ok("登录成功", vo));
        } catch (BadCredentialsException e) {
            // 用户名不存在或密码错误，统一返回 401（见 openapi /api/auth/login 失败示例）
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, e.getMessage()));
        }
    }
}
