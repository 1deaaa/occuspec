package com.occuspec.common;

/** 业务异常：携带错误码与明细，由全局异常处理器统一转响应体。 */
public class BusinessException extends RuntimeException {
  private final ErrorCode errorCode;
  private final Object details;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
    this.details = null;
  }

  public BusinessException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
    this.details = null;
  }

  public BusinessException(ErrorCode errorCode, Object details) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
    this.details = details;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public Object getDetails() {
    return details;
  }
}
