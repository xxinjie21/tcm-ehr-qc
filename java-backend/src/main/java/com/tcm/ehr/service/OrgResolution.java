package com.tcm.ehr.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次「用户 → 组」解析的结果。
 *
 * <p>把 orgId 与组内角色打成一个对象返回，是因为两者的<b>可用性耦合</b>：
 * 查组是一次 DB 查询，要么都拿到，要么都拿不到。不存在「有 orgId 但角色未知」
 * 或「有角色但组未知」的中间态 —— 那种状态在鉴权上是无法安全处理的。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgResolution {

    /** 无组（待分配池 / 组已停用 / DB 故障降级） */
    public static final OrgResolution NONE = new OrgResolution(null, null);

    private String orgId;
    /** owner=组长 / member=组员；无组时为 null */
    private String groupRole;

    /** 是否有生效组 */
    public boolean hasGroup() {
        return orgId != null && !orgId.isBlank();
    }
}
