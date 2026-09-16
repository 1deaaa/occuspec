package com.occuspec.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 幂等注解：写操作携带 Idempotency-Key 头时防重复执行。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotency {
  /** 占位过期秒数，默认 24 小时。 */
  long expireSeconds() default 86400;
}
