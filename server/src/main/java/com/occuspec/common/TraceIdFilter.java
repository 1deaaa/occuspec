package com.occuspec.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 链路追踪过滤器：生成 traceId 并贯穿 MDC 与响应头。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
  public static final String TRACE_ID = "traceId";
  public static final String HEADER = "X-Trace-Id";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = request.getHeader(HEADER);
    if (traceId == null || traceId.isBlank()) {
      traceId = UUID.randomUUID().toString().replace("-", "");
    }
    MDC.put(TRACE_ID, traceId);
    response.setHeader(HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(TRACE_ID);
    }
  }
}
