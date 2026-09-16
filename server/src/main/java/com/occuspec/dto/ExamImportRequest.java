package com.occuspec.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 体检 JSON 批量导入请求。 */
public record ExamImportRequest(@NotEmpty @Valid List<ExamJson> exams) {
  /** 单条体检 JSON：与录入表单同构。 */
  public record ExamJson(
      Long personId,
      String hazardCode,
      String examDate,
      List<ExamCreateRequest.Item> items) {}
}
