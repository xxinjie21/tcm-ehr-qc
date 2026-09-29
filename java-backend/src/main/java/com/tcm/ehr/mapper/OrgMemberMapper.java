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
     * 取某用户的<b>主组</b>，且该组必须处于 {@code active} 状态。
     *
     * <p>两个条件缺一不可，漏掉任一条都会留下真实漏洞：</p>
     * <ul>
     *   <li>漏 {@code is_primary = 1}：表上是 {@code UNIQUE(group_id,user_id)}，
     *       <b>允许一人多组</b>；不指定主组时取到哪组取决于 SQL 返回顺序 →
     *       同一用户不同请求可能看到不同数据。</li>
     *   <li>漏 {@code g.status = 'active'}：停用组只挡了登录，
     *       {@code group_members} 的行仍在 → 查组照常返回 orgId →
     *       <b>旧 token 在 24h 内照样能看数据</b>（停用形同虚设）。</li>
     * </ul>
     *
     * <p>走 {@code INDEX idx_member_user(user_id, is_primary)}，不写这个
     * {@code @Select} 的话每请求就是一次全表扫。</p>
     *
     * @param userId 用户 ID
     * @return 主组成员行；无组 / 组已停用时返回 {@code null}
     */
    @Select("""
            SELECT m.id, m.group_id, m.user_id, m.role, m.is_primary, m.create_time
              FROM group_members m
              JOIN research_groups g ON g.id = m.group_id
             WHERE m.user_id = #{userId}
               AND m.is_primary = 1
               AND g.status = 'active'
             LIMIT 1
            """)
    OrganizationMember findPrimaryActive(@Param("userId") String userId);
}
