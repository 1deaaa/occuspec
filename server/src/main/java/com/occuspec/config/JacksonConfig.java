package com.occuspec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson 全局配置。
 * 时间用 ISO 字符串而非时间戳；长整型按安全范围序列化（见 {@link SafeLongSerializer}）。
 */
@Configuration
public class JacksonConfig {
  @Bean
  public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
    ObjectMapper mapper = builder.createXmlMapper(false).build();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    // 雪花主键超出 JS 安全整数，按期序列化为字符串；计数器仍在范围内保持数字
    SimpleModule longModule = new SimpleModule();
    longModule.addSerializer(Long.class, new SafeLongSerializer());
    longModule.addSerializer(Long.TYPE, new SafeLongSerializer());
    mapper.registerModule(longModule);
    return mapper;
  }
}
