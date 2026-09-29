package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课题组成员。对应 {@code group_members} 表。
 *
 * <p>组内角色刻意与 {@code users.role} 分开：后者是系统级（管理员/用户），
 * 前者是组内级（组长/组员），作用域不同，混在一张表上就无法表达「既是组长又是组员」。
 *
 * <p>{@code isPrimary} 当前恒为 1（一人一组由应用层约束），但<b>必须带上</b>：
 * 查组 SQL 少了它，多组场景下取到哪组取决于 SQL 返回顺序，
 * 会让同一用户在不同请求看到不同数据。</p>
 */
@Data
@TableName("organization_members")
public class OrganizationMember {

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_MEMBER = "member";

    private String id;
    private String orgId;
    private String userId;
    /** owner=组长 / member=组员 */
    private String role;
    /** 主组标记，预留一人多组 */
    private Integer isPrimary;
    private LocalDateTime createTime;
}
