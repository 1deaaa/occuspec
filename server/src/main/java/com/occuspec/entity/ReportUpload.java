package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 报告图片上传与抽取记录。 */
@TableName("report_uploads")
public class ReportUpload {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private String fileName;
  private String mimeType;
  private Long fileSize;
  private Long personId;
  private String hazardCode;
  /** EXTRACTED/CONFIRMED/IMPORTED/FAILED。 */
  private String status;
  private String rawText;
  private String extractNote;
  private Long examId;
  private String creatorId;
  private Long costMs;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public String getMimeType() { return mimeType; }
  public void setMimeType(String mimeType) { this.mimeType = mimeType; }
  public Long getFileSize() { return fileSize; }
  public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
  public Long getPersonId() { return personId; }
  public void setPersonId(Long personId) { this.personId = personId; }
  public String getHazardCode() { return hazardCode; }
  public void setHazardCode(String hazardCode) { this.hazardCode = hazardCode; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getRawText() { return rawText; }
  public void setRawText(String rawText) { this.rawText = rawText; }
  public String getExtractNote() { return extractNote; }
  public void setExtractNote(String extractNote) { this.extractNote = extractNote; }
  public Long getExamId() { return examId; }
  public void setExamId(Long examId) { this.examId = examId; }
  public String getCreatorId() { return creatorId; }
  public void setCreatorId(String creatorId) { this.creatorId = creatorId; }
  public Long getCostMs() { return costMs; }
  public void setCostMs(Long costMs) { this.costMs = costMs; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
