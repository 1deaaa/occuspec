package com.occuspec.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 限流切面：Redis 计数窗口限流，Redis 故障时降级放行。 */
@Aspect
@Component
public class RateLimitAspect {
  private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);
  private final StringRedisTemplate redis;

  public RateLimitAspect(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Around("@annotation(rateLimit)")
  public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return joinPoint.proceed();
    }
    HttpServletRequest request = attrs.getRequest();
    String client = request.getRemoteAddr();
    String key = "occuspec:ratelimit:" + joinPoint.getSignature().toShortString() + ":" + client;
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1) {
        redis.expire(key, Duration.ofSeconds(rateLimit.windowSeconds()));
      }
      if (count != null && count > rateLimit.maxCount()) {
        throw new BusinessException(ErrorCode.RATE_LIMITED);
      }
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      log.warn("限流组件降级放行 err={}", ex.getMessage());
    }
    return joinPoint.proceed();
  }
}
