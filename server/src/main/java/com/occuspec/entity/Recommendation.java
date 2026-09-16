package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 额外检查推荐表。 */
@TableName("recommendations")
public class Recommendation {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private Long assessmentId;
  private String itemCode;
  private String itemName;
  private String reason;
  @TableField("is_extended")
  private Boolean extended;
  private String sourceClauseNo;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getAssessmentId() { return assessmentId; }
  public void setAssessmentId(Long assessmentId) { this.assessmentId = assessmentId; }
  public String getItemCode() { return itemCode; }
  public void setItemCode(String itemCode) { this.itemCode = itemCode; }
  public String getItemName() { return itemName; }
  public void setItemName(String itemName) { this.itemName = itemName; }
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }
  public Boolean getExtended() { return extended; }
  public void setExtended(Boolean extended) { this.extended = extended; }
  public String getSourceClauseNo() { return sourceClauseNo; }
  public void setSourceClauseNo(String sourceClauseNo) { this.sourceClauseNo = sourceClauseNo; }
}
