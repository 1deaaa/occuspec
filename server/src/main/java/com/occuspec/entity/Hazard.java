package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 危害因素表。 */
@TableName("hazards")
public class Hazard {
  @TableId(type = IdType.INPUT)
  private String code;
  /** 来源章节号，如 7.1。 */
  private String sectionNo;
  /** 来源标准号。 */
  private String sourceStandard;
  private String name;
  private String category;
  private String exposureLimit;
  /** 别名 JSON 数组，用于自然语言匹配。 */
  private String aliases;
  private LocalDateTime createdAt;

  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getSectionNo() { return sectionNo; }
  public void setSectionNo(String sectionNo) { this.sectionNo = sectionNo; }
  public String getSourceStandard() { return sourceStandard; }
  public void setSourceStandard(String sourceStandard) { this.sourceStandard = sourceStandard; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getExposureLimit() { return exposureLimit; }
  public void setExposureLimit(String exposureLimit) { this.exposureLimit = exposureLimit; }
  public String getAliases() { return aliases; }
  public void setAliases(String aliases) { this.aliases = aliases; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
