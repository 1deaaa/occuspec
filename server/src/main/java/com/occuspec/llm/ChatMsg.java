package com.occuspec.llm;

/** 对话消息：支持工具调用与工具结果回填。 */
public record ChatMsg(String role, String content, String toolCallId, String toolName, String toolCallsJson) {
  /** 系统消息。 */
  public static ChatMsg system(String content) {
    return new ChatMsg("system", content, null, null, null);
  }

  /** 用户消息。 */
  public static ChatMsg user(String content) {
    return new ChatMsg("user", content, null, null, null);
  }

  /** 助手消息（可能含工具调用）。 */
  public static ChatMsg assistant(String content, String toolCallsJson) {
    return new ChatMsg("assistant", content == null ? "" : content, null, null, toolCallsJson);
  }

  /** 工具结果消息。 */
  public static ChatMsg tool(String toolCallId, String content) {
    return new ChatMsg("tool", content, toolCallId, null, null);
  }
}
