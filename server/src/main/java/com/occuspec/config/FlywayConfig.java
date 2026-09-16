package com.occuspec.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 双库迁移配置：MySQL 业务表与 PG 向量表各自版本化演进。
 * 本地默认直连本机服务，不依赖容器。
 */
@Configuration
public class FlywayConfig {
  @Bean(initMethod = "migrate")
  @ConditionalOnProperty(name = "occuspec.flyway.mysql-enabled", havingValue = "true", matchIfMissing = true)
  public Flyway mysqlFlyway(@Qualifier("mysqlDataSource") DataSource mysqlDataSource) {
    return Flyway.configure()
        .dataSource(mysqlDataSource)
        .locations("classpath:db/migration/mysql")
        .table("flyway_schema_history")
        .baselineOnMigrate(true)
        .load();
  }

  @Bean(initMethod = "migrate")
  @ConditionalOnProperty(name = "occuspec.flyway.pg-enabled", havingValue = "true", matchIfMissing = true)
  public Flyway pgFlyway(@Qualifier("pgDataSource") DataSource pgDataSource) {
    return Flyway.configure()
        .dataSource(pgDataSource)
        .locations("classpath:db/migration/postgresql")
        .table("flyway_schema_history")
        .baselineOnMigrate(true)
        .load();
  }
}
