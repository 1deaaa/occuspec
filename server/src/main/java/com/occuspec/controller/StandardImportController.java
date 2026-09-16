package com.occuspec.controller;

import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.service.StandardImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标准导入控制器：运维触发全量解析入库。
 * 生产环境应收紧权限，此处先行可用版本。
 */
@RestController
@RequestMapping("/admin/standards")
public class StandardImportController {
  private final StandardImportService importService;

  public StandardImportController(StandardImportService importService) {
    this.importService = importService;
  }

  /** 全量导入数据目录顶层 Markdown。 */
  @PostMapping("/import")
  @AuditLog(action = "标准全量导入")
  public ApiResponse<StandardImportService.ImportResult> importAll() throws Exception {
    return ApiResponse.ok(importService.importAll());
  }
}
