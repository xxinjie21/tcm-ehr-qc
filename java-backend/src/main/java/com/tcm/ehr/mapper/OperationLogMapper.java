package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.OperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    /**
     * 库中实际出现过的操作类型。
     *
     * <p>§七 L7：普通用户的下拉不能显示别人的操作类型（轻度信息泄漏 —— 能从
     * 「存在 X 操作」推断出系统里有人干过那件事）。过滤口径与 {@code buildWrapper}
     * 保持同一套三档：管理员全部 / 组长本组 / 组员与无组仅自己。
     * 传 {@code orgId=null} 表示不按组过滤（管理员）。</p>
     *
     * @param orgId 所属组；null = 不按组过滤（仅管理员路径）
     * @param operator 操作人；null = 不过滤（管理员 / 组长）
     */
    @Select("""
            <script>
            SELECT DISTINCT action FROM operation_log
            WHERE action IS NOT NULL
            <if test="orgId != null and orgId != ''">
              AND org_id = #{orgId}
            </if>
            <if test="operator != null and operator != ''">
              AND operator = #{operator}
            </if>
            ORDER BY action
            </script>
            """)
    List<String> selectDistinctActions(@Param("orgId") String orgId,
                                       @Param("operator") String operator);
}
