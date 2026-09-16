package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 对话消息表：记录推理、工具调用与用量。 */
@TableName("chat_messages")
public class ChatMessage {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private Long sessionId;
  private String role;
  private String content;
  private String reasoning;
  private String toolCalls;
  private String citations;
  private Long promptTokens;
  private Long completionTokens;
  private Long totalTokens;
  private Long costMs;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getSessionId() { return sessionId; }
  public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  public String getReasoning() { return reasoning; }
  public void setReasoning(String reasoning) { this.reasoning = reasoning; }
  public String getToolCalls() { return toolCalls; }
  public void setToolCalls(String toolCalls) { this.toolCalls = toolCalls; }
  public String getCitations() { return citations; }
  public void setCitations(String citations) { this.citations = citations; }
  public Long getPromptTokens() { return promptTokens; }
  public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
  public Long getCompletionTokens() { return completionTokens; }
  public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
  public Long getTotalTokens() { return totalTokens; }
  public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }
  public Long getCostMs() { return costMs; }
  public void setCostMs(Long costMs) { this.costMs = costMs; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
