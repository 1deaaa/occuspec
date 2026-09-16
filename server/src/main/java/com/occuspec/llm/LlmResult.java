package com.occuspec.llm;

/** 模型调用结果：文本、用量与是否降级。 */
public record LlmResult(String text, LlmUsage usage, boolean degraded, String thinking) {
  public static LlmResult ok(String text, LlmUsage usage) {
    return new LlmResult(text, usage, false, null);
  }

  public static LlmResult ok(String text, LlmUsage usage, String thinking) {
    return new LlmResult(text, usage, false, thinking);
  }

  public static LlmResult degraded(String fallbackText) {
    return new LlmResult(fallbackText, LlmUsage.empty(), true, null);
  }
}
