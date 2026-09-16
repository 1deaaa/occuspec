package com.occuspec.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 体检录入请求。 */
public record ExamCreateRequest(
    @NotNull Long personId,
    @NotBlank String hazardCode,
    @NotNull LocalDate examDate,
    @NotEmpty @Valid List<Item> items) {
  /** 检查项：数值与文本至少填一个。 */
  public record Item(
      @NotBlank String itemCode,
      String itemName,
      BigDecimal valueNum,
      String valueText,
      String unit) {}
}
