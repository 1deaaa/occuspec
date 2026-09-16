package com.occuspec.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GBZ 188 危害因素目录抽取器：从第 5-9 章的章节标题抽取完整危害因素清单。
 * 标题形如 "- 5.1 铅及其无机化合物（铅，CAS 号：7439-92-1）"，含 CAS 号便于生成稳定编码。
 */
public class HazardCatalogParser {
  /** 危害因素章节标题：第 5-9 章的两级编号，如 5.1 / 7.1 / 9.10。 */
  private static final Pattern HAZARD_HEADING =
      Pattern.compile("^(?:#{1,4}\\s*|[-*]\\s+)?([5-9])\\.(\\d{1,2})\\s+([^\\n]{2,})$");
  /** CAS 号：用于生成稳定编码与匹配。 */
  private static final Pattern CAS = Pattern.compile("CAS\\s*号?[：:]\\s*([0-9]{2,7}-[0-9]{2}-[0-9])");
  /** 章节大类名。 */
  private static final Map<String, String> CHAPTER_CATEGORY =
      Map.of("5", "化学", "6", "粉尘", "7", "物理", "8", "生物", "9", "特殊作业");

  /** 危害因素条目。 */
  public record HazardItem(
      String sectionNo, String name, String category, String cas, List<String> aliases) {}

  /**
   * 从 GBZ 188 全文抽取危害因素清单。
   *
   * @param markdown GBZ 188 全文
   * @return 按章节号排序的危害因素列表
   */
  public List<HazardItem> parse(String markdown) {
    Map<String, HazardItem> bySection = new LinkedHashMap<>();
    for (String rawLine : markdown.split("\n", -1)) {
      String line = rawLine == null ? "" : rawLine.replace("\r", "");
      Matcher m = HAZARD_HEADING.matcher(line);
      if (!m.matches()) {
        continue;
      }
      String sectionNo = m.group(1) + "." + m.group(2);
      String rawName = m.group(3).trim();
      // 过滤目录页噪音（目次行含大量省略号）与误命中的正文引用
      if (rawName.contains("....") || rawName.contains("……")) {
        continue;
      }
      String name = cleanName(rawName);
      if (name.isBlank() || name.length() > 60) {
        continue;
      }
      // 同章节号只保留首次出现（正文先于目次之后的引用）
      bySection.putIfAbsent(sectionNo, new HazardItem(
          sectionNo, name, CHAPTER_CATEGORY.getOrDefault(m.group(1), ""),
          extractCas(rawName), buildAliases(sectionNo, name)));    }
    return new ArrayList<>(bySection.values());
  }

  /** 从章节号与名称生成稳定编码：优先章节号（唯一且稳定），便于跨标准复用。 */
  public static String toCode(String sectionNo) {
    return "gbz188-" + sectionNo.replace(".", "-");
  }

  /** 清理名称：括注说明只保留主名称，如 "有机粉尘[指动物性粉尘…]" → "有机粉尘"。 */
  private String cleanName(String rawName) {
    String name = rawName;
    // 方括号内是补充说明（GBZ 188 用于列举该类的具体物质），主名称在前
    int bracketIdx = name.indexOf('[');
    if (bracketIdx > 0) {
      name = name.substring(0, bracketIdx);
    }
    // 圆括号内多为 CAS 号或参照说明，一并去掉
    int parenIdx = name.indexOf('（');
    if (parenIdx < 0) {
      parenIdx = name.indexOf('(');
    }
    if (parenIdx > 0) {
      name = name.substring(0, parenIdx);
    }
    name = name.replaceAll("\\s+", " ").trim();
    // 名称过长时按首个顿号截断，保留主名称
    if (name.length() > 60 && name.contains("、")) {
      name = name.substring(0, name.indexOf('、'));
    }
    return name;
  }

  private String extractCas(String rawName) {
    Matcher m = CAS.matcher(rawName);
    return m.find() ? m.group(1) : "";
  }

  /** 别名：主名称 + 去括号后的简称，供自然语言匹配使用。 */
  private List<String> buildAliases(String sectionNo, String name) {
    List<String> aliases = new ArrayList<>();
    aliases.add(name);
    // 括号内简称，如 "甲苯（二甲苯参照执行）" → "甲苯"
    Matcher m = Pattern.compile("^([^（(]+)[（(]").matcher(name);
    if (m.find()) {
      String shortName = m.group(1).trim();
      if (!shortName.isBlank() && !aliases.contains(shortName)) {
        aliases.add(shortName);
      }
    }
    return aliases;
  }
}
