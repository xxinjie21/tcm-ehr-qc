package com.tcm.ehr.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus配置：注册分页插件（分页查询用 MP 插件实现）
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    /**
     * 注册分页内部拦截器：分页查询靠它改写 SQL（DbType 固定 MYSQL）。
     *
     * @return MyBatis-Plus 拦截器链
     */
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 1. 只挂分页插件；DbType 必须显式给 MySQL，否则多数据源场景会猜错方言
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}