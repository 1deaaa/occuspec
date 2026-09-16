package com.occuspec.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.common.Idempotency;
import com.occuspec.dto.ExamCreateRequest;
import com.occuspec.dto.ExamImportRequest;
import com.occuspec.service.ExamService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 体检录入控制器：表单录入与 JSON 导入。
 */
@RestController
@RequestMapping("/exams")
public class ExamController {
  private final ExamService examService;

  public ExamController(ExamService examService) {
    this.examService = examService;
  }

  /** 表单录入：单条体检记录。 */
  @PostMapping
  @Idempotency
  @AuditLog(action = "录入体检记录")
  public ApiResponse<Map<String, Object>> create(@Valid @RequestBody ExamCreateRequest request) {
    String operator = StpUtil.isLogin() ? String.valueOf(StpUtil.getLoginId()) : "";
    return ApiResponse.ok(examService.create(request, operator));
  }

  /** JSON 导入：多条体检记录，逐条落库并汇总失败。 */
  @PostMapping("/import-json")
  @Idempotency
  @AuditLog(action = "JSON导入体检记录")
  public ApiResponse<Map<String, Object>> importJson(@Valid @RequestBody ExamImportRequest request) {
    String operator = StpUtil.isLogin() ? String.valueOf(StpUtil.getLoginId()) : "";
    List<Long> examIds = new ArrayList<>();
    List<Map<String, Object>> failures = new ArrayList<>();
    for (int i = 0; i < request.exams().size(); i++) {
      var json = request.exams().get(i);
      try {
        var create = new ExamCreateRequest(
            json.personId(), json.hazardCode(),
            json.examDate() == null ? LocalDate.now() : LocalDate.parse(json.examDate()),
            json.items() == null ? List.of() : json.items());
        var result = examService.create(create, operator);
        examIds.add((Long) result.get("examId"));
      } catch (Exception ex) {
        Map<String, Object> failure = new HashMap<>();
        failure.put("index", i);
        failure.put("message", ex.getMessage());
        failures.add(failure);
      }
    }
    Map<String, Object> data = new HashMap<>();
    data.put("examIds", examIds);
    data.put("failures", failures);
    return ApiResponse.ok(data);
  }
}
