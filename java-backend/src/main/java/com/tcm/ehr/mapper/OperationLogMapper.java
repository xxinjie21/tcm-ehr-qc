package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.OperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    /**
     * 库中实际出现过的操作类型（供前端筛选下拉动态取值，避免与后端调用点漂移 —— UX-19）。
     */
    @Select("SELECT DISTINCT action FROM operation_log WHERE action IS NOT NULL ORDER BY action")
    List<String> selectDistinctActions();
}
