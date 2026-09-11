package com.tcm.ehr.service;

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

    /** 管理员菜单 */
    private static final List<String> ADMIN_MENUS = List.of(
            "首页看板", "病历数据", "结构化解析", "质控校验", "数据治理", "术语词典", "日志审计");

    /** 审核员菜单 */
    private static final List<String> AUDITOR_MENUS = List.of("首页看板", "人工复核");

    public LoginVO login(String username, String password) {
        User user = userMapper.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }
        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole()));
        vo.setRole(user.getRole());
        vo.setMenus("管理员".equals(user.getRole()) ? ADMIN_MENUS : AUDITOR_MENUS);
        return vo;
    }
}
