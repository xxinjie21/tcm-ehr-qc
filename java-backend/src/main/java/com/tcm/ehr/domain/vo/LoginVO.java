package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录响应。
 *
 * <p>组织信息随登录带出仅供<b>前端渲染</b>（菜单、引导页文案）。<b>鉴权不依赖它</b> ——
 * 服务端每次请求都由 {@code JwtInterceptor} 重新解析组织，客户端改 localStorage 没用。</p>
 */
@Data
public class LoginVO {

    private String token;
    /**
     * 用户名。
     *
     * <p>页面右上角要显示「这是谁」，而不是显示角色 —— 两个管理员在页面上长得一样，
     * 出问题时分不清是谁操作的。角色只作次要徽标。</p>
     */
    private String username;
    private String role;
    private List<String> menus;

    /** 当前所属组织 ID；无组织（待分配池 / 组织已停用）时为空串 */
    private String orgId;
    /** 组织内角色：owner / member；无组织时为 null */
    private String orgRole;
    /** 账号状态：pending / active / disabled */
    private String status;
    /** 是否已提交建组申请待审批（1=是） */
    private boolean pendingGroup;
    /** 词典写授权位（成员级；管理员/所有者不依赖此位） */
    private boolean canWriteDictionary;
    /** 质控规则写授权位（成员级；管理员/所有者不依赖此位） */
    private boolean canWriteQcRules;
}
