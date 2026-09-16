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
import java.util.List;
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
