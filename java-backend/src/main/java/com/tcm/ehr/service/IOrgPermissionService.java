package com.tcm.ehr.service;

/**
 * 组织内写权限查询（授权位来自 {@code organization_members}）。
 *
 * <p>「词典写 / 质控规则写」两个开关是<b>成员级</b>授权：管理员与组织所有者天然拥有，
 * 不依赖这两位（给他们授权没有意义）。</p>
 */
public interface IOrgPermissionService {

    /**
     * 判断某用户在其当前组织内能否写质控规则。
     *
     * @param userId 用户 ID；空值返回 false
     * @return true = 管理员 / 所有者 / 被授权成员
     */
    boolean canWriteQcRules(String userId);

    /**
     * 判断某用户在其当前组织内能否写词典。
     *
     * @param userId 用户 ID；空值返回 false
     * @return true = 管理员 / 所有者 / 被授权成员
     */
    boolean canWriteDictionary(String userId);
}
