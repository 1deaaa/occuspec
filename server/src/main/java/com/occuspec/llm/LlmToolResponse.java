package com.occuspec.llm;

import java.util.List;

/** 工具调用轮次结果：正文、推理、工具调用与用量。 */
public record LlmToolResponse(
    String content, String reasoning, List<LlmToolCall> toolCalls, LlmUsage usage, boolean degraded) {

  public static LlmToolResponse degraded(String fallback) {
    return new LlmToolResponse(fallback == null ? "" : fallback, null, List.of(), LlmUsage.empty(), true);
  }

  /** 是否需要继续执行工具。 */
  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }
}
