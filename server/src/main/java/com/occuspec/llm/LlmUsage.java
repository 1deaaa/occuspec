package com.occuspec.llm;

/** 模型用量统计：上游实际返回的 token 数。 */
public record LlmUsage(long promptTokens, long completionTokens, long totalTokens) {
  public static LlmUsage empty() {
    return new LlmUsage(0, 0, 0);
  }

  public LlmUsage add(LlmUsage other) {
    if (other == null) {
      return this;
    }
    return new LlmUsage(
        promptTokens + other.promptTokens(),
        completionTokens + other.completionTokens(),
        totalTokens + other.totalTokens());
  }
}
