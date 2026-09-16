package com.occuspec.service;

import com.occuspec.entity.Hazard;
import com.occuspec.mapper.HazardMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 危害因素查询服务：编码与名称互查。
 * 两级缓存（进程内 → Redis）避免高频判定反复查库，新增危害因素无需改代码。
 */
@Service
public class HazardService {
  private static final String REDIS_PREFIX = "occuspec:hazard:name:";
  private static final Duration REDIS_TTL = Duration.ofHours(12);

  private final HazardMapper hazardMapper;
  private final StringRedisTemplate redis;
  /** 进程内一级缓存：编码 → 名称。 */
  private final Map<String, String> localNameCache = new ConcurrentHashMap<>();

  public HazardService(HazardMapper hazardMapper, StringRedisTemplate redis) {
    this.hazardMapper = hazardMapper;
    this.redis = redis;
  }

  /** 编码转中文名：查不到时回退编码本身，保证查询文本非空。 */
  public String nameOf(String code) {
    if (code == null || code.isBlank()) {
      return "";
    }
    String cached = localNameCache.get(code);
    if (cached != null) {
      return cached;
    }
    try {
      String fromRedis = redis.opsForValue().get(REDIS_PREFIX + code);
      if (fromRedis != null && !fromRedis.isBlank()) {
        localNameCache.put(code, fromRedis);
        return fromRedis;
      }
    } catch (Exception ignored) {
      // Redis 不可用时直接查库
    }
    Hazard hazard = hazardMapper.selectById(code);
    String name = hazard == null || hazard.getName() == null ? code : hazard.getName();
    localNameCache.put(code, name);
    try {
      redis.opsForValue().set(REDIS_PREFIX + code, name, REDIS_TTL);
    } catch (Exception ignored) {
      // 缓存写入失败不影响主流程
    }
    return name;
  }

  /** 名称转编码：精确匹配名称或别名，供自然语言输入归一化使用。 */
  public String codeOf(String name) {
    if (name == null || name.isBlank()) {
      return "";
    }
    if (localNameCache.containsValue(name)) {
      for (Map.Entry<String, String> entry : localNameCache.entrySet()) {
        if (name.equals(entry.getValue())) {
          return entry.getKey();
        }
      }
    }
    for (Hazard hazard : hazardMapper.selectList(null)) {
      if (name.equals(hazard.getName())) {
        localNameCache.put(hazard.getCode(), hazard.getName());
        return hazard.getCode();
      }
    }
    return "";
  }

  /** 清除缓存（危害因素变更时调用）。 */
  public void evict(String code) {
    localNameCache.remove(code);
    try {
      redis.delete(REDIS_PREFIX + code);
    } catch (Exception ignored) {
      // 忽略
    }
  }
}
