package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.exception.BadCredentialsException;
import com.tcm.ehr.common.utils.JwtUtil;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.vo.LoginVO;
import com.tcm.ehr.mapper.UserMapper;
import com.tcm.ehr.service.IAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录/注册服务实现
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl extends ServiceImpl<UserMapper, User> implements IAuthService {

    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 角色常量：管理员 / 审核员 */
    private static final String ROLE_ADMIN = "管理员";
    private static final String ROLE_AUDITOR = "审核员";

    /**
     * 管理员菜单（功能设计文档第五章 + 登录响应示例，共 8 项，名称与前端路由一致）
     */
    private static final List<String> ADMIN_MENUS = List.of(
            "首页看板", "病历数据", "结构化解析", "质控校验", "人工复核",
            "清洗与导出", "术语词典", "日志审计");

    /** 审核员菜单：首页看板（登录落地页）+ 人工复核 */
    private static final List<String> AUDITOR_MENUS = List.of("首页看板", "人工复核");

    @Override
    public void register(String username, String password) {
        if (baseMapper.findByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        // 安全约束：注册账号角色固定为审核员，不开放管理员注册
        user.setRole(ROLE_AUDITOR);
        try {
            baseMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册竞态：两个请求同时通过上面的存在性检查，靠 users.username 唯一索引兜底
            throw new IllegalArgumentException("用户名已存在");
        }
    }

    @Override
    public LoginVO login(String username, String password) {
        User user = baseMapper.findByUsername(username);
        // 不区分用户不存在与密码错误，避免泄露账号是否存在
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole()));
        vo.setRole(user.getRole());
        vo.setMenus(ROLE_ADMIN.equals(user.getRole()) ? ADMIN_MENUS : AUDITOR_MENUS);
        return vo;
    }
}
