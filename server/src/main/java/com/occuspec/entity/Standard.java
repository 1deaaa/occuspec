package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 标准主表。 */
@TableName("standards")
public class Standard {
  @TableId(type = IdType.INPUT)
  private String code;
  private String name;
  private String version;
  private LocalDate publishDate;
  private LocalDate effectiveDate;
  private String status;
  private LocalDateTime createdAt;

  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public LocalDate getPublishDate() { return publishDate; }
  public void setPublishDate(LocalDate publishDate) { this.publishDate = publishDate; }
  public LocalDate getEffectiveDate() { return effectiveDate; }
  public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
