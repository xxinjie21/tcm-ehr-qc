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

    /**
     * 退出登录：作废该用户全部已签发的令牌。
     *
     * <p>JWT 无状态，只清前端 localStorage 的话，那张令牌在有效期内仍能继续用
     * （复制到别的浏览器照样能调接口）。这里把令牌版本 +1，令牌里的 {@code ver}
     * 对不上即失效。</p>
     *
     * <p>刻意作废<b>全部</b>而不是「仅当前这一张」：用户点退出登录的预期是
     * 「这台设备别再是登录态」，而令牌是按用户签发的，做不到只废一张。</p>
     *
     * @param userId 当前用户 ID
     */
    void logout(String userId);
}
