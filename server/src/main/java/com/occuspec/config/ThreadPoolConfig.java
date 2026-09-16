package com.occuspec.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 线程池配置。
 * 判定任务为 IO 密集（等待模型与数据库），核心数按 CPU 核数 2 倍附近取值；
 * 批量任务与判定隔离，避免相互挤占。
 */
@Configuration
public class ThreadPoolConfig {
  @Bean("assessExecutor")
  public Executor assessExecutor(
      @Value("${occuspec.thread-pool.assess.core:4}") int core,
      @Value("${occuspec.thread-pool.assess.max:8}") int max,
      @Value("${occuspec.thread-pool.assess.queue:200}") int queue) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(core);
    executor.setMaxPoolSize(max);
    executor.setQueueCapacity(queue);
    executor.setThreadNamePrefix("assess-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.initialize();
    return executor;
  }

  @Bean("batchExecutor")
  public Executor batchExecutor(
      @Value("${occuspec.thread-pool.batch.core:2}") int core,
      @Value("${occuspec.thread-pool.batch.max:4}") int max,
      @Value("${occuspec.thread-pool.batch.queue:100}") int queue) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(core);
    executor.setMaxPoolSize(max);
    executor.setQueueCapacity(queue);
    executor.setThreadNamePrefix("batch-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.initialize();
    return executor;
  }
}
