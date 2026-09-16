package com.occuspec.dto;

import java.util.List;
import java.util.Map;

/** 判定结果视图：结论 + 证据链 + 推荐 + 用量。 */
public record AssessResultView(
    Long assessmentId,
    Long examId,
    String conclusion,
    String conclusionLabel,
    String conclusionSource,
    List<EvidenceView> evidences,
    List<RecommendationView> recommendations,
    List<Map<String, Object>> toolCalls,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    long costMs) {
  /** 证据视图。 */
  public record EvidenceView(
      String standardCode, String clauseNo, String quote, String itemCode, String reason, Integer pageNo) {}
  /** 推荐视图。 */
  public record RecommendationView(
      String itemCode, String itemName, String reason, boolean extended, String sourceClauseNo) {}
}
