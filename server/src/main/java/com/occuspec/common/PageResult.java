package com.occuspec.common;

import java.util.List;

/** 统一分页体：{data, pagination}。 */
public record PageResult<T>(List<T> data, Pagination pagination) {
  public record Pagination(int page, int pageSize, long totalItems, int totalPages) {}

  public static <T> PageResult<T> of(List<T> data, int page, int pageSize, long total) {
    int totalPages = pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
    return new PageResult<>(data, new Pagination(page, pageSize, total, totalPages));
  }
}
