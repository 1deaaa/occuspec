package com.occuspec.common;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 热点数据 cache-aside 助手：进程内一级 + Redis 二级，统一处理三类缓存问题。
 *
 * <ul>
 *   <li>穿透：空结果以短 TTL 空值占位缓存，避免不存在的键每次都打到数据库；</li>
 *   <li>击穿：同一 key 并发回源时单飞（single-flight），仅一个线程查库，其余复用结果；</li>
 *   <li>雪崩：TTL 附加随机抖动，避免同批 key 同时过期引发回源高峰。</li>
 * </ul>
 *
 * 仅缓存字符串，序列化交给调用方，保持简单与可控。
 */
@Component
public class HotCache {
  private static final Logger log = LoggerFactory.getLogger(HotCache.class);
  /** 空值占位符：表示"查过、确实不存在"，区别于"缓存未命中"。 */
  private static final String NULL_SENTINEL = "\u0000NULL\u0000";
  private static final Duration NULL_TTL = Duration.ofSeconds(30);

  private final StringRedisTemplate redis;
  /** 一级缓存：key → 值（值可能为空占位符）。 */
  private final ConcurrentHashMap<String, String> local = new ConcurrentHashMap<>();
  /** 单飞表：key → 正在回源的 future。 */
  private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

  public HotCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * 读取缓存，未命中时回源并写入。
   *
   * @param key 缓存键（需含业务前缀）
   * @param ttl 二级缓存有效期（会附加随机抖动）
   * @param loader 回源函数，返回 null 视为不存在
   * @return 缓存或回源结果，可能为 null
   */
  public String get(String key, Duration ttl, Supplier<String> loader) {
    String cached = local.get(key);
    if (cached != null) {
      return NULL_SENTINEL.equals(cached) ? null : cached;
    }
    try {
      String fromRedis = redis.opsForValue().get(key);
      if (fromRedis != null) {
        local.put(key, fromRedis);
        return NULL_SENTINEL.equals(fromRedis) ? null : fromRedis;
      }
    } catch (Exception ex) {
      log.debug("缓存读取降级 key={} err={}", key, ex.getMessage());
    }
    // 单飞：同 key 并发只放行一个回源，避免击穿
    CompletableFuture<String> mine = new CompletableFuture<>();
    CompletableFuture<String> existing = inFlight.putIfAbsent(key, mine);
    if (existing != null) {
      try {
        return existing.join();
      } catch (Exception ex) {
        // 单飞失败则降级为直接回源，保证可用
        return loader.get();
      }
    }
    try {
      String value = loader.get();
      String stored = value == null ? NULL_SENTINEL : value;
      local.put(key, stored);
      try {
        redis.opsForValue().set(key, stored, jitter(ttl));
      } catch (Exception ex) {
        log.debug("缓存写入降级 key={} err={}", key, ex.getMessage());
      }
      mine.complete(value);
      return value;
    } catch (Exception ex) {
      mine.completeExceptionally(ex);
      throw ex;
    } finally {
      inFlight.remove(key);
    }
  }

  /** 主动失效指定键（数据变更时调用）。 */
  public void evict(String key) {
    local.remove(key);
    try {
      redis.delete(key);
    } catch (Exception ex) {
      log.debug("缓存失效降级 key={} err={}", key, ex.getMessage());
    }
  }

  /** 清空一级缓存（用于测试或全量重建）。 */
  public void clearLocal() {
    local.clear();
  }

  /** TTL 抖动：在 ±20% 区间随机，避免同批键同时过期。 */
  private Duration jitter(Duration ttl) {
    long base = Math.max(1, ttl.getSeconds());
    long delta = Math.max(1, base / 5);
    return Duration.ofSeconds(base + ThreadLocalRandom.current().nextLong(-delta, delta + 1));
  }

  /** 空值占位符，供调用方判断。 */
  public static boolean isNullSentinel(String value) {
    return NULL_SENTINEL.equals(value);
  }
}
