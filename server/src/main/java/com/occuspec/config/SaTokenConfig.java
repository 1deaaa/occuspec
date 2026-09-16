package com.occuspec.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 鉴权拦截器：登录与文档接口放行，其余需登录。 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
        .addPathPatterns("/**")
        // 注意：context-path 已剥离，此处按控制器路径放行
        .excludePathPatterns(
            "/auth/login",
            "/auth/logout",
            "/error",
            "/admin/standards/import",
            "/admin/rag/**",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/actuator/health");
  }
}
