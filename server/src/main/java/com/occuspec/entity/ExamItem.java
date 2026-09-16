package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

/** 体检明细表。 */
@TableName("exam_items")
public class ExamItem {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private Long examId;
  private String itemCode;
  private String itemName;
  private BigDecimal valueNum;
  private String valueText;
  private String unit;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getExamId() { return examId; }
  public void setExamId(Long examId) { this.examId = examId; }
  public String getItemCode() { return itemCode; }
  public void setItemCode(String itemCode) { this.itemCode = itemCode; }
  public String getItemName() { return itemName; }
  public void setItemName(String itemName) { this.itemName = itemName; }
  public BigDecimal getValueNum() { return valueNum; }
  public void setValueNum(BigDecimal valueNum) { this.valueNum = valueNum; }
  public String getValueText() { return valueText; }
  public void setValueText(String valueText) { this.valueText = valueText; }
  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }
}
