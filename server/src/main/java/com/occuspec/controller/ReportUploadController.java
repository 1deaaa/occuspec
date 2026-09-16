package com.occuspec.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.common.RateLimit;
import com.occuspec.service.ReportUploadService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 报告上传控制器：上传抽取 → 人工核对 → 确认入库。
 *
 * <p>三步分离体现责任边界：模型抽取仅为草稿，人工核对后才可入库参与判定。
 */
@RestController
@RequestMapping("/report-uploads")
public class ReportUploadController {
  private final ReportUploadService uploadService;

  public ReportUploadController(ReportUploadService uploadService) {
    this.uploadService = uploadService;
  }

  /** 上传报告图片并抽取（不落体检数据，返回草稿供核对）。 */
  @PostMapping
  @RateLimit(windowSeconds = 60, maxCount = 10)
  @AuditLog(action = "上传报告抽取")
  public ApiResponse<ReportUploadService.UploadView> upload(
      @RequestParam MultipartFile file,
      @RequestParam(required = false) Long personId,
      @RequestParam(required = false) String hazardCode,
      @RequestParam(required = false) String personHint) throws Exception {
    String creator = StpUtil.isLogin() ? String.valueOf(StpUtil.getLoginId()) : "";
    return ApiResponse.ok(uploadService.uploadAndExtract(
        file.getBytes(), file.getOriginalFilename(), file.getContentType(),
        personId, hazardCode, personHint, creator));
  }

  /** 上传记录列表。 */
  @GetMapping
  public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
    return ApiResponse.ok(Map.of("data", uploadService.list(limit)));
  }

  /** 单条上传记录（含核对项）。 */
  @GetMapping("/{id}")
  public ApiResponse<ReportUploadService.UploadView> get(@PathVariable long id) {
    return ApiResponse.ok(uploadService.get(id));
  }

  /** 人工核对：提交修正后的检查项与采纳状态。 */
  @PostMapping("/{id}/confirm")
  @AuditLog(action = "核对报告抽取项")
  public ApiResponse<ReportUploadService.UploadView> confirm(
      @PathVariable long id, @RequestBody List<ReportUploadService.ConfirmItem> items) {
    return ApiResponse.ok(uploadService.confirm(id, items));
  }

  /** 确认入库：已采纳项写入体检记录。 */
  @PostMapping("/{id}/import")
  @AuditLog(action = "报告确认入库")
  public ApiResponse<Map<String, Object>> importToExam(
      @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> safe = body == null ? Map.of() : body;
    Long personId = safe.get("personId") == null ? null : Long.valueOf(String.valueOf(safe.get("personId")));
    String examDate = safe.get("examDate") == null ? null : String.valueOf(safe.get("examDate"));
    String operator = StpUtil.isLogin() ? String.valueOf(StpUtil.getLoginId()) : "";
    return ApiResponse.ok(uploadService.importToExam(id, personId, examDate, operator));
  }
}
