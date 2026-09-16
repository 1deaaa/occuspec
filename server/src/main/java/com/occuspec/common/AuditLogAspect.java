package com.occuspec.common;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计日志切面：记录方法耗时、入参与操作人。审计写入失败不得影响主流程。
 */
@Aspect
@Component
public class AuditLogAspect {
  private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
  private final ObjectMapper objectMapper;

  public AuditLogAspect(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Around("@annotation(auditLog)")
  public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
    long start = System.currentTimeMillis();
    String actor = "anonymous";
    try {
      if (StpUtil.isLogin()) {
        actor = String.valueOf(StpUtil.getLoginId());
      }
    } catch (Exception ignored) {
      // 未登录时保持匿名
    }
    try {
      Object result = joinPoint.proceed();
      long cost = System.currentTimeMillis() - start;
      log.info(
          "audit action={} actor={} costMs={} target={}",
          auditLog.action(),
          actor,
          cost,
          joinPoint.getSignature().toShortString());
      return result;
    } catch (Throwable ex) {
      long cost = System.currentTimeMillis() - start;
      log.warn(
          "audit-fail action={} actor={} costMs={} target={} err={}",
          auditLog.action(),
          actor,
          cost,
          joinPoint.getSignature().toShortString(),
          ex.getMessage());
      throw ex;
    }
  }

  /** 入参摘要，避免日志过大。 */
  String summarizeArgs(Object[] args) {
    try {
      String json = objectMapper.writeValueAsString(args);
      return json.length() > 2000 ? json.substring(0, 2000) + "..." : json;
    } catch (Exception e) {
      return "<unserializable>";
    }
  }
}
