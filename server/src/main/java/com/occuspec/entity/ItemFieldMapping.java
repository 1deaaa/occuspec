package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 检查项字段别名映射：外部字段名 → 规范 fact 编码。 */
@TableName("item_field_mapping")
public class ItemFieldMapping {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  /** 外部字段别名（比较前统一小写去空白）。 */
  private String alias;
  /** 规范 fact 编码。 */
  private String canonicalCode;
  /** 规范中文名。 */
  private String canonicalName;
  private String unit;
  /** 来源：SEED/MANUAL/REPORT。 */
  private String source;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getAlias() { return alias; }
  public void setAlias(String alias) { this.alias = alias; }
  public String getCanonicalCode() { return canonicalCode; }
  public void setCanonicalCode(String canonicalCode) { this.canonicalCode = canonicalCode; }
  public String getCanonicalName() { return canonicalName; }
  public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }
  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
