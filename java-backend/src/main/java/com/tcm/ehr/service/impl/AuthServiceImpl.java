package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.exception.BadCredentialsException;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.exception.UnauthorizedException;
import com.tcm.ehr.common.utils.JwtUtil;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.domain.dto.RegisterDTO;
import com.tcm.ehr.domain.po.OrganizationMember;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.vo.LoginVO;
import com.tcm.ehr.domain.vo.MenuNode;
import com.tcm.ehr.mapper.OrgMemberMapper;
import com.tcm.ehr.mapper.OrgMapper;
import com.tcm.ehr.mapper.UserMapper;
import com.tcm.ehr.service.OrgResolution;
import com.tcm.ehr.service.IAuthService;
import com.tcm.ehr.service.IOrgPermissionService;
import com.tcm.ehr.service.IOrgService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录 / 注册服务实现（阶段2：加入课题组维度）。
 *
 * <p>角色分两层，作用域不同，<b>不塞进同一个枚举</b>：</p>
 * <pre>
 * users.role          {管理员, 用户}          ← 系统级
 * organization_members.role  {owner(组长), member(组员)} ← 组内级
 * </pre>
 * 混在一起就会出现「是某组组长」这种无法在 users 表上表达的状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl extends ServiceImpl<UserMapper, User> implements IAuthService {

    private final JwtUtil jwtUtil;
    private final OrgMapper groupMapper;
    private final OrgMemberMapper groupMemberMapper;
    private final IOrgService orgService;
    private final IOrgPermissionService orgPermission;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /** 登录失败计数的 Redis key 前缀（批次 15） */
    private static final String LOGIN_FAIL_KEY = "tcm:auth:fail:";

    /** 连续失败达到该次数即锁定 */
    @Value("${auth.login-max-fail:5}")
    private int loginMaxFail;

    /** 锁定时长（分钟） */
    @Value("${auth.login-lock-minutes:15}")
    private int loginLockMinutes;

    /**
     * 菜单树（批次 17 从平铺字符串列表升级为带 children 的结构）。
     *
     * <p><b>为什么升级</b>：「术语批量导入」是「术语词典」的子项，平铺列表表达不了父子关系。
     * 名称与前端 MainLayout 的 ALL_MENUS[].title 对应。</p>
     *
     * <p>「术语词典」父项<b>所有人可见</b>（它是日常高频入口）；其子项
     * 「术语批量导入」<b>仅管理员</b>可见 —— 后端 {@code POST /dictionary/import}
     * 是 {@code @RequireRole("管理员")}，父项可点但不展开的语义也在这里一并确定。</p>
     */
    private static List<MenuNode> menus(boolean withDictImport, String... titles) {
        List<MenuNode> out = new ArrayList<>();
        for (String t : titles) {
            if (DICT_TITLE.equals(t) && withDictImport) {
                // 只有管理员能看到「术语批量导入」子项：后端 POST /dictionary/import
                // 是 @RequireRole("管理员")，给组长露出入口只会让人点了撞 403。
                out.add(new MenuNode(DICT_TITLE, "/dictionary",
                        List.of(new MenuNode(DICT_IMPORT_TITLE, "/dictionary/import"))));
            } else {
                out.add(new MenuNode(t, pathOf(t)));
            }
        }
        return out;
    }

    private static final String DICT_TITLE = "术语词典";
    private static final String DICT_IMPORT_TITLE = "术语批量导入";

    /** 菜单标题 → 前端路由路径；与 router/index.js 的 meta.title 一一对应 */
    private static String pathOf(String title) {
        return switch (title) {
            case "首页看板" -> "/dashboard";
            case "病历数据" -> "/records";
            case "结构化解析" -> "/nlp-extract";
            case "质控校验" -> "/qc-check";
            case "人工复核" -> "/review";
            case "清洗与导出" -> "/governance";
            case "日志审计" -> "/audit-log";
            case "组织管理" -> "/orgs";
            case "我的组织" -> "/my-org";
            default -> "/dashboard";
        };
    }

    /**
     * 管理员菜单（含「术语词典」及其子项「术语批量导入」）。
     */
    private static final List<MenuNode> ADMIN_MENUS = menus(true,
            "首页看板", "病历数据", "结构化解析", "质控校验", "人工复核",
            "清洗与导出", DICT_TITLE, "日志审计", "组织管理");

    /**
     * 所有者菜单：本组数据 + 本组成员管理 + 共用只读（术语词典/日志审计）。
     *
     * <p>术语词典的读取与日志审计均为「登录即可」（后者按组织三档可见，见 §七 L7），
     * 故所有者/成员也能用；<b>「术语批量导入」子项不在这里</b> —— 它是管理员特权，
     * 后端 import 同样是 @RequireRole("管理员")，前端露出入口只会让组长点了撞 403。</p>
     */
    private static final List<MenuNode> OWNER_MENUS = menus(false,
            "首页看板", "病历数据", "结构化解析", "质控校验", "人工复核",
            "清洗与导出", DICT_TITLE, "日志审计", "我的组织");

    /** 成员菜单：本组数据 + 共用只读（术语词典/日志审计），无成员管理 */
    private static final List<MenuNode> MEMBER_MENUS = menus(false,
            "首页看板", "病历数据", "结构化解析", "质控校验", "人工复核",
            "清洗与导出", DICT_TITLE, "日志审计");

    /**
     * 待分配池 / 审批中的菜单：<b>空列表</b>。
     *
     * <p>空菜单不是「没做完」，是刻意的：让前端落到引导页，说明「在等组长接收」或
     * 「申请已提交」，而不是给一个点进去全是空态的侧栏。</p>
     */
    private static final List<MenuNode> PENDING_MENUS = List.of();

    @Override
    // rollbackFor 必写：本方法连写 users + organizations + organization_members 三张表，
    // 默认只回滚 RuntimeException，任何受检异常都会留下「用户已建、组织没建」的半成品。
    // 口径与 RecordServiceImpl / ReviewServiceImpl / QcServiceImpl.processOne 一致。
    @Transactional(rollbackFor = Exception.class)
    public boolean register(String username, String password, RegisterDTO.CreateGroup createGroup) {
        // 1. 用户名重复直接拒绝
        if (baseMapper.findByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        // 2. 组装用户：密码 BCrypt 加密后落库，不存明文；角色固定「用户」，不开放管理员注册
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(RequestUtils.ROLE_USER);
        // 2.1 状态：批次 6 取消审核后，账号一律 active。
        //     「有组织才能登录」的旧口径依赖 pending 中间态，现在组织可自助创建、
        //     无组织用户登录后落到「我的组织」引导页，所以不需要中间态。
        user.setStatus(User.STATUS_ACTIVE);
        user.setHasPendingGroup(0);
        // 3. 落库（唯一索引兜底并发注册竞态）
        try {
            baseMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册竞态：两个请求同时通过上面的存在性检查，由 users.username 唯一索引兜底
            throw new IllegalArgumentException("用户名已存在");
        }

        // 4. 勾了「同时创建组织」→ 直接建生效组织，创建者即所有者。
        //    ⚠️ 刻意复用 orgService.createOrg 而不是在这里再写一遍：配额、编码白名单、
        //    名称去重这三道防滥用闸只有一份实现，写两份必然漂移。
        if (createGroup == null) {
            return false;
        }
        OrgDTOs.CreateOrgRequest req = new OrgDTOs.CreateOrgRequest();
        req.setName(createGroup.getName());
        req.setCode(createGroup.getCode());
        req.setPurpose(createGroup.getPurpose());
        orgService.createOrg(req, user.getId());
        return true;
    }

    @Override
    public LoginVO login(String username, String password) {
        // 0. 暴力破解锁定（批次 15）：达到阈值后直接拒，不去比对密码 —— 否则
        //    锁定只是「提示变了」，爆破请求照样打到 BCrypt 上（BCrypt 故意慢，
        //    这正是爆破的瓶颈，挡在这里才有意义）。
        int locked = lockRemainingSeconds(username);
        if (locked > 0) {
            throw new BadCredentialsException("登录失败次数过多，账号已锁定，请 " + (locked / 60) + " 分钟后重试");
        }
        // 1. 按用户名取用户
        User user = baseMapper.findByUsername(username);
        // 不区分用户不存在与密码错误，避免泄露账号是否存在
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            recordLoginFailure(username);
            throw new BadCredentialsException("用户名或密码错误");
        }
        // 1.1 登录成功：清零失败计数
        clearLoginFailure(username);
        // 2. 账号停用：直接拒登（401，与「密码错误」同类但语义不同：
        //    前端据此清登录态引导重新登录，而不是弹「权限不足」）。
        //    停用是管理动作，不该给出「密码错误」那种模糊提示。
        if (User.STATUS_DISABLED.equals(user.getStatus())) {
            throw new UnauthorizedException("账号已被停用，请联系管理员");
        }

        // 3. 解析当前组织（含 status='active' 过滤 —— 组织被停用则查不到）
        OrgResolution g = orgService.resolvePrimaryOrg(user.getId());
        if (!g.hasGroup() && isOrgStopped(user.getId())) {
            // 3.1 查不到组织有三种可能：无组织（待加入用户，正常）/ 组织被停用（拒登）/
            //     DB 故障（降级为无组织，不该在这里误判）。停用的组织能从
            //     organization_members 查到行但 organizations.status != 'active'，
            //     故补一次不带 status 过滤的查询来区分。
            throw new ForbiddenException("所属组织已被停用，请联系管理员");
        }

        // 4. 签发 JWT（组织**不**进 token），并按身份下发菜单
        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole()));
        // 用户名随登录下发：页面右上角要显示「这是谁」。只给 role 的话，
        // 两个管理员在页面上长得一模一样，操作出问题分不清是谁做的。
        vo.setUsername(user.getUsername());
        vo.setRole(user.getRole());
        vo.setOrgId(g.hasGroup() ? g.getOrgId() : "");
        vo.setOrgRole(g.hasGroup() ? g.getGroupRole() : null);
        vo.setStatus(user.getStatus());
        vo.setPendingGroup(user.getHasPendingGroup() != null && user.getHasPendingGroup() == 1);
        // 词典 / 质控规则写授权位：**实时查库**，不用登录时的快照。
        // 原来这里写死 false（注释说「批次 4 的 DDL 落地前恒 false」），但 DDL 早已落地、
        // organization_members 上的两列也早已在用 —— 写死导致：
        //   ① 前端 canWriteDictionaryEntry 永远退化成「管理员或所有者」，成员授权功能形同失效；
        //   ② 与 QcController 的实时判定不一致：后端放行、前端却不显示入口。
        // 与 QcController:241 走同一个 OrgPermissionService，口径只此一处。
        vo.setCanWriteDictionary(orgPermission.canWriteDictionary(user.getId()));
        vo.setCanWriteQcRules(orgPermission.canWriteQcRules(user.getId()));
        vo.setMenus(menusOf(user, g));
        return vo;
    }

    /**
     * 菜单四套：管理员 / 所有者 / 成员 / 待分配池（空）。
     *
     * <p>管理员与其它身份的区别是「能不能管组织、能不能写配置」，
     * <b>不是</b>「能不能看数据」—— 后者由 {@code auth.admin-can-view-data} 控制
     * （数据层 fail-closed），不在菜单层体现。</p>
     */
    private List<MenuNode> menusOf(User user, OrgResolution g) {
        if (RequestUtils.ROLE_ADMIN.equals(user.getRole())) {
            return ADMIN_MENUS;
        }
        if (!g.hasGroup()) {
            return PENDING_MENUS;
        }
        return OrganizationMember.ROLE_OWNER.equals(g.getGroupRole()) ? OWNER_MENUS : MEMBER_MENUS;
    }

    /**
     * 该用户是否属于一个<b>已停用</b>的组织（状态为 {@code stopped}）。
     *
     * <p>⚠️ 不能简化成「有成员行但解析不到 active 组」——那会把
     * <b>审批中的建组申请人</b>（状态 {@code pending}）误判成「组织已停用」而拒绝登录。
     * 申请人本就该能登录（进引导页看审批进度）。故这里显式只看
     * {@code organizations.status = 'stopped'}。</p>
     */
    private boolean isOrgStopped(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        List<OrganizationMember> members = groupMemberMapper.selectList(
                new QueryWrapper<OrganizationMember>().eq("user_id", userId));
        for (OrganizationMember m : members) {
            if (m.getOrgId() == null || m.getOrgId().isBlank()) {
                continue;
            }
            Organization grp = groupMapper.selectById(m.getOrgId());
            if (grp != null && Organization.STOPPED.equals(grp.getStatus())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 登出

    @Override
    public void logout(String userId) {
        // 令牌版本 +1：该用户此前签发的所有令牌立即失效（含其它设备上的）
        jwtUtil.revokeAll(userId);
    }

    // ------------------------------------------------------- 登录失败锁定

    /**
     * 记录一次登录失败；达到阈值即进入锁定。
     *
     * <p>用 Redis INCR + 首次设置过期时间：进程内计数在多实例下会各算各的，
     * 挡不住并发爆破；Redis 是共享的。</p>
     */
    private void recordLoginFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            String key = LOGIN_FAIL_KEY + username;
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) {
                // 首次失败才设过期：窗口从第一次失败开始滚，而不是每次失败都续命
                redis.expire(key, loginLockMinutes, java.util.concurrent.TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            // Redis 不可用：不阻断登录。降级为「无锁定」，可用性优先。
            log.warn("[Auth] 记录登录失败计数失败 username={}: {}", username, e.getMessage());
        }
    }

    /** 登录成功：清零失败计数 */
    private void clearLoginFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        try {
            redis.delete(LOGIN_FAIL_KEY + username);
        } catch (Exception e) {
            log.warn("[Auth] 清零登录失败计数失败 username={}: {}", username, e.getMessage());
        }
    }

    /**
     * 剩余锁定秒数；未锁定返回 0。
     *
     * <p>Redis 不可用返回 0（不锁定）：与全站其它降级一致 —— 宁可少一层防护，
     * 也不能让一次 Redis 抖动把所有人挡在门外。</p>
     */
    private int lockRemainingSeconds(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        try {
            String key = LOGIN_FAIL_KEY + username;
            String v = redis.opsForValue().get(key);
            if (v == null) {
                return 0;
            }
            if (Integer.parseInt(v) < loginMaxFail) {
                return 0;
            }
            Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
            // getExpire 返回 -1=无过期、-2=key 不在；两种都按「锁到窗口结束」处理，
            // 否则 Redis 里残留的计数会让账号被无限期锁死。
            if (ttl == null || ttl < 0) {
                return loginLockMinutes * 60;
            }
            return ttl.intValue();
        } catch (Exception e) {
            return 0;
        }
    }
}
