package com.occuspec.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 基础设施配置：分页插件与 API 文档信息。 */
@Configuration
public class InfraConfig {
  @Bean
  public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
  }

  @Bean
  public OpenAPI openAPI() {
    return new OpenAPI()
        .info(new Info().title("职业健康辅助判定系统").version("0.1.0").description("辅助判定工具，结论为建议性质，须经主检医师复核。"));
  }
}
