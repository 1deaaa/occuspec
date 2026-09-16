package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.entity.ChatMessage;
import com.occuspec.entity.ChatSession;
import com.occuspec.llm.ChatMsg;
import com.occuspec.mapper.ChatMessageMapper;
import com.occuspec.mapper.ChatSessionMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话会话服务：会话与消息落库，支持历史回放与多轮上下文。
 */
@Service
public class ChatSessionService {
  private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);
  /** 参与模型上下文的最大历史消息数，避免上下文无限增长。 */
  private static final int MAX_CONTEXT_MESSAGES = 12;

  private final ChatSessionMapper sessionMapper;
  private final ChatMessageMapper messageMapper;
  private final ObjectMapper objectMapper;

  public ChatSessionService(
      ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper, ObjectMapper objectMapper) {
    this.sessionMapper = sessionMapper;
    this.messageMapper = messageMapper;
    this.objectMapper = objectMapper;
  }

  /** 创建会话，标题取首条提问摘要。 */
  @Transactional
  public ChatSession createSession(String title, String userId) {
    ChatSession session = new ChatSession();
    session.setTitle(title == null ? "" : title.length() > 200 ? title.substring(0, 200) : title);
    session.setUserId(userId == null ? "" : userId);
    sessionMapper.insert(session);
    return session;
  }

  /** 读取会话。 */
  public ChatSession getSession(long sessionId) {
    return sessionMapper.selectById(sessionId);
  }

  /** 会话列表。 */
  public List<ChatSession> listSessions(String userId) {
    var wrapper = new LambdaQueryWrapper<ChatSession>().orderByDesc(ChatSession::getUpdatedAt).last("LIMIT 50");
    if (userId != null && !userId.isBlank()) {
      wrapper.eq(ChatSession::getUserId, userId);
    }
    return sessionMapper.selectList(wrapper);
  }

  /** 会话内消息（按时间升序）。 */
  public List<ChatMessage> listMessages(long sessionId) {
    return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
        .eq(ChatMessage::getSessionId, sessionId)
        .orderByAsc(ChatMessage::getCreatedAt)
        .last("LIMIT 200"));
  }

  /** 构建模型上下文：取最近 N 条 user/assistant 消息，跳过工具消息。 */
  public List<ChatMsg> buildContext(long sessionId) {
    List<ChatMessage> all = listMessages(sessionId);
    List<ChatMsg> context = new ArrayList<>();
    for (ChatMessage m : all) {
      if ("user".equals(m.getRole())) {
        context.add(ChatMsg.user(m.getContent()));
      } else if ("assistant".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
        context.add(ChatMsg.assistant(m.getContent(), null));
      }
    }
    int from = Math.max(0, context.size() - MAX_CONTEXT_MESSAGES);
    return new ArrayList<>(context.subList(from, context.size()));
  }

  /** 保存用户消息。 */
  public void saveUserMessage(long sessionId, String content) {
    save(sessionId, "user", content, null, null, null, 0, 0, 0, 0);
  }

  /** 保存助手消息：含推理、工具调用与引用。 */
  public void saveAssistantMessage(long sessionId, ChatService.ChatResult result) {
    save(sessionId, "assistant", result.answer(), result.reasoning(),
        toJson(result.toolTraces()), toJson(result.citations()),
        result.usage().promptTokens(), result.usage().completionTokens(),
        result.usage().totalTokens(), 0);
  }

  private void save(long sessionId, String role, String content, String reasoning,
      String toolCalls, String citations, long prompt, long completion, long total, long costMs) {
    try {
      ChatMessage message = new ChatMessage();
      message.setSessionId(sessionId);
      message.setRole(role);
      message.setContent(content == null ? "" : content);
      message.setReasoning(reasoning);
      message.setToolCalls(toolCalls);
      message.setCitations(citations);
      message.setPromptTokens(prompt);
      message.setCompletionTokens(completion);
      message.setTotalTokens(total);
      message.setCostMs(costMs);
      messageMapper.insert(message);
      ChatSession session = sessionMapper.selectById(sessionId);
      if (session != null) {
        sessionMapper.updateById(session);
      }
    } catch (Exception ex) {
      // 落库失败不影响对话本身
      log.warn("对话消息落库失败 session={} err={}", sessionId, ex.getMessage());
    }
  }

  /** 序列化为 JSON 字符串。 */
  private String toJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return null;
    }
  }

  /** 会话消息转为前端视图。 */
  public List<Map<String, Object>> messageViews(long sessionId) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (ChatMessage m : listMessages(sessionId)) {
      Map<String, Object> row = new java.util.HashMap<>();
      row.put("role", m.getRole());
      row.put("content", m.getContent());
      row.put("reasoning", m.getReasoning());
      row.put("toolCalls", parseJson(m.getToolCalls()));
      row.put("citations", parseJson(m.getCitations()));
      row.put("totalTokens", m.getTotalTokens());
      row.put("createdAt", String.valueOf(m.getCreatedAt()));
      views.add(row);
    }
    return views;
  }

  private Object parseJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (Exception e) {
      return null;
    }
  }
}
