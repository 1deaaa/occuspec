package com.occuspec.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常处理器：统一错误体 {code, message, details}。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  @ResponseStatus(HttpStatus.OK)
  public ApiResponse<Void> handleBusiness(BusinessException ex, HttpServletRequest request) {
    log.warn("业务异常 path={} code={} msg={}", request.getRequestURI(), ex.getErrorCode(), ex.getMessage());
    return ApiResponse.fail(ex.getErrorCode(), ex.getDetails());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.OK)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
    var details =
        ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));
    return ApiResponse.fail(ErrorCode.VALIDATION_ERROR, details);
  }

  /**
   * 未登录：返回 401 + JSON 错误体。
   *
   * <p>显式声明 {@code produces=application/json}：SSE 请求带 {@code Accept: text/event-stream}，
   * 若走常规内容协商会因"无可接受表示"抛 HttpMediaTypeNotAcceptableException 变成 500，
   * 把真实的鉴权失败掩盖掉。固定返回 JSON，前端据此清理登录态并跳转登录页。
   */
  @ExceptionHandler({NotLoginException.class})
  public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.fail(ErrorCode.AUTH_FAILED, "登录已失效，请重新登录", null));
  }

  @ExceptionHandler({NotPermissionException.class})
  @ResponseStatus(HttpStatus.OK)
  public ApiResponse<Void> handleForbidden(NotPermissionException ex) {
    return ApiResponse.fail(ErrorCode.FORBIDDEN, ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.OK)
  public ApiResponse<Void> handleUnknown(Exception ex, HttpServletRequest request) {
    log.error("未知异常 path={}", request.getRequestURI(), ex);
    return ApiResponse.fail(ErrorCode.INTERNAL_ERROR);
  }
}
