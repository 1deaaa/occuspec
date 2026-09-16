package com.occuspec.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.common.RateLimit;
import com.occuspec.dto.AssessRequest;
import com.occuspec.llm.LlmUsage;
import com.occuspec.service.AssessService;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式判定控制器：SSE 事件流 reasoning/tool_call/tool_result/content/token_usage/done/error。
 * 前端用 fetch 流式读取，展示可折叠推理过程、工具调用与用量。
 */
@RestController
public class AssessStreamController {
  private static final Logger log = LoggerFactory.getLogger(AssessStreamController.class);
  private final AssessService assessService;
  private final ObjectMapper objectMapper;
  /** 判定专用线程池：流式判定异步执行，避免阻塞 Servlet 线程。 */
  private final java.util.concurrent.Executor assessExecutor;

  public AssessStreamController(
      AssessService assessService, ObjectMapper objectMapper,
      @Qualifier("assessExecutor") java.util.concurrent.Executor assessExecutor) {
    this.assessService = assessService;
    this.objectMapper = objectMapper;
    this.assessExecutor = assessExecutor;
  }

  /**
   * 流式判定：异步执行 Agent 判定，推理与工具事件边产生边推送。
   *
   * <p>必须异步：若在返回 emitter 前同步跑完判定，所有事件会在方法返回后才被
   * 容器刷新，前端表现为"内容一次性全部出现"，失去流式意义。故提交到判定线程池执行，
   * emitter 立即返回，事件随生成实时下发。
   *
   * <p>超时设 15 分钟：Agent 多轮检索实测 3–8 分钟，留足余量。
   */
  @PostMapping("/assessments/stream")
  @RateLimit(windowSeconds = 60, maxCount = 20)
  public SseEmitter stream(@RequestBody AssessRequest request) {
    SseEmitter emitter = new SseEmitter(900_000L);
    assessExecutor.execute(() -> {
      try {
        var view = assessService.assess(request.examId(), request.ruleVersion(),
            new AssessService.ProgressListener() {
              @Override
              public void onReasoning(String text) {
                send(emitter, "reasoning", Map.of("text", text));
              }

              @Override
              public void onToolCall(String tool, Map<String, Object> args) {
                Map<String, Object> data = new HashMap<>();
                data.put("tool", tool);
                data.put("args", args);
                send(emitter, "tool_call", data);
              }

              @Override
              public void onToolResult(String tool, int hits, String note) {
                send(emitter, "tool_result", Map.of("tool", tool, "hits", hits, "note", note));
              }
            });
        String assessmentId = String.valueOf(view.assessmentId());
        send(emitter, "content", Map.of("assessmentId", assessmentId,
            "delta", "建议结论：" + view.conclusionLabel() + "（" + view.conclusionSource()
                + "）。本结论为建议性质，须经主检医师复核。"));
        send(emitter, "token_usage", Map.of("assessmentId", assessmentId,
            "prompt", view.promptTokens(), "completion", view.completionTokens(),
            "total", view.totalTokens()));
        Map<String, Object> done = new HashMap<>();
        done.put("assessmentId", view.assessmentId());
        done.put("conclusion", view.conclusion());
        done.put("conclusionLabel", view.conclusionLabel());
        done.put("evidences", view.evidences());
        done.put("recommendations", view.recommendations());
        // Agent 决策元信息：轮次、是否被规则下限拦截、模型提交的依据
        done.put("agentRounds", view.agentRounds());
        done.put("floorApplied", view.floorApplied());
        done.put("rationale", view.rationale() == null ? "" : view.rationale());
        send(emitter, "done", done);
        emitter.complete();
      } catch (Exception ex) {
        log.warn("流式判定失败 examId={} err={}", request.examId(), ex.getMessage());
        send(emitter, "error", Map.of("code", "ASSESS_FAILED", "message", "判定失败：" + ex.getMessage()));
        emitter.completeWithError(ex);
      }
    });
    return emitter;
  }

  private void send(SseEmitter emitter, String event, Object data) {
    try {
      String json = objectMapper.writeValueAsString(data);
      emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
    } catch (Exception ex) {
      log.debug("SSE 推送跳过 event={}", event);
    }
  }
}
