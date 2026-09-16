package com.occuspec.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.common.Idempotency;
import com.occuspec.common.PageResult;
import com.occuspec.common.RateLimit;
import com.occuspec.dto.AssessRequest;
import com.occuspec.dto.AssessResultView;
import com.occuspec.entity.Clause;
import com.occuspec.entity.Hazard;
import com.occuspec.entity.Standard;
import com.occuspec.mapper.ClauseMapper;
import com.occuspec.mapper.HazardMapper;
import com.occuspec.mapper.StandardMapper;
import com.occuspec.service.AssessService;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 判定与基础查询控制器：同步判定、危害路由、标准条款分页查询。
 * 流式判定见 AssessStreamController。
 */
@RestController
public class AssessController {
  private final AssessService assessService;
  private final ClauseMapper clauseMapper;
  private final StandardMapper standardMapper;
  private final HazardMapper hazardMapper;

  public AssessController(
      AssessService assessService, ClauseMapper clauseMapper,
      StandardMapper standardMapper, HazardMapper hazardMapper) {
    this.assessService = assessService;
    this.clauseMapper = clauseMapper;
    this.standardMapper = standardMapper;
    this.hazardMapper = hazardMapper;
  }

  /** 同步判定：返回完整结论与证据链。 */
  @PostMapping("/assessments")
  @Idempotency
  @RateLimit(windowSeconds = 60, maxCount = 30)
  @AuditLog(action = "提交判定")
  public ApiResponse<AssessResultView> assess(@Valid @RequestBody AssessRequest request) {
    return ApiResponse.ok(assessService.assess(request.examId(), request.ruleVersion(), null));
  }

  /** 危害路由：适用标准与节级条款清单。 */
  @PostMapping("/hazard-routes/resolve")
  public ApiResponse<Map<String, Object>> resolve(@RequestBody Map<String, Object> body) {
    Object codes = body.get("hazardCodes");
    java.util.List<String> hazards = new java.util.ArrayList<>();
    if (codes instanceof java.util.List<?> list) {
      for (Object o : list) {
        hazards.add(String.valueOf(o));
      }
    } else if (body.get("hazardCode") != null) {
      hazards.add(String.valueOf(body.get("hazardCode")));
    }
    var routes = new java.util.ArrayList<Map<String, Object>>();
    for (String hazard : hazards) {
      var clauses = clauseMapper.selectList(new LambdaQueryWrapper<Clause>()
          .eq(Clause::getHazardCode, hazard).last("LIMIT 50")).stream()
          .filter(c -> c.getClauseNo() != null && !c.getClauseNo().startsWith("DOC:") && c.getClauseNo().length() <= 6)
          .map(c -> Map.<String, Object>of("standardCode", c.getStandardCode(), "clauseNo", c.getClauseNo(),
              "title", c.getTitle() == null ? "" : c.getTitle()))
          .toList();
      Map<String, Object> route = new HashMap<>();
      route.put("hazard", hazard);
      route.put("clauses", clauses);
      routes.add(route);
    }
    return ApiResponse.ok(Map.of("routes", routes));
  }

  /** 标准分页列表。 */
  @GetMapping("/standards")
  public ApiResponse<PageResult<Map<String, Object>>> standards(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    var result = standardMapper.selectPage(new Page<Standard>(page, pageSize),
        new LambdaQueryWrapper<Standard>().orderByAsc(Standard::getCode));
    var data = result.getRecords().stream()
        .map(s -> Map.<String, Object>of("code", s.getCode(), "name", s.getName(),
            "status", s.getStatus() == null ? "" : s.getStatus()))
        .toList();
    return ApiResponse.ok(PageResult.of(data, page, pageSize, result.getTotal()));
  }

  /** 条款分页查询：按标准号/危害因素/关键词过滤。 */
  @GetMapping("/clauses")
  public ApiResponse<PageResult<Map<String, Object>>> clauses(
      @RequestParam(required = false) String standardCode,
      @RequestParam(required = false) String hazard,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    var wrapper = new LambdaQueryWrapper<Clause>().notLike(Clause::getClauseNo, "DOC:");
    if (standardCode != null && !standardCode.isBlank()) {
      wrapper.eq(Clause::getStandardCode, standardCode);
    }
    if (hazard != null && !hazard.isBlank()) {
      wrapper.eq(Clause::getHazardCode, hazard);
    }
    if (keyword != null && !keyword.isBlank()) {
      wrapper.and(w -> w.like(Clause::getTitle, keyword).or().like(Clause::getContent, keyword));
    }
    wrapper.orderByAsc(Clause::getId);
    var result = clauseMapper.selectPage(new Page<Clause>(page, pageSize), wrapper);
    var data = result.getRecords().stream().map(c -> {
      Map<String, Object> row = new HashMap<>();
      row.put("id", c.getId());
      row.put("standardCode", c.getStandardCode());
      row.put("clauseNo", c.getClauseNo());
      row.put("title", c.getTitle());
      row.put("pageNo", c.getPageNo());
      row.put("appendixType", c.getAppendixType());
      row.put("hazardCode", c.getHazardCode());
      row.put("quote", c.getContent() == null ? "" : c.getContent().substring(0, Math.min(300, c.getContent().length())));
      return row;
    }).toList();
    return ApiResponse.ok(PageResult.of(data, page, pageSize, result.getTotal()));
  }

  /** 单条款原文。 */
  @GetMapping("/clauses/detail")
  public ApiResponse<Map<String, Object>> clauseDetail(@RequestParam long id) {
    Clause clause = clauseMapper.selectById(id);
    if (clause == null) {
      return ApiResponse.ok(Map.of("found", false));
    }
    Map<String, Object> data = new HashMap<>();
    data.put("found", true);
    data.put("standardCode", clause.getStandardCode());
    data.put("clauseNo", clause.getClauseNo());
    data.put("title", clause.getTitle());
    data.put("content", clause.getContent());
    data.put("pageNo", clause.getPageNo());
    data.put("appendixType", clause.getAppendixType());
    return ApiResponse.ok(data);
  }

  /** 危害因素列表。 */
  @GetMapping("/hazards")
  public ApiResponse<Map<String, Object>> hazards() {
    var list = hazardMapper.selectList(new LambdaQueryWrapper<Hazard>().orderByAsc(Hazard::getCode));
    return ApiResponse.ok(Map.of("data", list.stream().map(h -> Map.of(
        "code", h.getCode(), "name", h.getName(),
        "category", h.getCategory() == null ? "" : h.getCategory())).toList()));
  }

  /** 当前会话信息。 */
  @GetMapping("/auth/me")
  public ApiResponse<Map<String, Object>> me() {
    boolean login = StpUtil.isLogin();
    Map<String, Object> data = new HashMap<>();
    data.put("login", login);
    data.put("loginId", login ? String.valueOf(StpUtil.getLoginId()) : "");
    return ApiResponse.ok(data);
  }
}
