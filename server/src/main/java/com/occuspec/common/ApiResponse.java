package com.occuspec.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 统一响应体：成功返回 {code=0,message,data}，失败返回错误码与明细。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data, Object details) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data, null);
  }

  public static <T> ApiResponse<T> fail(ErrorCode error, Object details) {
    return new ApiResponse<>(error.getCode(), error.getMessage(), null, details);
  }

  public static <T> ApiResponse<T> fail(ErrorCode error) {
    return fail(error, null);
  }
}
