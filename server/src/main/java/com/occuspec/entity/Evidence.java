package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 证据链表。 */
@TableName("evidences")
public class Evidence {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private Long assessmentId;
  private Long clauseId;
  private String standardCode;
  private String clauseNo;
  private String quote;
  private String itemCode;
  private String reason;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getAssessmentId() { return assessmentId; }
  public void setAssessmentId(Long assessmentId) { this.assessmentId = assessmentId; }
  public Long getClauseId() { return clauseId; }
  public void setClauseId(Long clauseId) { this.clauseId = clauseId; }
  public String getStandardCode() { return standardCode; }
  public void setStandardCode(String standardCode) { this.standardCode = standardCode; }
  public String getClauseNo() { return clauseNo; }
  public void setClauseNo(String clauseNo) { this.clauseNo = clauseNo; }
  public String getQuote() { return quote; }
  public void setQuote(String quote) { this.quote = quote; }
  public String getItemCode() { return itemCode; }
  public void setItemCode(String itemCode) { this.itemCode = itemCode; }
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }
}
