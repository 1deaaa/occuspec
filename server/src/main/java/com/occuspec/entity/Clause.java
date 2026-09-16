package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 条款表：最小可引用单元。 */
@TableName("clauses")
public class Clause {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private String standardCode;
  private String clauseNo;
  private String title;
  private String content;
  private Integer pageNo;
  private String appendixType;
  private String hazardCode;
  private String checkClass;
  private String targetText;
  private String periodText;
  private String forceType;
  private String relations;
  private String contentHash;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getStandardCode() { return standardCode; }
  public void setStandardCode(String standardCode) { this.standardCode = standardCode; }
  public String getClauseNo() { return clauseNo; }
  public void setClauseNo(String clauseNo) { this.clauseNo = clauseNo; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  public Integer getPageNo() { return pageNo; }
  public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
  public String getAppendixType() { return appendixType; }
  public void setAppendixType(String appendixType) { this.appendixType = appendixType; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getCheckClass() { return checkClass; }
  public void setCheckClass(String checkClass) { this.checkClass = checkClass; }
  public String getTargetText() { return targetText; }
  public void setTargetText(String targetText) { this.targetText = targetText; }
  public String getPeriodText() { return periodText; }
  public void setPeriodText(String periodText) { this.periodText = periodText; }
  public String getForceType() { return forceType; }
  public void setForceType(String forceType) { this.forceType = forceType; }
  public String getRelations() { return relations; }
  public void setRelations(String relations) { this.relations = relations; }
  public String getContentHash() { return contentHash; }
  public void setContentHash(String contentHash) { this.contentHash = contentHash; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
