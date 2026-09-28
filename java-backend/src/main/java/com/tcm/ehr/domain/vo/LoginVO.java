package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录响应。
 *
 * <p>组信息随登录带出仅供<b>前端渲染</b>（菜单、引导页文案）。<b>鉴权不依赖它</b> ——
 * 服务端每次请求都由 {@code JwtInterceptor} 重新解析组，客户端改 localStorage 没用。</p>
 */
@Data
public class LoginVO {

    private String token;
    private String role;
    private List<String> menus;

    /** 当前所属课题组 ID；无组（待分配池 / 组已停用）时为空串 */
    private String groupId;
    /** 组内角色：owner / member；无组时为 null */
    private String groupRole;
    /** 账号状态：pending / active / disabled */
    private String status;
    /** 是否已提交建组申请待审批（1=是） */
    private boolean pendingGroup;
}
