package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 审计日志表：只追加不修改。 */
@TableName("audits")
public class Audit {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private String bizType;
  private String bizId;
  private String actorId;
  private String action;
  private String diff;
  private String traceId;
  private Long costMs;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getBizType() { return bizType; }
  public void setBizType(String bizType) { this.bizType = bizType; }
  public String getBizId() { return bizId; }
  public void setBizId(String bizId) { this.bizId = bizId; }
  public String getActorId() { return actorId; }
  public void setActorId(String actorId) { this.actorId = actorId; }
  public String getAction() { return action; }
  public void setAction(String action) { this.action = action; }
  public String getDiff() { return diff; }
  public void setDiff(String diff) { this.diff = diff; }
  public String getTraceId() { return traceId; }
  public void setTraceId(String traceId) { this.traceId = traceId; }
  public Long getCostMs() { return costMs; }
  public void setCostMs(Long costMs) { this.costMs = costMs; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
