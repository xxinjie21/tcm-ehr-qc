package com.tcm.ehr.service;

import com.tcm.ehr.common.BadCredentialsException;
import com.tcm.ehr.entity.User;
import com.tcm.ehr.mapper.UserMapper;
import com.tcm.ehr.util.JwtUtil;
import com.tcm.ehr.vo.LoginVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 登录服务层测试（纯 Mockito + 真实 JwtUtil/BCrypt，不加载 Spring 容器）：
 * BCrypt 校验、JWT 下发与 claims、按角色返回菜单、错误凭证统一抛 BadCredentialsException。
 */
class AuthServiceTest {

    /** 与 database-init.sql 内置账号一致的 123456 的 BCrypt 哈希 */
    private static final String HASH_123456 =
            "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi";

    private UserMapper userMapper;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = Mockito.mock(UserMapper.class);
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "tcm-ehr-governance-jwt-secret-key-2026-course-design");
        ReflectionTestUtils.setField(jwtUtil, "expireHours", 24L);
        authService = new AuthService(userMapper, jwtUtil);
    }

    private User user(String id, String username, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword(HASH_123456);
        u.setRole(role);
        return u;
    }

    @Test
    void adminLoginOkAndTokenValid() {
        when(userMapper.findByUsername("admin"))
                .thenReturn(user("admin-0001", "admin", "管理员"));

        LoginVO vo = authService.login("admin", "123456");

        assertNotNull(vo.getToken());
        assertEquals("管理员", vo.getRole());
        // 管理员菜单：文档第五章/登录响应示例共 8 项，名称已由旧名“数据治理”改为“清洗与导出”
        List<String> menus = vo.getMenus();
        assertEquals(8, menus.size());
        assertTrue(menus.contains("人工复核"));
        assertTrue(menus.contains("清洗与导出"));
        assertFalse(menus.contains("数据治理"));

        Claims claims = jwtUtil.parseToken(vo.getToken());
        assertEquals("admin-0001", claims.getSubject());
        assertEquals("admin", claims.get("username"));
        assertEquals("管理员", claims.get("role"));
    }

    @Test
    void auditorLoginOk() {
        when(userMapper.findByUsername("auditor"))
                .thenReturn(user("audit-0001", "auditor", "审核员"));

        LoginVO vo = authService.login("auditor", "123456");

        assertEquals("审核员", vo.getRole());
        assertEquals(List.of("首页看板", "人工复核"), vo.getMenus());
        assertNotNull(vo.getToken());
    }

    @Test
    void wrongPasswordThrows() {
        when(userMapper.findByUsername("admin"))
                .thenReturn(user("admin-0001", "admin", "管理员"));

        assertThrows(BadCredentialsException.class,
                () -> authService.login("admin", "wrong-password"));
    }

    @Test
    void unknownUserThrowsSameError() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);

        // 与密码错误完全相同的异常，避免泄露账号是否存在
        assertThrows(BadCredentialsException.class,
                () -> authService.login("ghost", "123456"));
    }
}
