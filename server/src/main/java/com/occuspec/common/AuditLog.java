package com.occuspec.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 审计日志注解：标记需要记录耗时、入参与操作人的方法。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditLog {
  /** 业务动作描述，如"提交判定"。 */
  String action() default "";
}
