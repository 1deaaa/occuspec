package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 报告抽取的检查项，也是人工核对单位。 */
@TableName("report_upload_items")
public class ReportUploadItem {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private Long uploadId;
  /** 抽取到的原文名称。 */
  private String itemName;
  /** 归一化后的规范 fact 编码。 */
  private String itemCode;
  private BigDecimal valueNum;
  private String valueText;
  private String unit;
  /** 模型自评置信度 0-1。 */
  private BigDecimal confidence;
  /** 人工是否采纳。 */
  private Boolean confirmed;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getUploadId() { return uploadId; }
  public void setUploadId(Long uploadId) { this.uploadId = uploadId; }
  public String getItemName() { return itemName; }
  public void setItemName(String itemName) { this.itemName = itemName; }
  public String getItemCode() { return itemCode; }
  public void setItemCode(String itemCode) { this.itemCode = itemCode; }
  public BigDecimal getValueNum() { return valueNum; }
  public void setValueNum(BigDecimal valueNum) { this.valueNum = valueNum; }
  public String getValueText() { return valueText; }
  public void setValueText(String valueText) { this.valueText = valueText; }
  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }
  public BigDecimal getConfidence() { return confidence; }
  public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
  public Boolean getConfirmed() { return confirmed; }
  public void setConfirmed(Boolean confirmed) { this.confirmed = confirmed; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
