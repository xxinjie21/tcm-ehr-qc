package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.vo.LoginVO;

/**
 * 认证服务：注册与登录。
 */
public interface IAuthService extends IService<User> {

    /**
     * 注册用户，角色固定为审核员。
     *
     * @param username 用户名
     * @param password 明文密码，落库前做 BCrypt 加密
     * @throws IllegalArgumentException 用户名已存在
     */
    void register(String username, String password);

    /**
     * 校验凭证并签发访问令牌。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return token=JWT；role=角色；menus=该角色可见的菜单
     * @throws com.tcm.ehr.common.exception.BadCredentialsException 用户名或密码错误
     */
    LoginVO login(String username, String password);
}
