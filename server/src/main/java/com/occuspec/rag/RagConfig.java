package com.occuspec.rag;

import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型装配：对话流式模型与向量模型均走 OpenAI 兼容接口。
 * 框架只做调用与向量存取，检索策略、过滤条件、提示词拼装全部由本项目定制。
 */
@Configuration
public class RagConfig {
  private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

  @Bean
  public OpenAiStreamingChatModel chatModel(
      @Value("${occuspec.llm.base-url:}") String baseUrl,
      @Value("${occuspec.llm.api-key:}") String apiKey,
      @Value("${occuspec.llm.model:qwen3.8-flash}") String model,
      @Value("${occuspec.llm.reasoning-effort:xhigh}") String reasoningEffort) {
    if (apiKey == null || apiKey.isBlank()) {
      log.warn("未配置对话模型密钥，判定将降级为纯规则模式");
    }
    return OpenAiStreamingChatModel.builder()
        .baseUrl(trimSlash(baseUrl))
        .apiKey(apiKey == null ? "" : apiKey)
        .modelName(model)
        .reasoningEffort(reasoningEffort)
        .returnThinking(true)
        .timeout(Duration.ofSeconds(120))
        .build();
  }

  @Bean
  public OpenAiEmbeddingModel embeddingModel(
      @Value("${occuspec.embedding.base-url:}") String baseUrl,
      @Value("${occuspec.embedding.api-key:}") String apiKey,
      @Value("${occuspec.embedding.model:Qwen/Qwen3-Embedding-8B}") String model,
      @Value("${occuspec.embedding.dimensions:1024}") int dimensions) {
    return OpenAiEmbeddingModel.builder()
        .baseUrl(trimSlash(baseUrl))
        .apiKey(apiKey == null ? "" : apiKey)
        .modelName(model)
        .dimensions(dimensions)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(2)
        .maxSegmentsPerBatch(16)
        .build();
  }

  private String trimSlash(String url) {
    if (url == null) {
      return "";
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
