package com.occuspec.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.BusinessException;
import com.occuspec.common.ErrorCode;
import com.occuspec.common.RateLimit;
import com.occuspec.entity.ChatSession;
import com.occuspec.service.ChatService;
import com.occuspec.service.ChatSessionService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话控制器：SSE 流式对话，推送推理、工具调用与正文增量。
 * 事件类型：reasoning / tool_call / tool_result / content / token_usage / done / error。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {
  private static final Logger log = LoggerFactory.getLogger(ChatController.class);
  private final ChatService chatService;
  private final ChatSessionService sessionService;
  private final ObjectMapper objectMapper;
  /** 判定专用线程池：对话与判定同为长耗时模型调用，共用同一隔离池。 */
  private final java.util.concurrent.Executor assessExecutor;

  public ChatController(
      ChatService chatService, ChatSessionService sessionService, ObjectMapper objectMapper,
      @org.springframework.beans.factory.annotation.Qualifier("assessExecutor")
          java.util.concurrent.Executor assessExecutor) {
    this.chatService = chatService;
    this.sessionService = sessionService;
    this.objectMapper = objectMapper;
    this.assessExecutor = assessExecutor;
  }

  /** 会话列表。 */
  @GetMapping("/sessions")
  public ApiResponse<Map<String, Object>> sessions() {
    String userId = StpUtil.isLogin() ? String.valueOf(StpUtil.getLoginId()) : "";
    List<ChatSession> sessions = sessionService.listSessions(userId);
    return ApiResponse.ok(Map.of("data", sessions.stream().map(s -> Map.of(
        "sessionId", s.getId(),
        "title", s.getTitle() == null ? "" : s.getTitle(),
        "updatedAt", String.valueOf(s.getUpdatedAt()))).toList()));
  }

  /** 会话消息历史。 */
  @GetMapping("/sessions/{id}/messages")
  public ApiResponse<Map<String, Object>> messages(@PathVariable long id) {
    return ApiResponse.ok(Map.of("data", sessionService.messageViews(id)));
  }

  /** 新建会话。 */
  @PostMapping("/sessions")
  public ApiResponse<Map<String, Object>> createSession(@RequestBody Map<String, Object> body) {
    String title = body.get("title") == null ? "新会话" : String.valueOf(body.get("title"));
    String userId = StpUtil.isLogin() ? String.valueOf(StpUtil.getLoginId()) : "";
    ChatSession session = sessionService.createSession(title, userId);
    return ApiResponse.ok(Map.of("sessionId", session.getId(), "title", session.getTitle()));
  }

  /**
   * 流式对话：不传 sessionId 时自动建会话。
   * 流程：保存用户消息 → Agentic 循环（模型自主调工具）→ 流式推送 → 落库。
   */
  @PostMapping("/stream")
  @RateLimit(windowSeconds = 60, maxCount = 30)
  public SseEmitter stream(@RequestBody Map<String, Object> body) {
    String input = body.get("message") == null ? "" : String.valueOf(body.get("message"));
    if (input.isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "消息不能为空");
    }
    String userId = StpUtil.isLogin() ? String.valueOf(StpUtil.getLoginId()) : "";
    long sessionId;
    if (body.get("sessionId") != null) {
      sessionId = Long.parseLong(String.valueOf(body.get("sessionId")));
      if (sessionService.getSession(sessionId) == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
      }
    } else {
      sessionId = sessionService.createSession(truncate(input, 60), userId).getId();
    }
    final long sid = sessionId;
    // 超时 15 分钟：对话实测数十秒至数分钟，留足余量
    SseEmitter emitter = new SseEmitter(900_000L);
    assessExecutor.execute(() -> {
      try {
        sessionService.saveUserMessage(sid, input);
        send(emitter, "session", Map.of("sessionId", sid));
        var history = sessionService.buildContext(sid);
        var result = chatService.chat(history, input, new ChatService.ChatProgress() {
          @Override
          public void onReasoning(String text) {
            send(emitter, "reasoning", Map.of("text", text));
          }

          @Override
          public void onToolCall(String tool, Map<String, Object> args) {
            send(emitter, "tool_call", Map.of("tool", tool, "args", args));
          }

          @Override
          public void onToolResult(String tool, int hits, String note, List<ChatService.Citation> citations) {
            send(emitter, "tool_result", Map.of("tool", tool, "hits", hits, "note", note,
                "citations", citations));
          }

          @Override
          public void onContent(String delta) {
            send(emitter, "content", Map.of("delta", delta));
          }
        });
        sessionService.saveAssistantMessage(sid, result);
        send(emitter, "token_usage", Map.of(
            "prompt", result.usage().promptTokens(),
            "completion", result.usage().completionTokens(),
            "total", result.usage().totalTokens()));
        send(emitter, "done", Map.of(
            "sessionId", sid,
            "citations", result.citations(),
            "toolTraces", result.toolTraces(),
            "rounds", result.rounds()));
        emitter.complete();
      } catch (Exception ex) {
        log.warn("对话失败 session={} err={}", sid, ex.getMessage());
        send(emitter, "error", Map.of("code", "CHAT_FAILED", "message", "对话失败：" + ex.getMessage()));
        emitter.completeWithError(ex);
      }
    });
    return emitter;
  }

  private void send(SseEmitter emitter, String event, Object data) {
    try {
      emitter.send(SseEmitter.event().name(event)
          .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
    } catch (Exception ex) {
      log.debug("SSE 推送跳过 event={}", event);
    }
  }

  private String truncate(String text, int max) {
    return text.length() > max ? text.substring(0, max) : text;
  }
}
