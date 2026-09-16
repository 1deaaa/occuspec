package com.occuspec.llm;

/** 模型请求的工具调用。 */
public record LlmToolCall(String id, String name, String arguments) {}
