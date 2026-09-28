package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.RegisterDTO;
import com.tcm.ehr.domain.po.User;
import com.tcm.ehr.domain.vo.LoginVO;

/**
 * 认证服务：注册与登录。
 */
public interface IAuthService extends IService<User> {

    /**
     * 注册用户（阶段2 起有两种形态）。
     *
     * @param username    用户名
     * @param password    明文密码，落库前做 BCrypt 加密
     * @param createGroup 可选的「同时申请建组」；为 {@code null} 时只注册，账号进待分配池
     * @return 是否已提交建组申请（true = 需等待管理员审批）
     * @throws IllegalArgumentException 用户名已存在，或课题组编码已被占用
     */
    boolean register(String username, String password, RegisterDTO.CreateGroup createGroup);

    /**
     * 校验凭证并签发访问令牌。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return token=JWT；role=系统级角色；menus=该身份可见的菜单；组上下文见 LoginVO
     * @throws com.tcm.ehr.common.exception.BadCredentialsException 用户名或密码错误
     * @throws IllegalStateException 账号被停用，或所属课题组被停用
     */
    LoginVO login(String username, String password);
}
