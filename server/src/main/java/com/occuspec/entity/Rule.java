package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 判定规则表。 */
@TableName("rules")
public class Rule {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private String code;
  private String name;
  private String hazardCode;
  private String expression;
  private String conclusion;
  private Integer weight;
  private Boolean enabled;
  private String version;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getExpression() { return expression; }
  public void setExpression(String expression) { this.expression = expression; }
  public String getConclusion() { return conclusion; }
  public void setConclusion(String conclusion) { this.conclusion = conclusion; }
  public Integer getWeight() { return weight; }
  public void setWeight(Integer weight) { this.weight = weight; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
