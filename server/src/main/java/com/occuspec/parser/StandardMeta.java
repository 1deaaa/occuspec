package com.occuspec.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标准号与危害因素映射：从标题与章节名映射到稳定编码。
 * 标准号优先从正文标题提取，失败回退文件名。
 */
public class StandardMeta {
  private static final Pattern STANDARD_CODE =
      Pattern.compile("GBZ/?T?\\s*\\d+(?:\\.\\d+)?[—–-]\\s*\\d{4}");
  private static final Pattern PUBLISH_EFFECTIVE =
      Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})\\s*实施");

  // GBZ 188 章节名到危害因素的映射（按章节前缀匹配）
  private static final Map<String, String> HAZARD_PREFIX = new HashMap<>();

  static {
    HAZARD_PREFIX.put("5.1", "lead");
    HAZARD_PREFIX.put("5.19", "benzene");
    HAZARD_PREFIX.put("5.58", "toluene");
    HAZARD_PREFIX.put("6.1", "dust_silica");
    HAZARD_PREFIX.put("7.1", "noise");
    HAZARD_PREFIX.put("7.2", "vibration");
    HAZARD_PREFIX.put("7.3", "heat");
  }

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

  /** 按条款编号前缀映射危害因素，映射不到返回空。 */
  public static String mapHazard(String clauseNo, String title) {
    if (clauseNo != null) {
      for (Map.Entry<String, String> entry : HAZARD_PREFIX.entrySet()) {
        if (clauseNo.equals(entry.getKey()) || clauseNo.startsWith(entry.getKey() + ".")) {
          return entry.getValue();
        }
      }
    }
    if (title != null) {
      if (title.contains("噪声")) {
        return "noise";
      }
      if (title.contains("苯") && !title.contains("甲苯")) {
        return "benzene";
      }
      if (title.contains("铅")) {
        return "lead";
      }
      if (title.contains("粉尘") || title.contains("尘肺")) {
        return "dust_silica";
      }
    }
    return "";
  }
}
