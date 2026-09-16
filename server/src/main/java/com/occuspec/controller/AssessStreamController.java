package com.occuspec.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.common.RateLimit;
import com.occuspec.dto.AssessRequest;
import com.occuspec.llm.LlmUsage;
import com.occuspec.service.AssessService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  public AssessStreamController(AssessService assessService, ObjectMapper objectMapper) {
    this.assessService = assessService;
    this.objectMapper = objectMapper;
  }

  /** 流式判定：先推送检索推理事件，再同步执行判定，最后推送结论与用量。 */
  @PostMapping("/assessments/stream")
  @RateLimit(windowSeconds = 60, maxCount = 20)
  public SseEmitter stream(@RequestBody AssessRequest request) {
    SseEmitter emitter = new SseEmitter(180_000L);
    AtomicLong prompt = new AtomicLong();
    AtomicLong completion = new AtomicLong();
    AtomicLong total = new AtomicLong();
    AtomicReference<String> assessmentId = new AtomicReference<>("");
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
      assessmentId.set(String.valueOf(view.assessmentId()));
      prompt.set(view.promptTokens());
      completion.set(view.completionTokens());
      total.set(view.totalTokens());
      // 结论正文：依据渲染文本已在判定内流式产生，此处推送结论摘要
      send(emitter, "content", Map.of("assessmentId", assessmentId.get(),
          "delta", "建议结论：" + view.conclusionLabel() + "（" + view.conclusionSource() + "）。本结论为建议性质，须经主检医师复核。"));
      send(emitter, "token_usage", Map.of("assessmentId", assessmentId.get(),
          "prompt", prompt.get(), "completion", completion.get(), "total", total.get()));
      Map<String, Object> done = new HashMap<>();
      done.put("assessmentId", view.assessmentId());
      done.put("conclusion", view.conclusion());
      done.put("conclusionLabel", view.conclusionLabel());
      done.put("evidences", view.evidences());
      done.put("recommendations", view.recommendations());
      send(emitter, "done", done);
      emitter.complete();
    } catch (Exception ex) {
      log.warn("流式判定失败 examId={} err={}", request.examId(), ex.getMessage());
      send(emitter, "error", Map.of("code", "ASSESS_FAILED", "message", "判定失败：" + ex.getMessage()));
      emitter.completeWithError(ex);
    }
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
