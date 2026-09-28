package com.tcm.ehr.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次「用户 → 组」解析的结果。
 *
 * <p>把 groupId 与组内角色打成一个对象返回，是因为两者的<b>可用性耦合</b>：
 * 查组是一次 DB 查询，要么都拿到，要么都拿不到。不存在「有 groupId 但角色未知」
 * 或「有角色但组未知」的中间态 —— 那种状态在鉴权上是无法安全处理的。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupResolution {

    /** 无组（待分配池 / 组已停用 / DB 故障降级） */
    public static final GroupResolution NONE = new GroupResolution(null, null);

    private String groupId;
    /** owner=组长 / member=组员；无组时为 null */
    private String groupRole;

    /** 是否有生效组 */
    public boolean hasGroup() {
        return groupId != null && !groupId.isBlank();
    }
}
