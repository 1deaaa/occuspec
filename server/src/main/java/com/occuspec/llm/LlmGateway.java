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
   * 带图片的多模态补全：文本提示 + 若干图片 → 文本。
   * 供报告图片结构化抽取使用；失败返回降级结果，不抛穿。
   *
   * @param systemPrompt 系统提示词（可空）
   * @param userPrompt 文本提示词
   * @param images 图片列表（data URL 或公网 URL）
   */
  default LlmResult completeWithImages(String systemPrompt, String userPrompt, List<LlmImage> images) {
    return LlmResult.degraded("");
  }

  /**
   * 带工具的多轮对话补全（非流式）：返回正文、推理与工具调用请求。
   * 供 Agentic 循环逐轮调用，由调用方执行工具并回填结果。
   *
   * @param messages 完整消息历史（含此前工具结果）
   * @param tools 可用工具声明（可为空表示纯对话）
   */
  LlmToolResponse completeWithTools(List<ChatMsg> messages, List<LlmToolSpec> tools);

  /**
   * 带工具的流式补全：正文与推理逐增量回调，工具调用在整轮结束后整体返回。
   *
   * <p>为什么工具调用不能流式：OpenAI 兼容协议的 tool_calls 是分片累积的
   * （id/name 只在首片出现，arguments 跨多片拼接），必须等整轮结束才能拿到可执行的参数。
   * 但同一轮里的正文与推理是连续的 token 流，可以实时推送——这正是"边想边显示"的来源。
   *
   * @param onContent 正文增量回调（可空）
   * @param onReasoning 推理增量回调（可空）
   * @return 聚合后的整轮结果（正文、推理、工具调用、用量）
   */
  default LlmToolResponse streamWithTools(
      List<ChatMsg> messages, List<LlmToolSpec> tools,
      Consumer<String> onContent, Consumer<String> onReasoning) {
    return completeWithTools(messages, tools);
  }
}
