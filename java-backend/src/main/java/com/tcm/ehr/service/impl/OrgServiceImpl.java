package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.OrganizationMember;
import com.tcm.ehr.domain.po.Organization;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.domain.vo.OrgVOs;
import com.tcm.ehr.mapper.OrgMemberMapper;
import com.tcm.ehr.mapper.OrgMapper;
import com.tcm.ehr.service.OrgResolution;
import com.tcm.ehr.service.IOrgService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 组织服务实现（批次 6：自助创建、成员搜索、授权开关、归档 / 改派）。
 *
 * <p>成员管理接口全部要求 owner 且有 {@code @RequireOrgRole("owner")} 拦截器兜底，
 * 本类内再校验一次「操作者是本组织所有者、目标行存在」的交错关系，双保险防越权。</p>
 *
 * <p>一人一组织的应用层约束：一个 {@code user_id} 只允许出现在一个<b>未归档</b>组织的
 * 成员表里（结构保留 {@code is_primary} 以便将来支持多组织而无需改表）。</p>
 *
 * <p><b>防滥用</b>（自助创建放开后必须自己兜住）：创建配额 + 编码白名单 + 名称去重，
 * 见 {@link #createOrg}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgServiceImpl extends ServiceImpl<OrgMapper, Organization>
        implements IOrgService {

    private static final String DEFAULT_ORG_ID = "grp-default-2026";

    /** 防滥用 1：每人同时最多持有的 owner 组织数（不自动归档，超了就必须先归档旧的） */
    private static final int MAX_ACTIVE_ORGS_PER_USER = 3;

    /** 防滥用 2：组织编码白名单（大写字母 / 数字 / 连字符，2~50） */
    private static final java.util.regex.Pattern CODE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,49}$");

    /** 防滥用 3：成员搜索单次返回上限（配合「关键词至少 2 字符」，避免变成扫描器） */
    private static final int USER_SEARCH_LIMIT = 20;

    private final OrgMemberMapper memberMapper;

    /** 普通用户的 Mapper（查用户名等）
     * 组表通过 ServiceImpl.baseMapper 拿（extends ServiceImpl<OrgMapper, Organization>） */
    private final com.tcm.ehr.mapper.UserMapper userMapper;

    // ---------------------------------------------------------- R2 身份解析

    @Override
    public OrgResolution resolvePrimaryOrg(String userId) {
        if (userId == null || userId.isBlank() || "unknown".equals(userId)) {
            return OrgResolution.NONE;
        }
        OrganizationMember m;
        try {
            m = memberMapper.findPrimaryActive(userId);
        } catch (Exception e) {
            log.error("[课题组] 解析 user={} 的组失败，降级为无组: {}", userId, e.getMessage());
            return OrgResolution.NONE;
        }
        if (m == null || m.getOrgId() == null || m.getOrgId().isBlank()) {
            return OrgResolution.NONE;
        }
        return new OrgResolution(m.getOrgId(), m.getRole());
    }

    // ---------------------------------------------------------- 我的组织

    /**
     * GET /api/my-org：当前用户的组织上下文。
     *
     * <p>批次 6 去审核后<b>没有「审批中」这个状态</b>：无组织就是无组织，
     * 前端据此落到「我的组织」引导页（可自助创建 / 等所有者邀请）。</p>
     */
    @Override
    public OrgVOs.MyOrgVO myOrg() {
        OrgVOs.MyOrgVO vo = new OrgVOs.MyOrgVO();
        OrganizationMember m = memberMapper.findPrimaryActive(RequestUtils.currentUserId());
        if (m != null) {
            Organization g = baseMapper.selectById(m.getOrgId());
            if (g != null) {
                vo.setOrg(toOrgInfo(g, false));
                vo.setMyRole(m.getRole());
            }
        }
        return vo;
    }

    // ---------------------------------------------------------- 管理员

    @Override
    public List<OrgVOs.OrgInfo> listOrgs(String status) {
        QueryWrapper<Organization> w = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            w.eq("status", status.trim());
        }
        w.orderByDesc("create_time");
        List<OrgVOs.OrgInfo> out = new ArrayList<>();
        for (Organization g : baseMapper.selectList(w)) {
            out.add(toOrgInfo(g, true));
        }
        return out;
    }

    /**
     * 自助创建组织（批次 6）：任何登录用户可创建，创建者自动成为 owner，<b>无审核</b>。
     *
     * <p><b>放开自助创建后，这三道闸就是防滥用的全部手段</b>，少一道都可能被刷：</p>
     * <ol>
     *   <li><b>创建配额</b>：每人同时最多持有 {@value #MAX_ACTIVE_ORGS_PER_USER} 个未归档组织。
     *       没有它，一个账号可以建几百个空组织，把组织列表冲垮（管理员页是全表查询）。</li>
     *   <li><b>编码白名单</b>：{@code ^[A-Z0-9][A-Z0-9-]{1,49}$}（DTO 侧已校验，这里再兜一次）。
     *       编码会进日志、导出文件名与接口路径，放任 {@code ../} 会污染这三处。</li>
     *   <li><b>名称去重</b>：同名会让 owner 在列表里分不清自己的组织。</li>
     * </ol>
     *
     * <p>code 留空时服务端生成 {@code ORG-<8位大写>}，避免并发抢同一个自增序列。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrgVOs.OrgInfo createOrg(OrgDTOs.CreateOrgRequest body, String creatorUserId) {
        String name = body == null || body.getName() == null ? "" : body.getName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("请填写组织名称");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("组织名称最长 100 字");
        }
        String code = body == null || body.getCode() == null ? "" : body.getCode().trim().toUpperCase();
        if (!code.isEmpty() && !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("组织编码只能是大写字母、数字、连字符，长度 2~50");
        }

        // 1. 配额：同一创建者名下已有多少个未归档组织
        Long owned = memberMapper.selectCount(new QueryWrapper<OrganizationMember>()
                .eq("user_id", creatorUserId)
                .eq("role", OrganizationMember.ROLE_OWNER));
        if (owned != null && owned >= MAX_ACTIVE_ORGS_PER_USER) {
            throw new IllegalArgumentException("每人最多同时创建 "
                    + MAX_ACTIVE_ORGS_PER_USER + " 个组织，请先归档不再使用的");
        }
        // 2. 名称去重（同一创建者名下）
        if (owned != null && owned > 0) {
            List<OrganizationMember> mine = memberMapper.selectList(
                    new QueryWrapper<OrganizationMember>()
                            .eq("user_id", creatorUserId)
                            .eq("role", OrganizationMember.ROLE_OWNER));
            for (OrganizationMember m : mine) {
                Organization g = baseMapper.selectById(m.getOrgId());
                if (g != null && name.equals(g.getName())) {
                    throw new IllegalArgumentException("你名下已有同名组织「" + name + "」");
                }
            }
        }
        // 3. 编码唯一（交给唯一索引兜底并转友好文案）
        if (code.isEmpty()) {
            code = "ORG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        } else if (baseMapper.selectCount(new QueryWrapper<Organization>().eq("code", code)) > 0) {
            throw new IllegalArgumentException("组织编码「" + code + "」已被占用，请换一个");
        }

        // 4. 建组织 + 建 owner 成员行 + 建者的账号置 active —— 必须同一事务：
        //    否则会出现「有组织但没有所有者」的孤儿组织
        Organization g = new Organization();
        g.setId(UUID.randomUUID().toString());
        g.setCode(code);
        g.setName(name);
        g.setPurpose(body == null || body.getPurpose() == null ? null : body.getPurpose().trim());
        g.setStatus(Organization.ACTIVE);
        g.setOwnerUserId(creatorUserId);
        g.setCreateTime(LocalDateTime.now().withNano(0));
        try {
            baseMapper.insert(g);
        } catch (DuplicateKeyException e) {
            // 并发同编码：上面的存在性检查与插入之间有窗口，由 uk 兜底
            throw new IllegalArgumentException("组织编码「" + code + "」已被占用，请换一个");
        }

        OrganizationMember owner = new OrganizationMember();
        owner.setId(UUID.randomUUID().toString());
        owner.setOrgId(g.getId());
        owner.setUserId(creatorUserId);
        owner.setRole(OrganizationMember.ROLE_OWNER);
        // 两个授权位显式置 0：不写会因 null 被 MyBatis-Plus 跳过而依赖 DB 默认值，
        // 「新建所有者的授权状态」在代码里就读不出来，且默认值一改就静默变化
        owner.setCanWriteDictionary(0);
        owner.setCanWriteQcRules(0);
        owner.setIsPrimary(1);
        owner.setCreateTime(LocalDateTime.now().withNano(0));
        memberMapper.insert(owner);

        updateUserStatus(creatorUserId, User.STATUS_ACTIVE, false);
        log.info("[组织] 用户 {} 创建组织 {}（code={}），自动成为所有者", creatorUserId, g.getId(), code);
        return toOrgInfo(g, true);
    }

    @Override
    public void updateOrg(String orgId, OrgDTOs.UpdateGroupRequest body) {
        Organization g = requireOrg(orgId);
        if (body == null) {
            return;
        }
        if (body.getName() != null && !body.getName().isBlank()) {
            g.setName(body.getName().trim());
        }
        g.setPurpose(body.getPurpose());
        baseMapper.updateById(g);
    }

    @Override
    public void stop(String orgId) {
        Organization g = requireOrg(orgId);
        if (Organization.STOPPED.equals(g.getStatus())) {
            return;
        }
        if (Organization.ARCHIVED.equals(g.getStatus())) {
            throw new IllegalArgumentException("已归档的组织不能停用");
        }
        g.setStatus(Organization.STOPPED);
        baseMapper.updateById(g);
        // 停用只挡登录（JwtInterceptor 的主组织解析已过滤 status='active'），数据保留
    }

    @Override
    public void activate(String orgId) {
        Organization g = requireOrg(orgId);
        if (!Organization.STOPPED.equals(g.getStatus())) {
            throw new IllegalArgumentException("只有已停用的组织才能恢复");
        }
        g.setStatus(Organization.ACTIVE);
        baseMapper.updateById(g);
        // 成员状态恢复为 active（此前登录被挡就是因为查不到 active 组织）
        List<OrganizationMember> members = memberMapper.selectList(
                new QueryWrapper<OrganizationMember>().eq("org_id", g.getId()));
        for (OrganizationMember m : members) {
            updateUserStatus(m.getUserId(), User.STATUS_ACTIVE, false);
        }
    }

    /**
     * 归档（仅管理员）：前提「成员数为 0」。
     *
     * <p><b>为什么要求成员数为 0</b>：归档的语义是「这个组织已经没人用了」，
     * 若带着成员归档，这些人的账号会因为「查不到 active 组织」而登录不了，
     * 而组织列表里又看不到有未处理的人 —— 变成无声的账号丢失。</p>
     *
     * <p><b>不自动归档</b>：自动归档会误伤「沉睡但仍有效」的组织。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(String orgId, String reason) {
        Organization g = requireOrg(orgId);
        if (Organization.ARCHIVED.equals(g.getStatus())) {
            return;
        }
        if (Organization.STOPPED.equals(g.getStatus())) {
            throw new IllegalArgumentException("已停用的组织请先恢复再归档，或直接保持停用");
        }
        Long members = memberMapper.selectCount(new QueryWrapper<OrganizationMember>()
                .eq("org_id", orgId));
        if (members != null && members > 0) {
            throw new IllegalArgumentException("组织还有 " + members
                    + " 名成员，请先清空成员（或停用组织）再归档");
        }
        g.setStatus(Organization.ARCHIVED);
        baseMapper.updateById(g);
        log.info("[组织] 组织 {} 已归档：{}", orgId, reason);
    }

    /**
     * 改派所有者（仅管理员）：owner 账号丢失 / 人离职时的兜底。
     *
     * <p>没有它，唯一能让组织脱离「无人可管」状态的方式是等原 owner 回来 ——
     * 管理员只能停用。{@code applications.owner_user_id} 与成员行的 owner
     * <b>必须同事务改两处</b>，否则两者会指向不同的人。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reassignOwner(String orgId, String newOwnerUserId) {
        Organization g = requireOrg(orgId);
        if (Organization.ARCHIVED.equals(g.getStatus())) {
            throw new IllegalArgumentException("已归档的组织不能改派所有者");
        }
        User target = userMapper.selectById(newOwnerUserId);
        if (target == null) {
            throw new ResourceNotFoundException(1006, "用户不存在");
        }
        if (newOwnerUserId.equals(g.getOwnerUserId())) {
            throw new IllegalArgumentException("该用户已经是本组织所有者");
        }
        // 1. 原 owner 降为普通成员（保留在组织内，不移出）
        OrganizationMember old = memberOf(orgId, g.getOwnerUserId());
        if (old != null) {
            old.setRole(OrganizationMember.ROLE_MEMBER);
            memberMapper.updateById(old);
        }
        // 2. 新 owner：已在组织内则升权，不在则新建行
        OrganizationMember neu = memberOf(orgId, newOwnerUserId);
        if (neu == null) {
            neu = new OrganizationMember();
            neu.setId(UUID.randomUUID().toString());
            neu.setOrgId(orgId);
            neu.setUserId(newOwnerUserId);
            neu.setIsPrimary(1);
            neu.setCreateTime(LocalDateTime.now().withNano(0));
            neu.setRole(OrganizationMember.ROLE_OWNER);
            memberMapper.insert(neu);
        } else {
            neu.setRole(OrganizationMember.ROLE_OWNER);
            memberMapper.updateById(neu);
        }
        // 3. 冗余列同步（权威仍是成员行的 role）
        g.setOwnerUserId(newOwnerUserId);
        baseMapper.updateById(g);
        updateUserStatus(newOwnerUserId, User.STATUS_ACTIVE, false);
        log.info("[组织] 组织 {} 的所有者改派为 {}", orgId, newOwnerUserId);
    }

    /**
     * 按用户名搜索可拉入的候选人（登录即可）。
     *
     * <p>两道限制防止它变成枚举器 / 扫描器：关键词至少 2 个字符（单字符会撞上
     * 全站绝大多数用户名）、{@value #USER_SEARCH_LIMIT} 条封顶。</p>
     */
    @Override
    public List<OrgVOs.UserBriefVO> searchUsers(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() < 2) {
            throw new IllegalArgumentException("搜索关键词至少 2 个字符");
        }
        List<OrgVOs.UserBriefVO> out = new ArrayList<>();
        // 只回 id + username：返回角色 / 状态 / 所属组织等于开了一个「全站用户名 + 组织归属」查询
        for (User u : userMapper.selectList(new QueryWrapper<User>()
                .like("username", kw)
                .orderByAsc("username")
                .last("LIMIT " + USER_SEARCH_LIMIT))) {
            OrgVOs.UserBriefVO vo = new OrgVOs.UserBriefVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            out.add(vo);
        }
        return out;
    }

    // ---------------------------------------------------------- 所有者（本组织）

    @Override
    public List<OrgVOs.MemberInfo> members(String orgId) {
        requireOrg(orgId);
        List<OrgVOs.MemberInfo> out = new ArrayList<>();
        // owner 排前，便于前端直接看出所有者
        List<OrganizationMember> rows = memberMapper.selectList(new QueryWrapper<OrganizationMember>()
                .eq("org_id", orgId)
                .orderByDesc("role").orderByAsc("create_time"));
        for (OrganizationMember m : rows) {
            User u = userMapper.selectById(m.getUserId());
            OrgVOs.MemberInfo mi = new OrgVOs.MemberInfo();
            mi.setUserId(m.getUserId());
            mi.setUsername(u == null ? "(已注销)" : u.getUsername());
            mi.setRole(m.getRole());
            mi.setJoinTime(m.getCreateTime());
            out.add(mi);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addMember(String orgId, String userId) {
        requireOrg(orgId);
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new ResourceNotFoundException(1006, "用户不存在");
        }
        // 已有归属 → 不能拉（一人一组织）。已停用账号也不拉入：拉进来也登不了，
        // 只会让 owner 以为自己多了个人。
        if (memberMapper.selectCount(new QueryWrapper<OrganizationMember>()
                .eq("user_id", userId)) > 0) {
            throw new IllegalArgumentException("该用户已属于其他组织，无法重复拉入");
        }
        if (User.STATUS_DISABLED.equals(u.getStatus())) {
            throw new IllegalArgumentException("该账号已被停用，无法拉入");
        }
        OrganizationMember m = new OrganizationMember();
        m.setId(UUID.randomUUID().toString());
        m.setOrgId(orgId);
        m.setUserId(userId);
        m.setRole(OrganizationMember.ROLE_MEMBER);
        m.setIsPrimary(1);
        m.setCreateTime(LocalDateTime.now().withNano(0));
        // uk_org_user 唯一索引兜底并发拉人：上面的存在性检查与插入之间有窗口
        // （两个所有者同时拉同一人都会看到「无归属」）。不捕获的话异常直冒成 500，
        // 用户只会看到「系统异常」而不知道「该用户已被拉走」。
        try {
            memberMapper.insert(m);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该用户已在其他组织中，请刷新后重试");
        }
        updateUserStatus(userId, User.STATUS_ACTIVE, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(String orgId, String userId) {
        requireOrg(orgId);
        OrganizationMember m = memberOf(orgId, userId);
        if (OrganizationMember.ROLE_OWNER.equals(m.getRole())) {
            throw new IllegalArgumentException("不能直接移除所有者，请先转让所有者");
        }
        memberMapper.deleteById(m.getId());
        updateUserStatus(userId, User.STATUS_PENDING, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferOwner(String orgId, String newOwnerUserId) {
        requireOrg(orgId);
        OrganizationMember newOwner = memberOf(orgId, newOwnerUserId);
        // 原组长降为组员：两行必须同生共死（加了 @Transactional）
        List<OrganizationMember> owners = memberMapper.selectList(new QueryWrapper<OrganizationMember>()
                .eq("org_id", orgId).eq("role", OrganizationMember.ROLE_OWNER));
        if (owners.isEmpty()) {
            throw new IllegalStateException("本组织没有所有者，数据异常");
        }
        OrganizationMember oldOwner = owners.get(0);
        if (oldOwner.getUserId().equals(newOwnerUserId)) {
            return; // 已经是组长
        }
        oldOwner.setRole(OrganizationMember.ROLE_MEMBER);
        memberMapper.updateById(oldOwner);
        newOwner.setRole(OrganizationMember.ROLE_OWNER);
        memberMapper.updateById(newOwner);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void leave(String orgId) {
        requireOrg(orgId);
        String userId = RequestUtils.currentUserId();
        OrganizationMember m = memberOf(orgId, userId);
        if (OrganizationMember.ROLE_OWNER.equals(m.getRole())) {
            // 组长不能直接退出：先数还有几个组员，没有继任者就拒绝（不产生孤儿组）
            long others = memberMapper.selectCount(new QueryWrapper<OrganizationMember>()
                    .eq("org_id", orgId)
                    .ne("user_id", userId));
            if (others == 0) {
                throw new IllegalArgumentException("你是组长且组内无其他成员，无法退出");
            }
            // 有其他人：按「队长离职须指定继任者」处理 —— 直接从剩余成员里选最早的升组长
            OrganizationMember successor = memberMapper.selectList(new QueryWrapper<OrganizationMember>()
                            .eq("org_id", orgId)
                            .ne("user_id", userId)
                            .orderByAsc("create_time")
                            .last("LIMIT 1"))
                    .get(0);
            successor.setRole(OrganizationMember.ROLE_OWNER);
            memberMapper.updateById(successor);
        }
        memberMapper.deleteById(m.getId());
        updateUserStatus(userId, User.STATUS_PENDING, false);
    }

    /**
     * 授予 / 回收成员的两个写开关（批次 6）。
     *
     * <p><b>两个开关独立</b>：owner 可以只给「词典写」或只给「质控规则写」。
     * 参数为 {@code Boolean} 且<b>可空</b> —— null 表示「这一位不改」，
     * 否则前端只提交其中一个开关就会把另一个误清零。</p>
     *
     * <p>不能给 owner 自己授权：owner 本来就能写，授权位只对 member 有意义。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setPermissions(String orgId, String userId,
                              Boolean canWriteDictionary, Boolean canWriteQcRules) {
        requireOrg(orgId);
        OrganizationMember m = memberOf(orgId, userId);
        if (OrganizationMember.ROLE_OWNER.equals(m.getRole())) {
            throw new IllegalArgumentException("所有者本就拥有全部写权限，无需单独授权");
        }
        // 两个都为空 = 什么都没改，直接返回，避免一次无意义的写库
        if (canWriteDictionary == null && canWriteQcRules == null) {
            throw new IllegalArgumentException("未指定要修改的权限");
        }
        if (canWriteDictionary != null) {
            m.setCanWriteDictionary(canWriteDictionary ? 1 : 0);
        }
        if (canWriteQcRules != null) {
            m.setCanWriteQcRules(canWriteQcRules ? 1 : 0);
        }
        memberMapper.updateById(m);
    }

    // ---------------------------------------------------------- 辅助

    private Organization requireOrg(String orgId) {
        Organization g = baseMapper.selectById(orgId);
        if (g == null) {
            throw new ResourceNotFoundException(1006, "组织不存在");
        }
        return g;
    }

    private OrganizationMember memberOf(String orgId, String userId) {
        OrganizationMember m = memberMapper.selectOne(new QueryWrapper<OrganizationMember>()
                .eq("org_id", orgId).eq("user_id", userId)
                .last("LIMIT 1"));
        if (m == null) {
            throw new IllegalArgumentException("该用户不在此组织");
        }
        return m;
    }

    /** 组装组概要；withOwner=false 时跳过组长的二次查询（我的组只有一组，没必要） */
    private OrgVOs.OrgInfo toOrgInfo(Organization g, boolean withOwner) {
        OrgVOs.OrgInfo info = new OrgVOs.OrgInfo();
        info.setId(g.getId());
        info.setCode(g.getCode());
        info.setName(g.getName());
        info.setStatus(g.getStatus());
        info.setCreateTime(g.getCreateTime());
        info.setMemberCount((int) (long) memberMapper.selectCount(
                new QueryWrapper<OrganizationMember>().eq("org_id", g.getId())));
        if (withOwner) {
            List<OrganizationMember> owners = memberMapper.selectList(new QueryWrapper<OrganizationMember>()
                    .eq("org_id", g.getId()).eq("role", OrganizationMember.ROLE_OWNER)
                    .last("LIMIT 1"));
            if (!owners.isEmpty()) {
                User owner = userMapper.selectById(owners.get(0).getUserId());
                info.setOwnerName(owner == null ? "" : owner.getUsername());
            }
        }
        return info;
    }

    private void updateUserStatus(String userId, String status, boolean pendingGroupFlag) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        User u = userMapper.selectById(userId);
        if (u == null) {
            return;
        }
        u.setStatus(status);
        if (pendingGroupFlag) {
            u.setHasPendingGroup(1);
        } else {
            u.setHasPendingGroup(0);
        }
        userMapper.updateById(u);
    }
}