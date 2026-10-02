package com.tcm.ehr.controller;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.exception.BadCredentialsException;
import com.tcm.ehr.common.exception.GlobalExceptionHandler;
import com.tcm.ehr.domain.dto.LoginDTO;
import com.tcm.ehr.domain.dto.RegisterDTO;
import com.tcm.ehr.domain.vo.LoginVO;
import com.tcm.ehr.service.IAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录 / 注册接口契约测试（standalone MockMvc，不加载 Spring 容器，无需 MySQL/Redis/ES）：
 * 登录成功 200、凭证错误 401、参数为空 400；
 * 注册成功固定为「用户」、参数为空 400、用户名重复 400、伪造 role 被忽略；
 * 以及阶段 2 的两种形态：只注册（进待分配池）与同时申请建组（待审批）。
 */
class AuthControllerTest {

    private MockMvc mockMvc;
    private IAuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        authService = Mockito.mock(IAuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                // 注册全局异常处理器：异常统一返回 Result（401/400）
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String json(String username, String password) throws Exception {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return objectMapper.writeValueAsString(dto);
    }

    private String registerJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
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
                .andExpect(jsonPath("$.msg").value("请输入用户名"));
    }

    @Test
    void blankPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("admin", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请输入密码"));
    }

    @Test
    void registerSuccessAlwaysUser() throws Exception {
        // 只注册（不带 createGroup）→ 进待分配池
        Mockito.when(authService.register(Mockito.eq("newuser"), Mockito.eq("123456"), Mockito.isNull()))
                .thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("newuser", "123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("注册成功，请等待课题组接收"))
                .andExpect(jsonPath("$.data.username").value("newuser"))
                // 注册账号角色固定为「用户」（防开两种角色的冗余）
                .andExpect(jsonPath("$.data.role").value("用户"))
                .andExpect(jsonPath("$.data.groupSubmitted").value("false"));
    }

    /** 带 createGroup 注册 → 提交建组申请，待管理员审批 */
    @Test
    void registerWithCreateGroupSubmitsApplication() throws Exception {
        RegisterDTO.CreateGroup cg = new RegisterDTO.CreateGroup();
        cg.setCode("NEURO-2026");
        cg.setName("神经组");
        Mockito.when(authService.register(Mockito.eq("newuser"), Mockito.eq("123456"), Mockito.any()))
                .thenReturn(true);

        String body = objectMapper.writeValueAsString(Map.of(
                "username", "newuser", "password", "123456", "createGroup", cg));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("课题组申请已提交，等待管理员审批"))
                .andExpect(jsonPath("$.data.groupSubmitted").value("true"));
    }

    /** createGroup.code 必填：仅选择「同时建组」就必须给编码 */
    @Test
    void registerWithBlankGroupCodeReturns400() throws Exception {
        RegisterDTO.CreateGroup cg = new RegisterDTO.CreateGroup();
        cg.setCode("   ");
        cg.setName("神经组");
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "newuser", "password", "123456", "createGroup", cg));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("请填写课题组编码"));
    }

    @Test
    void registerForgedAdminRoleIsIgnored() throws Exception {
        // 请求体即使伪造 role=管理员，后端也必须忽略并固定注册为「用户」
        String forged = objectMapper.writeValueAsString(
                Map.of("username", "hacker", "password", "123456", "role", "管理员"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("用户"));
        // 服务层收到的是 username/password/createGroup，无 role 入参，无法越权指定管理员
        Mockito.verify(authService).register(Mockito.eq("hacker"), Mockito.eq("123456"), Mockito.isNull());
    }

    @Test
    void registerBlankUsernameReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("   ", "123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请输入用户名"));
    }

    /**
     * 空字符串用户名：只应报一条。
     * 若长度约束单用 @Size，会与 @NotBlank 同时触发，用户拿到两条重复提示。
     */
    @Test
    void registerEmptyUsernameReportsSingleError() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("", "123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请输入用户名"));
    }

    /** 长度约束：2~20 字符（长度写在正则可避免与 @NotBlank 重复报错） */
    @Test
    void registerTooShortUsernameReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("a", "123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("用户名须为 2~20 位字母、数字、下划线或中文"));
    }

    /** 字符集约束：仅字母/数字/下划线/中文 */
    @Test
    void registerIllegalCharUsernameReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("admin@1", "123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("用户名须为 2~20 位字母、数字、下划线或中文"));
    }

    /** 中文用户名合法（字符集含 \u4e00-\u9fa5） */
    @Test
    void registerChineseUsernameReturns200() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("张三", "123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void registerDuplicateUsernameReturns400() throws Exception {
        Mockito.doThrow(new IllegalArgumentException("用户名已存在"))
                .when(authService).register(Mockito.eq("admin"), Mockito.eq("123456"), Mockito.isNull());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("admin", "123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("用户名已存在"));
    }
}
