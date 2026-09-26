package com.tcm.ehr.common.config;

import com.tcm.ehr.common.interceptors.JwtInterceptor;
import com.tcm.ehr.common.interceptors.RoleInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC配置：注册JWT拦截器（登录接口放行）与角色拦截器
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final RoleInterceptor roleInterceptor;

    @Override
    /**
     * 注册两个拦截器：先 JWT 鉴权（401），再角色校验（403）。
     * 两者都放行登录与注册接口 —— 这两个接口不需要 token。
     *
     * @param registry 拦截器注册表
     */
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序即执行顺序：先鉴权（401），再校验角色（403）
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
    }
}
