package com.occuspec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.core.RedisTemplate;

/** Redis 配置：Lettuce 连接池，key 统一字符串序列化。 */
@Configuration
public class RedisConfig {
  @Bean
  public LettuceConnectionFactory redisConnectionFactory(
      @Value("${spring.data.redis.host:127.0.0.1}") String host,
      @Value("${spring.data.redis.port:6379}") int port,
      @Value("${spring.data.redis.password:1009}") String password) {
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
    if (password != null && !password.isBlank()) {
      config.setPassword(RedisPassword.of(password));
    }
    return new LettuceConnectionFactory(config);
  }

  @Bean
  public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
    return new StringRedisTemplate(factory);
  }

  @Bean
  public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory factory, ObjectMapper objectMapper) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
    template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
    return template;
  }
}
