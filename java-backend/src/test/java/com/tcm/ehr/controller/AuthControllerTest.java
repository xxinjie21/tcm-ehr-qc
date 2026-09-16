package com.tcm.ehr.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.BadCredentialsException;
import com.tcm.ehr.dto.LoginDTO;
import com.tcm.ehr.dto.RegisterDTO;
import com.tcm.ehr.service.AuthService;
import com.tcm.ehr.vo.LoginVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录接口契约测试（standalone MockMvc，不加载 Spring 容器，无需 MySQL/Redis/ES）：
 * 成功 200、凭证错误 401、参数为空 400，且错误响应为统一 Result 结构。
 */
class AuthControllerTest {

    private MockMvc mockMvc;
    private AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        authService = Mockito.mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService)).build();
    }

    private String json(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return objectMapper.writeValueAsString(dto);
    }

    private String registerJson(String username, String password, String role) throws Exception {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setRole(role);
        return objectMapper.writeValueAsString(dto);
    }

    private LoginVO adminVO() {
        LoginVO vo = new LoginVO();
        vo.setToken("fake.jwt.token");
        vo.setRole("管理员");
        vo.setMenus(List.of("首页看板", "清洗与导出"));
        return vo;
    }

    @Test
    void loginSuccess() throws Exception {
        when(authService.login("admin", "123456")).thenReturn(adminVO());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("admin", "123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("登录成功"))
                .andExpect(jsonPath("$.data.token").value("fake.jwt.token"))
                .andExpect(jsonPath("$.data.role").value("管理员"))
                .andExpect(jsonPath("$.data.menus[1]").value("清洗与导出"));
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new BadCredentialsException("用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("admin", "bad-pass")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("用户名或密码错误"));
    }

    @Test
    void blankUsernameReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("   ", "123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("用户名不能为空"));
    }

    @Test
    void blankPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("admin", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("密码不能为空"));
    }

    @Test
    void registerSuccess() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("newuser", "123456", "审核员")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("注册成功"))
                .andExpect(jsonPath("$.data.username").value("newuser"))
                .andExpect(jsonPath("$.data.role").value("审核员"));
    }

    @Test
    void registerBlankUsernameReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("", "123456", "审核员")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("用户名不能为空"));
    }

    @Test
    void registerInvalidRoleReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("newuser", "123456", "超级管理员")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("角色只能为管理员或审核员"));
    }

    @Test
    void registerDuplicateUsernameReturns400() throws Exception {
        Mockito.doThrow(new IllegalArgumentException("用户名已存在"))
                .when(authService).register("admin", "123456", "管理员");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("admin", "123456", "管理员")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("用户名已存在"));
    }
}
