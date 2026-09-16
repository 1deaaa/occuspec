package com.occuspec.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 幂等切面：Redis 原子占位，相同键相同请求回放结果，不同请求体直接拒绝。
 * Redis 不可用时降级放行，保证可用性优先。
 */
@Aspect
@Component
public class IdempotencyAspect {
  private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
  public static final String HEADER = "Idempotency-Key";
  private static final String PREFIX = "occuspec:idem:";
  private static final String STATUS_DONE = "DONE:";
  private static final String STATUS_LOCK = "LOCK:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  public IdempotencyAspect(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  @Around("@annotation(idempotency)")
  public Object around(ProceedingJoinPoint joinPoint, Idempotency idempotency) throws Throwable {
    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return joinPoint.proceed();
    }
    HttpServletRequest request = attrs.getRequest();
    String key = request.getHeader(HEADER);
    if (key == null || key.isBlank()) {
      return joinPoint.proceed();
    }
    String reqHash = hashArgs(joinPoint.getArgs());
    String redisKey = PREFIX + key;
    try {
      String existing = redis.opsForValue().get(redisKey);
      if (existing != null) {
        if (existing.startsWith(STATUS_DONE)) {
          Stored stored = objectMapper.readValue(existing.substring(STATUS_DONE.length()), Stored.class);
          if (!stored.reqHash().equals(reqHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENT_KEY_MISMATCH);
          }
          return objectMapper.readValue(stored.body(), Object.class);
        }
        // 占位锁仍在：并发重复提交，提示稍后查询
        throw new BusinessException(ErrorCode.IDEMPOTENT_REPLAY);
      }
      Boolean locked =
          redis.opsForValue().setIfAbsent(redisKey, STATUS_LOCK + reqHash, Duration.ofSeconds(idempotency.expireSeconds()));
      if (Boolean.FALSE.equals(locked)) {
        throw new BusinessException(ErrorCode.IDEMPOTENT_REPLAY);
      }
      Object result = joinPoint.proceed();
      String body = objectMapper.writeValueAsString(result);
      Stored stored = new Stored(reqHash, body);
      redis.opsForValue().set(redisKey, STATUS_DONE + objectMapper.writeValueAsString(stored),
          Duration.ofSeconds(idempotency.expireSeconds()));
      return result;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      // Redis 故障降级：放行主流程
      log.warn("幂等组件降级放行 key={} err={}", key, ex.getMessage());
      try {
        return joinPoint.proceed();
      } catch (Throwable t) {
        if (t instanceof RuntimeException re) {
          throw re;
        }
        throw new RuntimeException(t);
      }
    }
  }

  private String hashArgs(Object[] args) throws Exception {
    String json = objectMapper.writeValueAsString(args);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
  }

  record Stored(String reqHash, String body) {}
}
