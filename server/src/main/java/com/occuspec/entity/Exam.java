package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 体检记录表。 */
@TableName("exams")
public class Exam {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private Long personId;
  private String hazardCode;
  private LocalDate examDate;
  private String operatorId;
  private String status;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getPersonId() { return personId; }
  public void setPersonId(Long personId) { this.personId = personId; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public LocalDate getExamDate() { return examDate; }
  public void setExamDate(LocalDate examDate) { this.examDate = examDate; }
  public String getOperatorId() { return operatorId; }
  public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
