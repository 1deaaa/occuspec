package com.occuspec.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.common.BusinessException;
import com.occuspec.common.ErrorCode;
import com.occuspec.common.Idempotency;
import com.occuspec.common.PageResult;
import com.occuspec.entity.BatchTask;
import com.occuspec.enums.BatchTaskStatus;
import com.occuspec.mapper.BatchTaskMapper;
import com.occuspec.service.BatchTaskService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 批量任务控制器：CSV 上传建任务、状态轮询、Excel 导出。
 */
@RestController
@RequestMapping("/batch-tasks")
public class BatchTaskController {
  private final BatchTaskService batchTaskService;
  private final BatchTaskMapper batchTaskMapper;

  public BatchTaskController(BatchTaskService batchTaskService, BatchTaskMapper batchTaskMapper) {
    this.batchTaskService = batchTaskService;
    this.batchTaskMapper = batchTaskMapper;
  }

  /** CSV 上传：首列为 examId，每行一条，创建异步批量判定任务。 */
  @PostMapping("/upload-csv")
  @Idempotency
  @AuditLog(action = "上传批量任务")
  public ApiResponse<Map<String, Object>> upload(
      @RequestParam MultipartFile file, @RequestParam(defaultValue = "CSV_IMPORT") String type) throws Exception {
    List<Long> examIds = new ArrayList<>();
    try (var reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String cell = line.split(",")[0].trim();
        try {
          examIds.add(Long.parseLong(cell));
        } catch (NumberFormatException ignored) {
          // 跳过表头与非法行
        }
      }
    }
    if (examIds.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CSV 中未找到有效的 examId");
    }
    BatchTask task = batchTaskService.create(type, file.getOriginalFilename(), examIds, "");
    return ApiResponse.ok(Map.of("taskId", task.getId(), "status", task.getStatus(), "total", task.getTotal()));
  }

  /** 任务分页列表。 */
  @GetMapping
  public ApiResponse<PageResult<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String status) {
    var wrapper = new LambdaQueryWrapper<BatchTask>().orderByDesc(BatchTask::getId);
    if (status != null && !status.isBlank()) {
      wrapper.eq(BatchTask::getStatus, status);
    }
    var result = batchTaskMapper.selectPage(new Page<BatchTask>(page, pageSize), wrapper);
    var data = result.getRecords().stream().map(t -> {
      Map<String, Object> row = new HashMap<>();
      row.put("taskId", t.getId());
      row.put("type", t.getType());
      row.put("status", t.getStatus());
      row.put("total", t.getTotal());
      row.put("success", t.getSuccess());
      row.put("fail", t.getFail());
      row.put("createdAt", String.valueOf(t.getCreatedAt()));
      return row;
    }).toList();
    return ApiResponse.ok(PageResult.of(data, page, pageSize, result.getTotal()));
  }

  /** 任务详情。 */
  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
    BatchTask task = batchTaskMapper.selectById(id);
    if (task == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
    }
    Map<String, Object> data = new HashMap<>();
    data.put("taskId", task.getId());
    data.put("type", task.getType());
    data.put("status", task.getStatus());
    data.put("total", task.getTotal());
    data.put("success", task.getSuccess());
    data.put("fail", task.getFail());
    return ApiResponse.ok(data);
  }

  /** Excel 导出：任务未完成时返回 409。 */
  @GetMapping("/{id}/export-xlsx")
  public ResponseEntity<StreamingResponseBody> exportXlsx(@PathVariable long id) {
    BatchTask task = batchTaskMapper.selectById(id);
    if (task == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
    }
    if (!BatchTaskStatus.SUCCESS.name().equals(task.getStatus())
        && !BatchTaskStatus.FAILED.name().equals(task.getStatus())) {
      throw new BusinessException(ErrorCode.TASK_NOT_READY);
    }
    Workbook workbook = batchTaskService.exportXlsx(id);
    StreamingResponseBody body = output -> {
      try (workbook) {
        workbook.write(output);
      }
    };
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    headers.setContentDisposition(ContentDisposition.attachment().filename("batch-" + id + ".xlsx").build());
    return ResponseEntity.ok().headers(headers).body(body);
  }
}
