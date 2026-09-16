package com.occuspec.common;

/** 全局错误码表，进 API 文档。 */
public enum ErrorCode {
  SUCCESS(0, "成功"),
  VALIDATION_ERROR(40001, "参数校验失败"),
  AUTH_FAILED(40101, "认证失败"),
  FORBIDDEN(40301, "无访问权限"),
  NOT_FOUND(40401, "资源不存在"),
  CONFLICT(40901, "资源冲突"),
  IDEMPOTENT_REPLAY(40902, "重复提交，已返回首次执行结果"),
  IDEMPOTENT_KEY_MISMATCH(40903, "幂等键被复用于不同请求"),
  RATE_LIMITED(42901, "请求过于频繁"),
  LLM_TIMEOUT(50401, "模型调用超时，已降级"),
  LLM_DEGRADED(50402, "模型服务降级，已使用规则结论"),
  TASK_NOT_READY(40904, "批量任务尚未完成"),
  INTERNAL_ERROR(50001, "系统内部错误");

  private final int code;
  private final String message;

  ErrorCode(int code, String message) {
    this.code = code;
    this.message = message;
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }
}
