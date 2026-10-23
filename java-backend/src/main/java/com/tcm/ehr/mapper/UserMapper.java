package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tcm.ehr.domain.po.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户的数据访问。
 *
 * BaseMapper 已覆盖通用 CRUD，这里只补认证链路需要的按用户名精确查询。
 *
 * ⚠️ findByUsername 返回整行（含 password 哈希），调用方不得把整行直接回传给前端。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(@Param("username") String username);
}