package com.occuspec.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * 大模型网关深模块：小接口隐藏超时、重试、降级细节。
 * 失败不得抛穿到接口层，统一返回降级结果。
 */
public interface LlmGateway {
  /** 同步补全：prompt → 文本 + 用量。 */
  LlmResult complete(String prompt);

  /** 同步补全：支持系统提示词。 */
  LlmResult complete(String systemPrompt, String userPrompt);

  /**
   * 流式补全：增量文本与思考过程分别回调，结束时回调用量。
   *
   * @param onDelta 正文增量回调
   * @param onThinking 思考过程增量回调（可为空）
   */
  void stream(String prompt, Consumer<String> onDelta, Consumer<String> onThinking, Consumer<LlmUsage> onUsage);

  /** 文本向量化：返回与输入等长的向量列表。 */
  List<float[]> embed(List<String> texts);

  /**
   * 带工具的多轮对话补全（非流式）：返回正文、推理与工具调用请求。
   * 供 Agentic 循环逐轮调用，由调用方执行工具并回填结果。
   *
   * @param messages 完整消息历史（含此前工具结果）
   * @param tools 可用工具声明（可为空表示纯对话）
   */
  LlmToolResponse completeWithTools(List<ChatMsg> messages, List<LlmToolSpec> tools);
}
