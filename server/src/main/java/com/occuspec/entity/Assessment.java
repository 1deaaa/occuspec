package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 判定评估表。 */
@TableName("assessments")
public class Assessment {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private Long examId;
  private String inputSnapshot;
  private String conclusion;
  private String conclusionSource;
  private String ruleVersion;
  private String reviewerId;
  private String reviewStatus;
  private String reviewComment;
  private Long promptTokens;
  private Long completionTokens;
  private Long totalTokens;
  private Long costMs;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getExamId() { return examId; }
  public void setExamId(Long examId) { this.examId = examId; }
  public String getInputSnapshot() { return inputSnapshot; }
  public void setInputSnapshot(String inputSnapshot) { this.inputSnapshot = inputSnapshot; }
  public String getConclusion() { return conclusion; }
  public void setConclusion(String conclusion) { this.conclusion = conclusion; }
  public String getConclusionSource() { return conclusionSource; }
  public void setConclusionSource(String conclusionSource) { this.conclusionSource = conclusionSource; }
  public String getRuleVersion() { return ruleVersion; }
  public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
  public String getReviewerId() { return reviewerId; }
  public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }
  public String getReviewStatus() { return reviewStatus; }
  public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
  public String getReviewComment() { return reviewComment; }
  public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
  public Long getPromptTokens() { return promptTokens; }
  public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
  public Long getCompletionTokens() { return completionTokens; }
  public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
  public Long getTotalTokens() { return totalTokens; }
  public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }
  public Long getCostMs() { return costMs; }
  public void setCostMs(Long costMs) { this.costMs = costMs; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
