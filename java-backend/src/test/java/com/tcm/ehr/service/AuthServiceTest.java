package com.tcm.ehr.service;

import com.tcm.ehr.common.exception.BadCredentialsException;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.exception.UnauthorizedException;
import com.tcm.ehr.common.utils.JwtUtil;
import com.tcm.ehr.domain.po.OrganizationMember;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.vo.LoginVO;
import com.tcm.ehr.domain.vo.MenuNode;
import com.tcm.ehr.mapper.OrgMemberMapper;
import com.tcm.ehr.mapper.OrgMapper;
import com.tcm.ehr.mapper.UserMapper;
import com.tcm.ehr.service.impl.AuthServiceImpl;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
    private OrgMapper groupMapper;
    private OrgMemberMapper groupMemberMapper;
    private IOrgService groupService;
    private JwtUtil jwtUtil;
    private AuthServiceImpl authService;
    private com.tcm.ehr.service.IOrgPermissionService orgPermission;

    @BeforeEach
    void setUp() {
        userMapper = Mockito.mock(UserMapper.class);
        groupMapper = Mockito.mock(OrgMapper.class);
        groupMemberMapper = Mockito.mock(OrgMemberMapper.class);
        groupService = Mockito.mock(IOrgService.class);
        // JwtUtil / AuthServiceImpl 都要 Redis（令牌版本 + 登录失败计数，批次 15）
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(valueOps);
        jwtUtil = new JwtUtil(redis);
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "tcm-ehr-qc-jwt-secret-key-2026-course-design");
        ReflectionTestUtils.setField(jwtUtil, "expireHours", 24L);
        orgPermission = org.mockito.Mockito.mock(
                com.tcm.ehr.service.IOrgPermissionService.class);
        authService = new AuthServiceImpl(jwtUtil, groupMapper, groupMemberMapper, groupService,
                orgPermission, redis);
        // 登录失败计数读 Redis：默认「无记录」（未锁定）
        Mockito.when(valueOps.get(Mockito.anyString())).thenReturn(null);
        ReflectionTestUtils.setField(authService, "loginMaxFail", 5);
        ReflectionTestUtils.setField(authService, "loginLockMinutes", 15);
        // ServiceImpl 的 baseMapper 由 Spring 注入，测试中手动设置
        ReflectionTestUtils.setField(authService, "baseMapper", userMapper);
        // 默认解析为无组（待分配池）；需要有组的用例再显式覆盖
        resolvesToNoGroup();
        Mockito.when(groupMemberMapper.selectCount(Mockito.any())).thenReturn(0L);
    }

    private User user(String id, String username, String role) {
        return user(id, username, role, User.STATUS_ACTIVE);
    }

    /** 可指定账号状态：停用用例需要 status=disabled 走到 401 分支 */
    private User user(String id, String username, String role, String status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword(HASH_123456);
        u.setRole(role);
        // 阶段 2：默认给「有生效组」，否则否免注册用例会被当成待分配池
        u.setStatus(status);
        u.setHasPendingGroup(0);
        return u;
    }

    /** 把当前测试的“解析结果”设为无组（默认） */
    private void resolvesToNoGroup() {
        Mockito.when(groupService.resolvePrimaryOrg(Mockito.anyString()))
                .thenReturn(OrgResolution.NONE);
    }

    /** 设为已属于某组（owner / member） */
    private void resolvesToGroup(String orgId, String groupRole) {
        Mockito.when(groupService.resolvePrimaryOrg(Mockito.anyString()))
                .thenReturn(new OrgResolution(orgId, groupRole));
    }

    @Test
    void adminLoginOkAndTokenValid() {
        when(userMapper.findByUsername("admin"))
                .thenReturn(user("admin-0001", "admin", "管理员"));
        resolvesToGroup("grp-default-2026", "member");

        LoginVO vo = authService.login("admin", "123456");

        assertNotNull(vo.getToken());
        assertEquals("管理员", vo.getRole());
        // 阶段2：管理员菜单 = 原 8 项 + 「组织管理」= 9 项
        // （注：计划 §八 的侧栏图同时列了「组织管理」与「我的组织」，但那条写的是
        //  管理员 8 项 → 9 项；管理员从「我的组织」页看的就是自己的组织信息，
        //  与 Orgs 页的组织列表是同一诉求，故只加 1 项以对上计划的数量）
        List<MenuNode> menus = vo.getMenus();
        assertEquals(10, menus.size(), menus.toString());
        assertTrue(titlesOf(menus).contains("组织管理"));
        assertTrue(titlesOf(menus).contains("人工复核"));
        assertTrue(titlesOf(menus).contains("清洗与导出"));
        assertFalse(titlesOf(menus).contains("数据清洗"));
        // 批次 24 新增：标准化质量报告只读，登录即可，三档菜单都该有
        assertTrue(titlesOf(menus).contains("标准化质量报告"), menus.toString());
        // 管理员额外能看到「术语词典」的子项「术语批量导入」
        assertTrue(childTitlesOf(menus, "术语词典").contains("术语批量导入"),
                menus.toString());

        Claims claims = jwtUtil.parseToken(vo.getToken());
        assertEquals("admin-0001", claims.getSubject());
        assertEquals("admin", claims.get("username"));
        assertEquals("管理员", claims.get("role"));
    }

    @Test
    void legacyAuditorRoleLoginOk() {
        when(userMapper.findByUsername("auditor"))
                .thenReturn(user("audit-0001", "auditor", "审核员"));

        LoginVO vo = authService.login("auditor", "123456");

        // 存量「审核员」账号的 role 字段原样回显（不做数据迁移改名），
        // 但它已不是独立权限维度：菜单按组织内角色给，此处无组织 → 空菜单
        assertEquals(List.of(), vo.getMenus());
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

    @Test
    void registerInsertsEncryptedPassword() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        AtomicReference<User> savedRef = new AtomicReference<>();
        Mockito.doAnswer(inv -> {
            savedRef.set(inv.getArgument(0, User.class));
            return 1;
        }).when(userMapper).insert(Mockito.any(User.class));

        authService.register("newuser", "123456", null);

        User saved = savedRef.get();
        assertNotNull(saved);
        assertEquals("newuser", saved.getUsername());
        // 批次 6：注册角色固定为「用户」；取消审核后账号一律 active
        assertEquals("用户", saved.getRole());
        assertEquals(User.STATUS_ACTIVE, saved.getStatus());
        assertEquals(0, saved.getHasPendingGroup());
        // 密码必须 BCrypt 加密存储，且与明文匹配
        assertFalse(saved.getPassword().equals("123456"));
        assertTrue(new BCryptPasswordEncoder().matches("123456", saved.getPassword()));
    }

    @Test
    void registerDuplicateThrows() {
        when(userMapper.findByUsername("admin"))
                .thenReturn(user("admin-0001", "admin", "管理员"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> authService.register("admin", "123456", null));
        assertEquals("用户名已存在", ex.getMessage());
        // 查重失败时不应插入任何数据
        Mockito.verify(userMapper, Mockito.never()).insert(Mockito.any(User.class));
    }

    /**
     * 组织不是 active（归档 / 无生效组织）时仍能登录，落引导页。
     *
     * <p>正确语义：只有组织状态为 {@code stopped} 才拒登（403）。
     * 批次 6 去审核后不再有 pending 状态，本用例改用 archived 覆盖同一分支。</p>
     */
    @Test
    void nonActiveOrgStillAllowsLogin() {
        User xxj = user("u-xxj", "XXJ", "用户");
        when(userMapper.findByUsername("XXJ")).thenReturn(xxj);
        // 组织不是 active（这里用 archived）→ 解析不到生效组织
        resolvesToNoGroup();
        OrganizationMember m = new OrganizationMember();
        m.setOrgId("grp-xxj");
        m.setUserId("u-xxj");
        m.setRole(OrganizationMember.ROLE_MEMBER);
        when(groupMemberMapper.selectList(Mockito.any())).thenReturn(List.of(m));
        Organization g = new Organization();
        g.setId("grp-xxj");
        g.setStatus(Organization.ARCHIVED);
        when(groupMapper.selectById("grp-xxj")).thenReturn(g);

        LoginVO vo = authService.login("XXJ", "123456");

        // 只有 status=stopped 才拒登（403）；归档/无组织一律放行，落引导页
        assertNotNull(vo.getToken(), "非 stopped 的组织成员应能登录");
        assertEquals(List.of(), vo.getMenus(), "无生效组织 → 空菜单（前端引导页）");
    }

    /** 组织确实被停用（status=stopped）时才拒登；语义是「无权限」→ 403 而非 401 */
    @Test
    void stoppedGroupRejectsLogin() {
        when(userMapper.findByUsername("zhangsan")).thenReturn(user("u-zs", "zhangsan", "用户"));
        resolvesToNoGroup();
        OrganizationMember m = new OrganizationMember();
        m.setOrgId("grp-stopped");
        m.setUserId("u-zs");
        m.setRole(OrganizationMember.ROLE_MEMBER);
        when(groupMemberMapper.selectList(Mockito.any())).thenReturn(List.of(m));
        Organization g = new Organization();
        g.setId("grp-stopped");
        g.setStatus(Organization.STOPPED);
        when(groupMapper.selectById("grp-stopped")).thenReturn(g);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> authService.login("zhangsan", "123456"));
        assertTrue(ex.getMessage().contains("停用"), ex.getMessage());
    }

    /** 账号被停用：凭证不可用 → 401（与「组织停用」的 403 区分开） */
    @Test
    void disabledAccountRejectsLoginWith401() {
        when(userMapper.findByUsername("zhangsan"))
                .thenReturn(user("u-zs", "zhangsan", "用户", User.STATUS_DISABLED));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> authService.login("zhangsan", "123456"));
        assertTrue(ex.getMessage().contains("账号已被停用"), ex.getMessage());
    }

    /** 所有者菜单：本组数据 + 术语词典/日志审计 + 我的组织 */
    @Test
    void ownerMenuIncludesSharedReadOnlyAndMyGroup() {
        when(userMapper.findByUsername("zu")).thenReturn(user("u-zu", "zu", "用户"));
        resolvesToGroup("grp-a", "owner");

        LoginVO vo = authService.login("zu", "123456");

        assertTrue(titlesOf(vo.getMenus()).contains("术语词典"), vo.getMenus().toString());
        assertTrue(titlesOf(vo.getMenus()).contains("日志审计"), vo.getMenus().toString());
        assertTrue(titlesOf(vo.getMenus()).contains("我的组织"), vo.getMenus().toString());
    }

    /** 成员菜单：同样可用术语词典/日志审计，但无成员管理页 */
    @Test
    void memberMenuHasDictionaryAndLogsButNotMyGroup() {
        when(userMapper.findByUsername("yu")).thenReturn(user("u-yu", "yu", "用户"));
        resolvesToGroup("grp-a", "member");

        LoginVO vo = authService.login("yu", "123456");

        assertTrue(titlesOf(vo.getMenus()).contains("术语词典"), vo.getMenus().toString());
        assertTrue(titlesOf(vo.getMenus()).contains("日志审计"), vo.getMenus().toString());
        assertFalse(titlesOf(vo.getMenus()).contains("我的组织"), vo.getMenus().toString());
        // 「术语批量导入」对所有身份可见：普通成员走它会**导入本机个人词典**
        // （POST /dictionary/parse 只解析、不落库），不写小组基线，
        // 所以不破坏「成员不能直接改基线、必须走提案审核」的约定。
        assertTrue(childTitlesOf(vo.getMenus(), "术语词典").contains("术语批量导入"),
                "成员也该看到「术语批量导入」入口（导入本地，不直写基线）: " + vo.getMenus());
    }

    /** 取菜单树的顶层标题集合（批次17：menus 由平铺字符串列表升级为树） */
    private static Set<String> titlesOf(List<MenuNode> menus) {
        Set<String> out = new LinkedHashSet<>();
        for (MenuNode m : menus) {
            out.add(m.getTitle());
        }
        return out;
    }

    /** 取某个父菜单下的子项标题集合；父菜单不存在时返回空集 */
    private static Set<String> childTitlesOf(List<MenuNode> menus, String parentTitle) {
        for (MenuNode m : menus) {
            if (parentTitle.equals(m.getTitle())) {
                Set<String> out = new LinkedHashSet<>();
                for (MenuNode c : m.getChildren()) {
                    out.add(c.getTitle());
                }
                return out;
            }
        }
        return Set.of();
    }
}
