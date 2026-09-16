package com.occuspec.service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 分布式锁：Redis SETNX + 过期时间防死锁，批量任务防重复执行用。
 * 单机场景同样使用该锁，保持语义统一；锁粒度为任务级。
 */
@Service
public class DistributedLock {
  private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);
  private final StringRedisTemplate redis;

  public DistributedLock(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * 加锁执行，拿不到锁直接返回空。
   *
   * @param key 锁键
   * @param expireSeconds 过期秒数
   * @param task 临界区任务
   * @return 任务返回值，拿不到锁返回 null
   */
  public <T> T tryRun(String key, long expireSeconds, Supplier<T> task) {
    String token = UUID.randomUUID().toString();
    Boolean locked = false;
    try {
      locked = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(expireSeconds));
    } catch (Exception ex) {
      log.warn("分布式锁降级：Redis 不可用，直接执行 key={}", key);
      return task.get();
    }
    if (!Boolean.TRUE.equals(locked)) {
      return null;
    }
    try {
      return task.get();
    } finally {
      try {
        // 仅释放自己持有的锁
        String current = redis.opsForValue().get(key);
        if (token.equals(current)) {
          redis.delete(key);
        }
      } catch (Exception ex) {
        log.warn("分布式锁释放失败 key={}", key);
      }
    }
  }
}
