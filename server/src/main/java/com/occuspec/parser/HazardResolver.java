package com.occuspec.parser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 危害因素解析器：统一决定一条条款该标哪个危害因素。
 *
 * <p>两条来源，互不越界（此前版本把 GBZ 188 的章节号规则套用到全部标准，导致 591 条误标）：
 * <ul>
 *   <li>GBZ 188 条款：按章节号匹配危害因素目录（7.1 → 噪声）</li>
 *   <li>其他标准：按标准名称匹配危害因素名称（"职业性苯中毒诊断标准" → 苯）</li>
 * </ul>
 * 匹配不到一律留空，宁可缺标也不误标。
 */
public class HazardResolver {
  /** 标准名称匹配时需要排除的通用词，避免"测定""采样"等把无关标准也标上。 */
  private static final List<String> TITLE_STOP_WORDS =
      List.of("监测", "测定", "测量", "采样", "分级", "检验", "方法", "技术规范", "设计卫生标准");

  private final List<HazardCatalogParser.HazardItem> catalog;
  /** 章节号 → 危害因素编码，按章节号长度降序以优先匹配更具体的章节。 */
  private final Map<String, HazardCatalogParser.HazardItem> bySection = new ConcurrentHashMap<>();
  /** 名称/别名 → 危害因素编码，按名称长度降序避免短名抢先匹配。 */
  private final List<Map.Entry<String, HazardCatalogParser.HazardItem>> byName = new ArrayList<>();

  public HazardResolver(List<HazardCatalogParser.HazardItem> catalog) {
    this.catalog = catalog;
    for (HazardCatalogParser.HazardItem item : catalog) {
      bySection.put(item.sectionNo(), item);
    }
    List<Map.Entry<String, HazardCatalogParser.HazardItem>> entries = new ArrayList<>();
    for (HazardCatalogParser.HazardItem item : catalog) {
      for (String alias : item.aliases()) {
        // 中文单字别名有效（如"苯""氨"），长度靠 stop words 与长名优先排序兜底
        if (alias != null && !alias.isBlank()) {
          entries.add(Map.entry(alias, item));
        }
      }
    }
    // 长名优先：避免"苯"抢先匹配"甲苯"
    entries.sort(Comparator.comparingInt((Map.Entry<String, HazardCatalogParser.HazardItem> e) -> e.getKey().length())
        .reversed());
    byName.addAll(entries);
  }

  /** 危害因素目录（只读）。 */
  public List<HazardCatalogParser.HazardItem> catalog() {
    return List.copyOf(catalog);
  }

  /**
   * 解析条款危害因素。
   *
   * @param standardCode 标准号
   * @param standardName 标准名称
   * @param clauseNo 条款编号
   * @return 危害因素编码，无法确定返回空串
   */
  public String resolve(String standardCode, String standardName, String clauseNo) {
    if (isGbz188(standardCode)) {
      return bySection(clauseNo);
    }
    return byStandardName(standardName);
  }

  /** GBZ 188 系列判定：含 GBZ 188 且排除 GBZ/T 189/192 等编号相邻的标准。 */
  public static boolean isGbz188(String standardCode) {
    if (standardCode == null) {
      return false;
    }
    String code = standardCode.replace(" ", "");
    return code.startsWith("GBZ188");
  }

  /** 按章节号匹配：条款号必须以"章节号."或等于章节号开头。 */
  private String bySection(String clauseNo) {
    if (clauseNo == null) {
      return "";
    }
    // 逐级回退：7.1.2.1 → 7.1.2 → 7.1，取第一个命中的章节
    String current = clauseNo;
    while (!current.isEmpty()) {
      HazardCatalogParser.HazardItem item = bySection.get(current);
      if (item != null) {
        return HazardCatalogParser.toCode(item.sectionNo());
      }
      int lastDot = current.lastIndexOf('.');
      if (lastDot < 0) {
        break;
      }
      current = current.substring(0, lastDot);
    }
    return "";
  }

  /** 按标准名称匹配危害因素名称/别名。 */
  private String byStandardName(String standardName) {
    if (standardName == null || standardName.isBlank()) {
      return "";
    }
    for (String stopWord : TITLE_STOP_WORDS) {
      if (standardName.contains(stopWord)) {
        // 测量/采样/分级类标准描述的是方法，不锁定某一种危害因素
        return "";
      }
    }
    for (Map.Entry<String, HazardCatalogParser.HazardItem> entry : byName) {
      if (standardName.contains(entry.getKey())) {
        return HazardCatalogParser.toCode(entry.getValue().sectionNo());
      }
    }
    return "";
  }
}
