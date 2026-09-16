package com.occuspec.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

  @ExceptionHandler({NotLoginException.class})
  @ResponseStatus(HttpStatus.OK)
  public ApiResponse<Void> handleNotLogin(NotLoginException ex) {
    return ApiResponse.fail(ErrorCode.AUTH_FAILED, ex.getMessage());
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
