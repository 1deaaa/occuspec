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
        .excludePathPatterns(
            "/auth/login",
            "/auth/logout",
            "/admin/standards/import",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/actuator/health");
  }
}
