package com.occuspec.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/**
 * 长整型序列化器：超出 JavaScript 安全整数范围时输出为字符串，否则输出数字。
 *
 * <p>背景：数据库主键由雪花算法生成，为 19 位（约 2.1e18），超过 JS 的
 * {@code Number.MAX_SAFE_INTEGER}（2^53-1 ≈ 9.0e15）。前端 {@code JSON.parse} 会静默丢失精度，
 * 例如 2100132345519558658 变成 2100132345519558700，再把该值回传后端就查不到记录
 * （表现为"会话不存在""体检记录不存在"）。
 *
 * <p>为什么不一律转字符串：同一个 {@code Long} 类型既承载主键，也承载 token 数、
 * 耗时等计数器。若全部转字符串，前端 {@code usage.total > 0} 之类的判断会因
 * 字符串真值语义而失真（{@code "0"} 在 JS 中为真）。按数值范围判定可同时满足两者：
 * 主键（超范围）转字符串，计数器（范围内）保持数字，无需逐字段标注。
 *
 * <p>该序列化器同时作用于对象字段与 Map 值（如 SSE 事件载荷中的 sessionId），
 * 避免只处理字段却漏掉手工拼装的 Map。
 */
public class SafeLongSerializer extends JsonSerializer<Long> {
  /** JS 安全整数上限 2^53-1。 */
  private static final long MAX_SAFE_INTEGER = 9007199254740991L;

  @Override
  public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
    if (value == null) {
      gen.writeNull();
      return;
    }
    if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) {
      gen.writeString(value.toString());
    } else {
      gen.writeNumber(value);
    }
  }
}
