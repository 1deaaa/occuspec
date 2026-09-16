package com.occuspec.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 双数据源配置：MySQL 为主业务库，PostgreSQL 专供向量检索。
 * 双库 Flyway 各自管理迁移目录，互不干扰。
 */
@Configuration
public class DataSourceConfig {
  @Primary
  @Bean("mysqlDataSource")
  public DataSource mysqlDataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String username,
      @Value("${spring.datasource.password}") String password,
      @Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}") String driver) {
    DriverManagerDataSource ds = new DriverManagerDataSource(url, username, password);
    ds.setDriverClassName(driver);
    return ds;
  }

  @Bean("pgDataSource")
  public DataSource pgDataSource(
      @Value("${occuspec.pg.host:127.0.0.1}") String host,
      @Value("${occuspec.pg.port:5432}") int port,
      @Value("${occuspec.pg.db:occuspec}") String db,
      @Value("${occuspec.pg.user:postgres}") String user,
      @Value("${occuspec.pg.password:1009}") String password) {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.postgresql.Driver");
    ds.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + db);
    ds.setUsername(user);
    ds.setPassword(password);
    return ds;
  }

  @Bean("pgJdbcTemplate")
  public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource pgDataSource) {
    return new JdbcTemplate(pgDataSource);
  }
}
