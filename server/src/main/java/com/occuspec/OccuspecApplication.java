package com.occuspec;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 应用启动入口。 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.occuspec.mapper")
public class OccuspecApplication {
  public static void main(String[] args) {
    SpringApplication.run(OccuspecApplication.class, args);
  }
}
