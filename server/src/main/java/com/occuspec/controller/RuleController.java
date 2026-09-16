package com.occuspec.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.common.BusinessException;
import com.occuspec.common.ErrorCode;
import com.occuspec.common.PageResult;
import com.occuspec.dto.RuleUpsertRequest;
import com.occuspec.entity.Rule;
import com.occuspec.mapper.RuleMapper;
import com.occuspec.rule.RuleEvaluator;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则管理控制器：列表、创建、更新、启停、删除。
 */
@RestController
@RequestMapping("/rules")
public class RuleController {
  private final RuleMapper ruleMapper;
  private final RuleEvaluator evaluator;

  public RuleController(RuleMapper ruleMapper) {
    this.ruleMapper = ruleMapper;
    this.evaluator = new RuleEvaluator();
  }

  /** 规则分页列表。 */
  @GetMapping
  public ApiResponse<PageResult<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String hazard) {
    var wrapper = new LambdaQueryWrapper<Rule>().orderByDesc(Rule::getWeight);
    if (hazard != null && !hazard.isBlank()) {
      wrapper.eq(Rule::getHazardCode, hazard);
    }
    var result = ruleMapper.selectPage(new Page<Rule>(page, pageSize), wrapper);
    var data = result.getRecords().stream().map(this::toMap).toList();
    return ApiResponse.ok(PageResult.of(data, page, pageSize, result.getTotal()));
  }

  /** 创建规则：表达式需为合法 JSON 且可求值。 */
  @PostMapping
  @AuditLog(action = "创建规则")
  public ApiResponse<Map<String, Object>> create(@Valid @RequestBody RuleUpsertRequest request) {
    validateExpression(request.expression());
    var exists = ruleMapper.selectCount(
        new LambdaQueryWrapper<Rule>().eq(Rule::getCode, request.code()));
    if (exists != null && exists > 0) {
      throw new BusinessException(ErrorCode.CONFLICT, "规则编码已存在");
    }
    Rule rule = new Rule();
    apply(rule, request);
    rule.setEnabled(true);
    ruleMapper.insert(rule);
    return ApiResponse.ok(Map.of("id", rule.getId()));
  }

  /** 更新规则。 */
  @PatchMapping("/{id}")
  @AuditLog(action = "更新规则")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable long id, @Valid @RequestBody RuleUpsertRequest request) {
    Rule rule = ruleMapper.selectById(id);
    if (rule == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "规则不存在");
    }
    validateExpression(request.expression());
    apply(rule, request);
    ruleMapper.updateById(rule);
    return ApiResponse.ok(Map.of("id", rule.getId()));
  }

  /** 启用规则。 */
  @PostMapping("/{id}/enable")
  @AuditLog(action = "启用规则")
  public ApiResponse<Map<String, Object>> enable(@PathVariable long id) {
    Rule rule = ruleMapper.selectById(id);
    if (rule == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "规则不存在");
    }
    rule.setEnabled(true);
    ruleMapper.updateById(rule);
    return ApiResponse.ok(Map.of("id", id, "enabled", true));
  }

  /** 停用规则。 */
  @PostMapping("/{id}/disable")
  @AuditLog(action = "停用规则")
  public ApiResponse<Map<String, Object>> disable(@PathVariable long id) {
    Rule rule = ruleMapper.selectById(id);
    if (rule == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "规则不存在");
    }
    rule.setEnabled(false);
    ruleMapper.updateById(rule);
    return ApiResponse.ok(Map.of("id", id, "enabled", false));
  }

  /** 删除规则。 */
  @DeleteMapping("/{id}")
  @AuditLog(action = "删除规则")
  public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
    ruleMapper.deleteById(id);
    return ApiResponse.ok(Map.of("id", id));
  }

  private void apply(Rule rule, RuleUpsertRequest request) {
    rule.setCode(request.code());
    rule.setName(request.name());
    rule.setHazardCode(request.hazardCode() == null ? "" : request.hazardCode());
    rule.setExpression(request.expression());
    rule.setConclusion(request.conclusion());
    rule.setWeight(request.weight() == null ? 100 : request.weight());
    rule.setVersion(request.version() == null ? "v1" : request.version());
  }

  private void validateExpression(String expression) {
    try {
      // 用空事实求值一次，只验证 JSON 合法性（空事实下仅兜底规则命中，不报错）
      evaluator.matches(expression, Map.of());
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "规则表达式非法");
    }
    if (expression == null || expression.isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "规则表达式不能为空");
    }
  }

  private Map<String, Object> toMap(Rule rule) {
    Map<String, Object> row = new HashMap<>();
    row.put("id", rule.getId());
    row.put("code", rule.getCode());
    row.put("name", rule.getName());
    row.put("hazardCode", rule.getHazardCode());
    row.put("expression", rule.getExpression());
    row.put("conclusion", rule.getConclusion());
    row.put("weight", rule.getWeight());
    row.put("enabled", rule.getEnabled());
    row.put("version", rule.getVersion());
    return row;
  }
}
