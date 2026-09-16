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
  private String name;
  private String category;
  private String exposureLimit;
  private LocalDateTime createdAt;

  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getExposureLimit() { return exposureLimit; }
  public void setExposureLimit(String exposureLimit) { this.exposureLimit = exposureLimit; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
