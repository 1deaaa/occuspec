package com.occuspec.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 分布式锁：Redis SETNX + 过期时间防死锁，批量任务防重复执行用。
 *
 * <p>释放锁用 Lua 脚本保证"比对持有者 + 删除"原子执行：
 * 若拆成 get 后 delete 两步，锁可能在这两步之间过期并被他人获取，导致误删他人锁。
 */
@Service
public class DistributedLock {
  private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);
  /** 仅当值等于自身 token 时才删除，避免误删他人锁。 */
  private static final String UNLOCK_SCRIPT =
      "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

  private final StringRedisTemplate redis;
  private final DefaultRedisScript<Long> unlockScript;

  public DistributedLock(StringRedisTemplate redis) {
    this.redis = redis;
    this.unlockScript = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
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
    Boolean locked;
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
        // 原子比对并删除，防止误删已被他人重新获取的锁
        redis.execute(unlockScript, List.of(key), token);
      } catch (Exception ex) {
        log.warn("分布式锁释放失败 key={}", key);
      }
    }
  }
}
