package com.occuspec.common;

/** 判定与批量接口限流注解（Redis 计数窗口，故障降级放行）。 */
@java.lang.annotation.Documented
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
public @interface RateLimit {
  /** 窗口秒数。 */
  int windowSeconds() default 60;

  /** 窗口内最大次数。 */
  int maxCount() default 60;
}
