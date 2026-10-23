package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.OrganizationMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 课题组成员 Mapper */
@Mapper
public interface OrgMemberMapper extends BaseMapper<OrganizationMember> {

    /**
     * 取某用户的主组，且该组必须处于 active 状态。
     *
     * 两个条件缺一不可，漏掉任一条都会留下真实漏洞：
     * - 漏 {@code is_primary = 1}：表上是 {@code UNIQUE(org_id,user_id)}，
     *       允许一人多组；不指定主组时取到哪组取决于 SQL 返回顺序 →
     *       同一用户不同请求可能看到不同数据。
     * - 漏 {@code g.status = 'active'}：停用组只挡了登录，
     *       organization_members 的行仍在 → 查组照常返回 orgId →
     *       旧 token 在 24h 内照样能看数据（停用形同虚设）。
     *
     * 走 {@code INDEX idx_member_user(user_id, is_primary)}，不写这个
     * {@code @Select} 的话每请求就是一次全表扫。
     *
     * 两个写授权位不能漏（can_write_dictionary / can_write_qc_rules）：
     * OrgPermissionServiceImpl 读的正是返回对象的这两个字段。漏取时它们恒为
     * {@code null}，判定退化成 {@code Integer.valueOf(1).equals(null)} = {@code false} ——
     * 表现为「库里给组员开了写权限、登录返回的却是 {@code false}」，而且不报错、只是静默失效。
     *
     * 列清单与 {@link OrganizationMember} 的字段一一对应，加字段时要同步改这里。
     *
     * @param userId 用户 ID
     * @return 主组成员行；无组 / 组已停用时返回 {@code null}
     */
    @Select("""
            SELECT m.id, m.org_id, m.user_id, m.role, m.is_primary, m.create_time,
                   m.can_write_dictionary, m.can_write_qc_rules
              FROM organization_members m
              JOIN organizations g ON g.id = m.org_id
             WHERE m.user_id = #{userId}
               AND m.is_primary = 1
               AND g.status = 'active'
             LIMIT 1
            """)
    OrganizationMember findPrimaryActive(@Param("userId") String userId);
}
