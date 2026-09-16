package com.occuspec.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.common.PageResult;
import com.occuspec.dto.AssessResultView;
import com.occuspec.entity.Assessment;
import com.occuspec.entity.Evidence;
import com.occuspec.entity.Recommendation;
import com.occuspec.enums.Conclusion;
import com.occuspec.mapper.AssessmentMapper;
import com.occuspec.mapper.EvidenceMapper;
import com.occuspec.mapper.RecommendationMapper;
import com.occuspec.service.AuditService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告与复核控制器：报告查询、复核签字、推荐分开展示。
 */
@RestController
public class ReportController {
  private final AssessmentMapper assessmentMapper;
  private final EvidenceMapper evidenceMapper;
  private final RecommendationMapper recommendationMapper;
  private final AuditService auditService;

  public ReportController(
      AssessmentMapper assessmentMapper, EvidenceMapper evidenceMapper,
      RecommendationMapper recommendationMapper, AuditService auditService) {
    this.assessmentMapper = assessmentMapper;
    this.evidenceMapper = evidenceMapper;
    this.recommendationMapper = recommendationMapper;
    this.auditService = auditService;
  }

  /** 报告查询：结论 + 证据链 + 两类推荐分开。 */
  @GetMapping("/assessments/{id}/report")
  public ApiResponse<Map<String, Object>> report(@PathVariable long id) {
    Assessment assessment = assessmentMapper.selectById(id);
    if (assessment == null) {
      return ApiResponse.ok(Map.of("found", false));
    }
    var evidences = evidenceMapper.selectList(
        new LambdaQueryWrapper<Evidence>().eq(Evidence::getAssessmentId, id));
    var recommendations = recommendationMapper.selectList(
        new LambdaQueryWrapper<Recommendation>().eq(Recommendation::getAssessmentId, id));
    var standardRecs = recommendations.stream().filter(r -> !Boolean.TRUE.equals(r.getExtended())).toList();
    var extendedRecs = recommendations.stream().filter(r -> Boolean.TRUE.equals(r.getExtended())).toList();
    Map<String, Object> data = new HashMap<>();
    data.put("assessmentId", assessment.getId());
    data.put("examId", assessment.getExamId());
    data.put("conclusion", assessment.getConclusion());
    data.put("conclusionLabel", labelOf(assessment.getConclusion()));
    data.put("conclusionSource", assessment.getConclusionSource());
    data.put("reviewStatus", assessment.getReviewStatus());
    data.put("reviewerId", assessment.getReviewerId());
    data.put("reviewComment", assessment.getReviewComment());
    data.put("evidences", evidences.stream().map(e -> Map.of(
        "standardCode", e.getStandardCode(), "clauseNo", e.getClauseNo(),
        "quote", e.getQuote() == null ? "" : e.getQuote(),
        "reason", e.getReason() == null ? "" : e.getReason())).toList());
    data.put("standardRecommendations", standardRecs.stream().map(r -> Map.of(
        "itemCode", r.getItemCode(), "itemName", r.getItemName() == null ? "" : r.getItemName(),
        "reason", r.getReason() == null ? "" : r.getReason(),
        "sourceClauseNo", r.getSourceClauseNo() == null ? "" : r.getSourceClauseNo())).toList());
    data.put("extendedRecommendations", extendedRecs.stream().map(r -> Map.of(
        "itemCode", r.getItemCode(), "itemName", r.getItemName() == null ? "" : r.getItemName(),
        "reason", r.getReason() == null ? "" : r.getReason())).toList());
    data.put("usage", Map.of("prompt", assessment.getPromptTokens(), "completion", assessment.getCompletionTokens(),
        "total", assessment.getTotalTokens(), "costMs", assessment.getCostMs()));
    data.put("disclaimer", "本结论为建议性质，须经主检医师复核签字后生效。");
    return ApiResponse.ok(data);
  }

  /** 复核签字：主检医师确认结论生效。 */
  @PostMapping("/assessments/{id}/review")
  @AuditLog(action = "复核签字")
  public ApiResponse<Map<String, Object>> review(
      @PathVariable long id, @RequestBody Map<String, Object> body) {
    Assessment assessment = assessmentMapper.selectById(id);
    if (assessment == null) {
      return ApiResponse.ok(Map.of("ok", false, "message", "评估不存在"));
    }
    String reviewer = body.get("reviewerId") == null ? "" : String.valueOf(body.get("reviewerId"));
    String comment = body.get("comment") == null ? "" : String.valueOf(body.get("comment"));
    assessment.setReviewerId(reviewer);
    assessment.setReviewStatus("REVIEWED");
    assessment.setReviewComment(comment);
    assessmentMapper.updateById(assessment);
    auditService.record("ASSESSMENT", String.valueOf(id), reviewer, "复核签字", comment, 0);
    return ApiResponse.ok(Map.of("ok", true, "reviewStatus", "REVIEWED"));
  }

  /** 评估分页列表。 */
  @GetMapping("/assessments")
  public ApiResponse<PageResult<Map<String, Object>>> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String conclusion) {
    var wrapper = new LambdaQueryWrapper<Assessment>().orderByDesc(Assessment::getId);
    if (conclusion != null && !conclusion.isBlank()) {
      wrapper.eq(Assessment::getConclusion, conclusion);
    }
    var result = assessmentMapper.selectPage(new Page<Assessment>(page, pageSize), wrapper);
    List<Map<String, Object>> data = result.getRecords().stream().map(a -> {
      Map<String, Object> row = new HashMap<>();
      row.put("assessmentId", a.getId());
      row.put("examId", a.getExamId());
      row.put("conclusion", a.getConclusion());
      row.put("conclusionLabel", labelOf(a.getConclusion()));
      row.put("reviewStatus", a.getReviewStatus());
      row.put("totalTokens", a.getTotalTokens());
      row.put("createdAt", String.valueOf(a.getCreatedAt()));
      return row;
    }).toList();
    return ApiResponse.ok(PageResult.of(data, page, pageSize, result.getTotal()));
  }

  private String labelOf(String conclusion) {
    try {
      return Conclusion.valueOf(conclusion).getLabel();
    } catch (Exception e) {
      return conclusion == null ? "" : conclusion;
    }
  }

  /** 推荐查询：按是否机构扩展过滤。 */
  @GetMapping("/recommendations")
  public ApiResponse<Map<String, Object>> recommendations(
      @RequestParam long assessmentId, @RequestParam(required = false) Boolean extended) {
    var wrapper = new LambdaQueryWrapper<Recommendation>().eq(Recommendation::getAssessmentId, assessmentId);
    if (extended != null) {
      wrapper.eq(Recommendation::getExtended, extended);
    }
    var list = recommendationMapper.selectList(wrapper);
    var views = list.stream().map(r -> new AssessResultView.RecommendationView(
        r.getItemCode(), r.getItemName(), r.getReason(),
        Boolean.TRUE.equals(r.getExtended()), r.getSourceClauseNo())).toList();
    return ApiResponse.ok(Map.of("data", views));
  }
}
