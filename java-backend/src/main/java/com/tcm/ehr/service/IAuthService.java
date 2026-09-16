package com.tcm.ehr.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.vo.LoginVO;

/**
 * 登录/注册服务
 */
public interface IAuthService extends IService<User> {

    /**
     * 用户注册：用户名查重、密码 BCrypt 加密入库
     *
     * @throws IllegalArgumentException 用户名已存在
     */
    void register(String username, String password, String role);

    /**
     * 用户登录：校验账号密码，返回 JWT、角色与按角色分配的菜单
     *
     * @throws com.tcm.ehr.common.exception.BadCredentialsException 用户名或密码错误
     */
    LoginVO login(String username, String password);
}
