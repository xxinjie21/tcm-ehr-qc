package com.tcm.ehr.service;

import com.tcm.ehr.common.BadCredentialsException;
import com.tcm.ehr.entity.User;
import com.tcm.ehr.mapper.UserMapper;
import com.tcm.ehr.vo.LoginVO;
import com.tcm.ehr.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 管理员菜单（功能设计文档第五章 + 登录响应示例，共 8 项，名称与前端路由一致） */
    private static final List<String> ADMIN_MENUS = List.of(
            "首页看板", "病历数据", "结构化解析", "质控校验", "人工复核",
            "清洗与导出", "术语词典", "日志审计");

    /** 审核员菜单：首页看板（登录落地页）+ 人工复核 */
    private static final List<String> AUDITOR_MENUS = List.of("首页看板", "人工复核");

    public LoginVO login(String username, String password) {
        User user = userMapper.findByUsername(username);
        // 不区分用户不存在与密码错误，避免泄露账号是否存在
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole()));
        vo.setRole(user.getRole());
        vo.setMenus("管理员".equals(user.getRole()) ? ADMIN_MENUS : AUDITOR_MENUS);
        return vo;
    }
}
