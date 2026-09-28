package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.exception.BadCredentialsException;
import com.tcm.ehr.common.utils.JwtUtil;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.RegisterDTO;
import com.tcm.ehr.domain.po.GroupMember;
import com.tcm.ehr.domain.po.ResearchGroup;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.vo.LoginVO;
import com.tcm.ehr.mapper.GroupMemberMapper;
import com.tcm.ehr.mapper.ResearchGroupMapper;
import com.tcm.ehr.mapper.UserMapper;
import com.tcm.ehr.service.GroupResolution;
import com.tcm.ehr.service.IAuthService;
import com.tcm.ehr.service.IGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 登录 / 注册服务实现（阶段2：加入课题组维度）。
 *
 * <p>角色分两层，作用域不同，<b>不塞进同一个枚举</b>：</p>
 * <pre>
 * users.role          {管理员, 用户}          ← 系统级
 * group_members.role  {owner(组长), member(组员)} ← 组内级
 * </pre>
 * 混在一起就会出现「是某组组长」这种无法在 users 表上表达的状态。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl extends ServiceImpl<UserMapper, User> implements IAuthService {

    private final JwtUtil jwtUtil;
    private final ResearchGroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final IGroupService groupService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 管理员菜单（9 项：原 8 项 + 课题组管理）。
     * 名称与前端路由 / 侧栏一致。
     */
    private static final List<String> ADMIN_MENUS = List.of(
            "首页看板", "病历数据", "结构化解析", "质控校验", "人工复核",
            "清洗与导出", "术语词典", "日志审计", "课题组管理");

    /** 组长菜单：本组数据 + 本组成员管理 */
    private static final List<String> OWNER_MENUS = List.of(
            "首页看板", "病历数据", "结构化解析", "质控校验", "人工复核",
            "清洗与导出", "我的课题组");

    /** 组员菜单：本组数据，无成员管理 */
    private static final List<String> MEMBER_MENUS = List.of(
            "首页看板", "病历数据", "结构化解析", "质控校验", "人工复核", "清洗与导出");

    /**
     * 待分配池 / 审批中的菜单：<b>空列表</b>。
     *
     * <p>空菜单不是「没做完」，是刻意的：让前端落到引导页，说明「在等组长接收」或
     * 「申请已提交」，而不是给一个点进去全是空态的侧栏。</p>
     */
    private static final List<String> PENDING_MENUS = List.of();

    @Override
    @Transactional
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
        // 2.1 状态：带建组申请时先进 pending（审批通过才转 active）
        user.setStatus(User.STATUS_PENDING);
        user.setHasPendingGroup(createGroup != null ? 1 : 0);
        // 3. 落库（唯一索引兜底并发注册竞态）
        try {
            baseMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册竞态：两个请求同时通过上面的存在性检查，由 users.username 唯一索引兜底
            throw new IllegalArgumentException("用户名已存在");
        }

        // 4. 不带建组申请 → 只注册，账号留在待分配池等组长拉
        if (createGroup == null) {
            return false;
        }
        // 5. 带建组申请：先校验编码唯一（排除 rejected —— 被拒时编码会被改写释放）
        String code = createGroup.getCode().trim();
        Long taken = groupMapper.selectCount(new QueryWrapper<ResearchGroup>()
                .eq("code", code)
                .ne("status", ResearchGroup.REJECTED));
        if (taken != null && taken > 0) {
            throw new IllegalArgumentException("课题组编码「" + code + "」已被占用，请换一个");
        }
        // 5.1 建 pending 组（待管理员审批）
        ResearchGroup g = new ResearchGroup();
        g.setId(UUID.randomUUID().toString());
        g.setCode(code);
        g.setName(createGroup.getName().trim());
        g.setPurpose(createGroup.getPurpose() == null ? null : createGroup.getPurpose().trim());
        g.setStatus(ResearchGroup.PENDING);
        g.setAppliedBy(user.getId());
        g.setCreateTime(LocalDateTime.now().withNano(0));
        groupMapper.insert(g);
        // 5.2 申请人成为首任组长（待审批，但组内身份先立好，审批通过即可用）
        GroupMember m = new GroupMember();
        m.setId(UUID.randomUUID().toString());
        m.setGroupId(g.getId());
        m.setUserId(user.getId());
        m.setRole(GroupMember.ROLE_OWNER);
        m.setIsPrimary(1);
        m.setCreateTime(LocalDateTime.now().withNano(0));
        groupMemberMapper.insert(m);
        return true;
    }

    @Override
    public LoginVO login(String username, String password) {
        // 1. 按用户名取用户
        User user = baseMapper.findByUsername(username);
        // 不区分用户不存在与密码错误，避免泄露账号是否存在
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        // 2. 账号停用：直接拒登。停用是管理动作，语义上不该给出「密码错误」以外的模糊提示
        if (User.STATUS_DISABLED.equals(user.getStatus())) {
            throw new IllegalStateException("账号已被停用，请联系管理员");
        }

        // 3. 解析当前组（含 status='active' 过滤 —— 组被停用则查不到）
        GroupResolution g = groupService.resolvePrimaryGroup(user.getId());
        if (!g.hasGroup() && isGroupStopped(user.getId())) {
            // 3.1 查不到组有三种可能：无组（待分配池，正常）/ 组已停用（拒登）/
            //     DB 故障（降级为无组，不该在这里误判）。停用的组能从
            //     group_members 查到行但 research_groups.status != 'active'，
            //     故补一次不带 status 过滤的查询来区分。
            throw new IllegalStateException("所属课题组已被停用，请联系管理员");
        }

        // 4. 签发 JWT（组**不**进 token），并按身份下发菜单
        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole()));
        vo.setRole(user.getRole());
        vo.setGroupId(g.hasGroup() ? g.getGroupId() : "");
        vo.setGroupRole(g.hasGroup() ? g.getGroupRole() : null);
        vo.setStatus(user.getStatus());
        vo.setPendingGroup(user.getHasPendingGroup() != null && user.getHasPendingGroup() == 1);
        vo.setMenus(menusOf(user, g));
        return vo;
    }

    /**
     * 菜单四套：管理员 / 组长 / 组员 / 待分配池（空）。
     *
     * <p>管理员与其它身份的区别是「能不能管组、能不能写共用配置」，
     * <b>不是</b>「能不能看数据」—— 后者由 {@code auth.admin-can-view-data} 控制
     * （数据层 fail-closed），不在菜单层体现。</p>
     */
    private List<String> menusOf(User user, GroupResolution g) {
        if (RequestUtils.ROLE_ADMIN.equals(user.getRole())) {
            return ADMIN_MENUS;
        }
        if (!g.hasGroup()) {
            return PENDING_MENUS;
        }
        return GroupMember.ROLE_OWNER.equals(g.getGroupRole()) ? OWNER_MENUS : MEMBER_MENUS;
    }

    /**
     * 该用户是否属于一个<b>已停用</b>的课题组（组状态为 {@code stopped}）。
     *
     * <p>⚠️ 不能简化成「有成员行但解析不到 active 组」——那会把
     * <b>审批中的建组申请人</b>（组状态 {@code pending}）误判成「组已停用」而拒绝登录。
     * 申请人本就该能登录（进引导页看审批进度）。故这里显式只看
     * {@code research_groups.status = 'stopped'}。</p>
     */
    private boolean isGroupStopped(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        List<GroupMember> members = groupMemberMapper.selectList(
                new QueryWrapper<GroupMember>().eq("user_id", userId));
        for (GroupMember m : members) {
            if (m.getGroupId() == null || m.getGroupId().isBlank()) {
                continue;
            }
            ResearchGroup grp = groupMapper.selectById(m.getGroupId());
            if (grp != null && ResearchGroup.STOPPED.equals(grp.getStatus())) {
                return true;
            }
        }
        return false;
    }
}
