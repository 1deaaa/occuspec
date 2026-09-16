package com.occuspec.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 元数据抽取器：从条款块内稳定抽取检查类别、目标文本、周期、引用回链。
 * 标题与前言级字段（标准号/附录类型/页码）由切分器归因，此处只做块内关键词抽取。
 */
public class MetadataExtractor {
  private static final Pattern CHECK_CLASS =
      Pattern.compile("(必检|补充检查|选检)[^。；\\n]{0,60}");
  private static final Pattern PERIOD = Pattern.compile("周期[：:][^。\\n]{0,80}");
  private static final Pattern TARGET =
      Pattern.compile("(目标疾病[：:][^\\n]{0,120}|职业禁忌证[：:]{0,1}[^\\n]{0,120})");
  // 引用回链：同 X.Y.Z / 按 GBZ 37 / 参见附录 X / GBZ/T 260
  private static final Pattern REL_SAME = Pattern.compile("同\\s*(\\d+(?:\\.\\d+){1,3})");
  private static final Pattern REL_GBZ = Pattern.compile("GBZ/?T?\\s*\\d+(?:\\.\\d+)?");
  private static final Pattern REL_APPENDIX = Pattern.compile("(?:参见|见)?附录\\s*([A-G])");

  /** 抽取检查类别：必检/补充/选检（取首次命中）。 */
  public static String extractCheckClass(String content) {
    if (content == null) {
      return "";
    }
    Matcher m = CHECK_CLASS.matcher(content);
    if (m.find()) {
      String hit = m.group(1);
      if ("补充检查".equals(hit)) {
        return "补充";
      }
      return hit;
    }
    return "";
  }

  /** 抽取周期摘录。 */
  public static String extractPeriod(String content) {
    if (content == null) {
      return "";
    }
    Matcher m = PERIOD.matcher(content);
    if (m.find()) {
      return m.group(0).trim();
    }
    return "";
  }

  /** 抽取目标疾病或禁忌证摘录。 */
  public static String extractTarget(String content) {
    if (content == null) {
      return "";
    }
    Matcher m = TARGET.matcher(content);
    if (m.find()) {
      String hit = m.group(0).trim();
      return hit.length() > 300 ? hit.substring(0, 300) : hit;
    }
    return "";
  }

  /** 抽取引用回链：同章节号、引用标准号、引用附录。 */
  public static List<String> extractRelations(String content) {
    List<String> relations = new ArrayList<>();
    if (content == null) {
      return relations;
    }
    Matcher same = REL_SAME.matcher(content);
    while (same.find()) {
      relations.add("SAME:" + same.group(1));
    }
    Matcher gbz = REL_GBZ.matcher(content);
    while (gbz.find()) {
      String hit = gbz.group(0).replaceAll("\\s+", "");
      if (!relations.contains("REF:" + hit)) {
        relations.add("REF:" + hit);
      }
    }
    Matcher appendix = REL_APPENDIX.matcher(content);
    while (appendix.find()) {
      relations.add("APPENDIX:" + appendix.group(1));
    }
    return relations;
  }

  /** 判定强制性：附录 C 默认为推荐性，正文默认为强制性。 */
  public static String forceType(String appendixLetter, String content) {
    if ("C".equalsIgnoreCase(appendixLetter)) {
      return "推荐性";
    }
    if (content != null && content.contains("推荐性")) {
      return "推荐性";
    }
    return "强制性";
  }
}
