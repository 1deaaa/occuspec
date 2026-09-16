package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 批量任务表：状态机推进。 */
@TableName("batch_tasks")
public class BatchTask {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private String type;
  private String fileUrl;
  private String status;
  private Integer total;
  private Integer success;
  private Integer fail;
  private String errorUrl;
  private String creatorId;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getFileUrl() { return fileUrl; }
  public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Integer getTotal() { return total; }
  public void setTotal(Integer total) { this.total = total; }
  public Integer getSuccess() { return success; }
  public void setSuccess(Integer success) { this.success = success; }
  public Integer getFail() { return fail; }
  public void setFail(Integer fail) { this.fail = fail; }
  public String getErrorUrl() { return errorUrl; }
  public void setErrorUrl(String errorUrl) { this.errorUrl = errorUrl; }
  public String getCreatorId() { return creatorId; }
  public void setCreatorId(String creatorId) { this.creatorId = creatorId; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
