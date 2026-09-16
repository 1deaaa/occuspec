package com.occuspec.parser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检查阶段抽取器：从条款编号或标题判定"上岗前/在岗期间/离岗时/应急"。
 * GBZ 188 编号约定：X.Y.1 上岗前、X.Y.2 在岗、X.Y.3 离岗、X.Y.4 应急。
 */
public class PhaseExtractor {
  public static final String PRE_EMPLOYMENT = "上岗前";
  public static final String IN_SERVICE = "在岗期间";
  public static final String PRE_DEPARTURE = "离岗时";
  public static final String EMERGENCY = "应急";

  /** 编号末段到阶段的映射。 */
  private static final Map<String, String> SEGMENT_PHASE = Map.of(
      "1", PRE_EMPLOYMENT, "2", IN_SERVICE, "3", PRE_DEPARTURE, "4", EMERGENCY);

  /** 标题关键词到阶段的映射（用于编号无法判定时兜底）。 */
  private static final Map<String, String> TITLE_KEYWORD_PHASE = new LinkedHashMap<>();

  static {
    TITLE_KEYWORD_PHASE.put("上岗前", PRE_EMPLOYMENT);
    TITLE_KEYWORD_PHASE.put("在岗期间", IN_SERVICE);
    TITLE_KEYWORD_PHASE.put("离岗时", PRE_DEPARTURE);
    TITLE_KEYWORD_PHASE.put("离岗后", PRE_DEPARTURE);
    TITLE_KEYWORD_PHASE.put("应急", EMERGENCY);
  }

  /**
   * 抽取检查阶段。
   *
   * @param clauseNo 条款编号（如 7.1.2 或 5.1.1.1）
   * @param title 条款标题
   * @param content 条款正文（标题未命中时前 200 字兜底）
   * @return 阶段名，无法判定返回空串
   */
  public String extract(String clauseNo, String title, String content) {
    // 标题关键词优先：标题明确写着"上岗前职业健康检查"时最可靠
    if (title != null) {
      for (Map.Entry<String, String> entry : TITLE_KEYWORD_PHASE.entrySet()) {
        if (title.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
    }
    // 编号末段：仅当编号为 X.Y.Z 三段及以上时有效（X.Y 是危害因素节，不代表阶段）
    String bySegment = phaseBySegment(clauseNo);
    if (!bySegment.isEmpty()) {
      return bySegment;
    }
    // 正文兜底：限定在前 200 字，避免正文中偶尔提及导致误判
    if (content != null && !content.isBlank()) {
      String head = content.length() > 200 ? content.substring(0, 200) : content;
      for (Map.Entry<String, String> entry : TITLE_KEYWORD_PHASE.entrySet()) {
        if (head.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
    }
    return "";
  }

  /** 按编号末段判定：X.Y.1 / X.Y.2 / X.Y.3 / X.Y.4。 */
  private String phaseBySegment(String clauseNo) {
    if (clauseNo == null) {
      return "";
    }
    String[] parts = clauseNo.split("\\.");
    // 至少三段：X.Y.N，且第 N 段的父级是危害因素节（X.Y）
    if (parts.length < 3) {
      return "";
    }
    // 取第一段的章节归属判断是否在 5-9 章（危害因素章节）
    if (parts[0].length() != 1 || parts[0].compareTo("5") < 0 || parts[0].compareTo("9") > 0) {
      return "";
    }
    return SEGMENT_PHASE.getOrDefault(parts[2], "");
  }
}
