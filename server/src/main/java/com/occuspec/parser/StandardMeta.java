package com.occuspec.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标准号抽取：从正文标题或文件名提取稳定标准号。
 * 危害因素映射已迁至 {@link HazardResolver}，此处只负责标准号。
 */
public class StandardMeta {
  private static final Pattern STANDARD_CODE =
      Pattern.compile("GBZ/?T?\\s*\\d+(?:\\.\\d+)?[—–-]\\s*\\d{4}");
  private static final Pattern PUBLISH_EFFECTIVE =
      Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})\\s*实施");

  /** 从正文首屏提取标准号，失败回退文件名。 */
  public static String extractStandardCode(String headText, String fileName) {
    if (headText != null) {
      Matcher m = STANDARD_CODE.matcher(headText);
      if (m.find()) {
        return normalize(m.group(0));
      }
    }
    if (fileName != null) {
      Matcher m = STANDARD_CODE.matcher(fileName);
      if (m.find()) {
        return normalize(m.group(0));
      }
      // 非 GBZ 开头文件：用文件名（去扩展名）作为标准号
      String base = fileName.replaceAll("\\.md$", "");
      if (!base.isBlank()) {
        return base;
      }
    }
    return "UNKNOWN";
  }

  /** 规范化标准号：统一连接符为空格分隔，如 GBZ 188-2025。 */
  static String normalize(String raw) {
    return raw.replaceAll("[—–]", "-").replaceAll("\\s+", " ").trim();
  }
}
