package com.occuspec.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.PageResult;
import com.occuspec.entity.Audit;
import com.occuspec.mapper.AuditMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计回放控制器：按业务对象查询审计链，证据链可回放。
 */
@RestController
@RequestMapping("/audits")
public class AuditController {
  private final AuditMapper auditMapper;

  public AuditController(AuditMapper auditMapper) {
    this.auditMapper = auditMapper;
  }

  /** 审计分页查询。 */
  @GetMapping
  public ApiResponse<PageResult<Map<String, Object>>> list(
      @RequestParam(required = false) String bizType,
      @RequestParam(required = false) String bizId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    var wrapper = new LambdaQueryWrapper<Audit>().orderByDesc(Audit::getId);
    if (bizType != null && !bizType.isBlank()) {
      wrapper.eq(Audit::getBizType, bizType);
    }
    if (bizId != null && !bizId.isBlank()) {
      wrapper.eq(Audit::getBizId, bizId);
    }
    var result = auditMapper.selectPage(new Page<Audit>(page, pageSize), wrapper);
    var data = result.getRecords().stream().map(a -> {
      Map<String, Object> row = new HashMap<>();
      row.put("id", a.getId());
      row.put("bizType", a.getBizType());
      row.put("bizId", a.getBizId());
      row.put("actorId", a.getActorId());
      row.put("action", a.getAction());
      row.put("diff", a.getDiff());
      row.put("traceId", a.getTraceId());
      row.put("costMs", a.getCostMs());
      row.put("createdAt", String.valueOf(a.getCreatedAt()));
      return row;
    }).toList();
    return ApiResponse.ok(PageResult.of(data, page, pageSize, result.getTotal()));
  }

  /** 按评估回放：判定审计 + 关联证据链。 */
  @GetMapping("/assessments/replay")
  public ApiResponse<Map<String, Object>> replay(@RequestParam long assessmentId) {
    var audits = auditMapper.selectList(new LambdaQueryWrapper<Audit>()
        .eq(Audit::getBizType, "ASSESSMENT")
        .eq(Audit::getBizId, String.valueOf(assessmentId))
        .orderByAsc(Audit::getId));
    return ApiResponse.ok(Map.of("assessmentId", assessmentId, "audits", audits.stream().map(a -> {
      Map<String, Object> row = new HashMap<>();
      row.put("action", a.getAction());
      row.put("actorId", a.getActorId());
      row.put("diff", a.getDiff());
      row.put("traceId", a.getTraceId());
      row.put("costMs", a.getCostMs());
      row.put("createdAt", String.valueOf(a.getCreatedAt()));
      return row;
    }).toList()));
  }
}
