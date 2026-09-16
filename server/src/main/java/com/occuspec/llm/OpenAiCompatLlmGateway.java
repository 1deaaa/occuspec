package com.occuspec.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 兼容模式网关实现：用 JDK 原生 HTTP 客户端直调 OpenAI 兼容接口。
 * 超时 60 秒、重试 2 次指数退避，失败返回降级结果。
 * 推理强度经 extra_body 透传 reasoning_effort 参数。
 */
@Component
public class OpenAiCompatLlmGateway implements LlmGateway {
  private static final Logger log = LoggerFactory.getLogger(OpenAiCompatLlmGateway.class);

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final String reasoningEffort;
  private final String embeddingBaseUrl;
  private final String embeddingApiKey;
  private final String embeddingModel;
  private final int embeddingDimensions;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public OpenAiCompatLlmGateway(
      @Value("${occuspec.llm.base-url:}") String baseUrl,
      @Value("${occuspec.llm.api-key:}") String apiKey,
      @Value("${occuspec.llm.model:qwen3.8-flash}") String model,
      @Value("${occuspec.llm.reasoning-effort:xhigh}") String reasoningEffort,
      @Value("${occuspec.embedding.base-url:}") String embeddingBaseUrl,
      @Value("${occuspec.embedding.api-key:}") String embeddingApiKey,
      @Value("${occuspec.embedding.model:Qwen/Qwen3-Embedding-8B}") String embeddingModel,
      @Value("${occuspec.embedding.dimensions:1024}") int embeddingDimensions,
      ObjectMapper objectMapper) {
    this.baseUrl = trimSlash(baseUrl);
    this.apiKey = apiKey;
    this.model = model;
    this.reasoningEffort = reasoningEffort;
    this.embeddingBaseUrl = trimSlash(embeddingBaseUrl);
    this.embeddingApiKey = embeddingApiKey;
    this.embeddingModel = embeddingModel;
    this.embeddingDimensions = embeddingDimensions;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  @Override
  public LlmResult complete(String prompt) {
    return complete(null, prompt);
  }

  @Override
  public LlmResult complete(String systemPrompt, String userPrompt) {
    try {
      ObjectNode body = chatBody(systemPrompt, userPrompt, false);
      String resp = postWithRetry(baseUrl + "/chat/completions", apiKey, body, 2);
      return parseChatResponse(resp);
    } catch (Exception ex) {
      log.warn("模型调用失败已降级 err={}", ex.getMessage());
      return LlmResult.degraded("");
    }
  }

  @Override
  public void stream(
      String prompt, Consumer<String> onDelta, Consumer<String> onThinking, Consumer<LlmUsage> onUsage) {
    stream(null, prompt, onDelta, onThinking, onUsage);
  }

  /** 带系统提示词的流式补全。 */
  public void stream(
      String systemPrompt,
      String prompt,
      Consumer<String> onDelta,
      Consumer<String> onThinking,
      Consumer<LlmUsage> onUsage) {
    try {
      ObjectNode body = chatBody(systemPrompt, prompt, true);
      // 请求流式用量的统计回传
      ObjectNode streamOptions = objectMapper.createObjectNode();
      streamOptions.put("include_usage", true);
      body.set("stream_options", streamOptions);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
              .timeout(Duration.ofSeconds(120))
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
              .build();
      HttpResponse<java.io.InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      StringBuilder thinkingBuf = new StringBuilder();
      StringBuilder textBuf = new StringBuilder();
      LlmUsage usage = LlmUsage.empty();
      try (var reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty() || !line.startsWith("data:")) {
            continue;
          }
          String payload = line.substring(5).trim();
          if ("[DONE]".equals(payload)) {
            break;
          }
          try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
              JsonNode delta = choices.get(0).path("delta");
              String reasoning = textOf(delta, "reasoning_content");
              if (reasoning == null) {
                reasoning = textOf(delta, "reasoning");
              }
              if (reasoning != null && !reasoning.isEmpty()) {
                thinkingBuf.append(reasoning);
                if (onThinking != null) {
                  onThinking.accept(reasoning);
                }
              }
              String content = textOf(delta, "content");
              if (content != null && !content.isEmpty()) {
                textBuf.append(content);
                onDelta.accept(content);
              }
            }
            JsonNode usageNode = node.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
              usage = toUsage(usageNode);
            }
          } catch (Exception parseEx) {
            log.debug("流式分片解析跳过 err={}", parseEx.getMessage());
          }
        }
      }
      onUsage.accept(usage);
    } catch (Exception ex) {
      log.warn("模型流式调用失败 err={}", ex.getMessage());
      onUsage.accept(LlmUsage.empty());
    }
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    List<float[]> result = new ArrayList<>();
    if (texts == null || texts.isEmpty()) {
      return result;
    }
    try {
      ObjectNode body = objectMapper.createObjectNode();
      body.put("model", embeddingModel);
      ArrayNode input = objectMapper.createArrayNode();
      texts.forEach(input::add);
      body.set("input", input);
      if (embeddingDimensions > 0) {
        body.put("dimensions", embeddingDimensions);
      }
      String resp = postWithRetry(embeddingBaseUrl + "/embeddings", embeddingApiKey, body, 2);
      JsonNode root = objectMapper.readTree(resp);
      for (JsonNode item : root.path("data")) {
        JsonNode vec = item.path("embedding");
        float[] arr = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
          arr[i] = (float) vec.get(i).asDouble();
        }
        result.add(arr);
      }
    } catch (Exception ex) {
      log.warn("向量调用失败 err={}", ex.getMessage());
    }
    return result;
  }

  @Override
  public LlmToolResponse completeWithTools(List<ChatMsg> messages, List<LlmToolSpec> tools) {
    try {
      String resp = postWithRetry(baseUrl + "/chat/completions", apiKey,
          toolBody(messages, tools, false), 2);
      return parseToolResponse(resp);
    } catch (Exception ex) {
      log.warn("工具对话调用失败已降级 err={}", ex.getMessage());
      return LlmToolResponse.degraded("");
    }
  }

  /**
   * 带工具的流式补全：正文与推理逐增量回调，工具调用分片累积后整体返回。
   *
   * <p>tool_calls 分片规则：首片含 index/id/function.name，后续片只含 function.arguments 增量，
   * 需按 index 归并拼接。正文与推理可直接推送，实现"边生成边显示"。
   */
  @Override
  public LlmToolResponse streamWithTools(
      List<ChatMsg> messages, List<LlmToolSpec> tools,
      Consumer<String> onContent, Consumer<String> onReasoning) {
    try {
      ObjectNode body = toolBody(messages, tools, true);
      ObjectNode streamOptions = objectMapper.createObjectNode();
      streamOptions.put("include_usage", true);
      body.set("stream_options", streamOptions);

      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
              .timeout(Duration.ofSeconds(300))
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
              .build();
      HttpResponse<java.io.InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

      StringBuilder content = new StringBuilder();
      StringBuilder reasoning = new StringBuilder();
      // 工具调用分片：index → 累积器
      Map<Integer, ToolCallAccumulator> callAcc = new LinkedHashMap<>();
      LlmUsage usage = LlmUsage.empty();
      try (var reader = new java.io.BufferedReader(
          new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty() || !line.startsWith("data:")) {
            continue;
          }
          String payload = line.substring(5).trim();
          if ("[DONE]".equals(payload)) {
            break;
          }
          try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode usageNode = node.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
              usage = toUsage(usageNode);
            }
            JsonNode choices = node.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
              continue;
            }
            JsonNode delta = choices.get(0).path("delta");
            String reasoningDelta = textOf(delta, "reasoning_content");
            if (reasoningDelta == null) {
              reasoningDelta = textOf(delta, "reasoning");
            }
            if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
              reasoning.append(reasoningDelta);
              if (onReasoning != null) {
                onReasoning.accept(reasoningDelta);
              }
            }
            String contentDelta = textOf(delta, "content");
            if (contentDelta != null && !contentDelta.isEmpty()) {
              content.append(contentDelta);
              if (onContent != null) {
                onContent.accept(contentDelta);
              }
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
              for (JsonNode tc : toolCalls) {
                int index = tc.path("index").asInt(0);
                ToolCallAccumulator acc = callAcc.computeIfAbsent(index, k -> new ToolCallAccumulator());
                String id = textOf(tc, "id");
                if (id != null) {
                  acc.id = id;
                }
                JsonNode fn = tc.path("function");
                String name = textOf(fn, "name");
                if (name != null) {
                  acc.name = name;
                }
                String args = textOf(fn, "arguments");
                if (args != null) {
                  acc.arguments.append(args);
                }
              }
            }
          } catch (Exception parseEx) {
            log.debug("流式工具分片解析跳过 err={}", parseEx.getMessage());
          }
        }
      }
      List<LlmToolCall> calls = new ArrayList<>();
      for (ToolCallAccumulator acc : callAcc.values()) {
        if (acc.name != null && !acc.name.isBlank()) {
          calls.add(new LlmToolCall(
              acc.id == null ? "" : acc.id, acc.name,
              acc.arguments.length() == 0 ? "{}" : acc.arguments.toString()));
        }
      }
      return new LlmToolResponse(content.toString(), reasoning.toString(), calls, usage, false);
    } catch (Exception ex) {
      log.warn("流式工具调用失败 err={}", ex.getMessage());
      return LlmToolResponse.degraded("");
    }
  }

  /** 工具调用分片累积器：arguments 跨片拼接。 */
  private static final class ToolCallAccumulator {
    private String id;
    private String name;
    private final StringBuilder arguments = new StringBuilder();
  }

  /** 构造带工具声明的请求体（流式与非流式共用）。 */
  private ObjectNode toolBody(List<ChatMsg> messages, List<LlmToolSpec> tools, boolean stream) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", model);
    if (stream) {
      body.put("stream", true);
    }
    ArrayNode msgs = objectMapper.createArrayNode();
    for (ChatMsg m : messages) {
      msgs.add(toWireMessage(m));
    }
    body.set("messages", msgs);
    if (tools != null && !tools.isEmpty()) {
      ArrayNode toolArray = objectMapper.createArrayNode();
      for (LlmToolSpec spec : tools) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = objectMapper.createObjectNode();
        fn.put("name", spec.name());
        fn.put("description", spec.description());
        fn.set("parameters", objectMapper.valueToTree(spec.parameters()));
        tool.set("function", fn);
        toolArray.add(tool);
      }
      body.set("tools", toolArray);
      body.put("tool_choice", "auto");
    }
    if (reasoningEffort != null && !reasoningEffort.isBlank()) {
      ObjectNode extra = objectMapper.createObjectNode();
      extra.put("reasoning_effort", reasoningEffort);
      extra.put("enable_thinking", true);
      body.set("extra_body", extra);
      body.put("reasoning_effort", reasoningEffort);
    }
    return body;
  }

  @Override
  public LlmResult completeWithImages(String systemPrompt, String userPrompt, List<LlmImage> images) {
    try {
      ObjectNode body = objectMapper.createObjectNode();
      body.put("model", model);
      ArrayNode messages = objectMapper.createArrayNode();
      if (systemPrompt != null && !systemPrompt.isBlank()) {
        messages.add(msg("system", systemPrompt));
      }
      // 多模态消息：content 为数组，依次为文本与图片
      ObjectNode userMessage = objectMapper.createObjectNode();
      userMessage.put("role", "user");
      ArrayNode content = objectMapper.createArrayNode();
      ObjectNode textPart = objectMapper.createObjectNode();
      textPart.put("type", "text");
      textPart.put("text", userPrompt == null ? "" : userPrompt);
      content.add(textPart);
      if (images != null) {
        for (LlmImage image : images) {
          if (image == null || image.base64() == null || image.base64().isBlank()) {
            continue;
          }
          ObjectNode imagePart = objectMapper.createObjectNode();
          imagePart.put("type", "image_url");
          ObjectNode imageUrl = objectMapper.createObjectNode();
          imageUrl.put("url", image.toDataUrl());
          imagePart.set("image_url", imageUrl);
          content.add(imagePart);
        }
      }
      userMessage.set("content", content);
      messages.add(userMessage);
      body.set("messages", messages);
      String resp = postWithRetry(baseUrl + "/chat/completions", apiKey, body, 2);
      return parseChatResponse(resp);
    } catch (Exception ex) {
      log.warn("多模态调用失败已降级 err={}", ex.getMessage());
      return LlmResult.degraded("");
    }
  }

  /** 构造上游消息：assistant 工具调用与 tool 结果需按协议回填。 */
  private ObjectNode toWireMessage(ChatMsg m) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("role", m.role());
    if ("tool".equals(m.role())) {
      node.put("tool_call_id", m.toolCallId() == null ? "" : m.toolCallId());
      node.put("content", m.content() == null ? "" : m.content());
      return node;
    }
    if (m.toolCallsJson() != null && !m.toolCallsJson().isBlank()) {
      node.putNull("content");
      try {
        node.set("tool_calls", objectMapper.readTree(m.toolCallsJson()));
      } catch (Exception e) {
        node.put("content", m.content() == null ? "" : m.content());
      }
      return node;
    }
    node.put("content", m.content() == null ? "" : m.content());
    return node;
  }

  /** 解析带工具调用的响应。 */
  private LlmToolResponse parseToolResponse(String resp) throws Exception {
    JsonNode root = objectMapper.readTree(resp);
    JsonNode message = root.path("choices").path(0).path("message");
    String content = textOf(message, "content");
    String thinking = textOf(message, "reasoning_content");
    if (thinking == null) {
      thinking = textOf(message, "reasoning");
    }
    List<LlmToolCall> calls = new ArrayList<>();
    JsonNode toolCalls = message.path("tool_calls");
    if (toolCalls.isArray()) {
      for (JsonNode tc : toolCalls) {
        calls.add(new LlmToolCall(
            tc.path("id").asText(""),
            tc.path("function").path("name").asText(""),
            tc.path("function").path("arguments").asText("{}")));
      }
    }
    return new LlmToolResponse(content == null ? "" : content, thinking, calls,
        toUsage(root.path("usage")), false);
  }

  private ObjectNode chatBody(String systemPrompt, String userPrompt, boolean stream) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", model);
    body.put("stream", stream);
    ArrayNode messages = objectMapper.createArrayNode();
    if (systemPrompt != null && !systemPrompt.isBlank()) {
      messages.add(msg("system", systemPrompt));
    }
    messages.add(msg("user", userPrompt));
    body.set("messages", messages);
    if (reasoningEffort != null && !reasoningEffort.isBlank()) {
      ObjectNode extra = objectMapper.createObjectNode();
      extra.put("reasoning_effort", reasoningEffort);
      extra.put("enable_thinking", true);
      body.set("extra_body", extra);
      body.put("reasoning_effort", reasoningEffort);
    }
    return body;
  }

  private ObjectNode msg(String role, String content) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("role", role);
    node.put("content", content);
    return node;
  }

  private String postWithRetry(String url, String key, ObjectNode body, int retries) throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt <= retries; attempt++) {
      try {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          return response.body();
        }
        last = new IllegalStateException("上游状态码 " + response.statusCode());
      } catch (Exception ex) {
        last = ex;
      }
      if (attempt < retries) {
        Thread.sleep(500L * (attempt + 1));
      }
    }
    throw last == null ? new IllegalStateException("模型调用失败") : last;
  }

  private LlmResult parseChatResponse(String resp) throws Exception {
    JsonNode root = objectMapper.readTree(resp);
    JsonNode message = root.path("choices").path(0).path("message");
    String content = textOf(message, "content");
    String thinking = textOf(message, "reasoning_content");
    if (thinking == null) {
      thinking = textOf(message, "reasoning");
    }
    LlmUsage usage = toUsage(root.path("usage"));
    return new LlmResult(content == null ? "" : content, usage, false, thinking);
  }

  private LlmUsage toUsage(JsonNode usageNode) {
    if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
      return LlmUsage.empty();
    }
    return new LlmUsage(
        usageNode.path("prompt_tokens").asLong(0),
        usageNode.path("completion_tokens").asLong(0),
        usageNode.path("total_tokens").asLong(0));
  }

  private String textOf(JsonNode node, String field) {
    if (node == null || node.isMissingNode()) {
      return null;
    }
    JsonNode child = node.path(field);
    if (child.isMissingNode() || child.isNull()) {
      return null;
    }
    return child.asText();
  }

  private String trimSlash(String url) {
    if (url == null) {
      return "";
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
