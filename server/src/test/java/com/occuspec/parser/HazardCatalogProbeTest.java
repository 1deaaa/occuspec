package com.occuspec.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 危害因素目录抽取验证：对真实 GBZ 188 全文抽取并打印结果，供人工核对。
 */
class HazardCatalogProbeTest {
  @Test
  void 抽取真实GBZ188危害因素() throws Exception {
    Path file = Path.of("..", "data-markdown", "GBZ188-2025-职业健康监护技术规范.md");
    String md = Files.readString(file, StandardCharsets.UTF_8);
    List<HazardCatalogParser.HazardItem> items = new HazardCatalogParser().parse(md);
    System.out.println("HAZARDS=" + items.size());
    long withCas = items.stream().filter(i -> !i.cas().isBlank()).count();
    System.out.println("WITH_CAS=" + withCas);
    for (int i = 0; i < items.size(); i++) {
      var it = items.get(i);
      System.out.println(i + " | " + it.sectionNo() + " | " + it.category() + " | " + it.name()
          + " | cas=" + it.cas() + " | aliases=" + it.aliases());
    }
  }

  @Test
  void 章节号编码稳定() {
    assertEquals("gbz188-7-1", HazardCatalogParser.toCode("7.1"));
    assertEquals("gbz188-5-58", HazardCatalogParser.toCode("5.58"));
  }

  @Test
  void 阶段抽取() {
    PhaseExtractor ex = new PhaseExtractor();
    assertEquals("上岗前", ex.extract("7.1.1", "目标疾病为职业禁忌证：", ""));
    assertEquals("在岗期间", ex.extract("7.1.2", "", ""));
    assertEquals("离岗时", ex.extract("7.1.3", "", ""));
    assertEquals("应急", ex.extract("7.1.4", "", ""));
    // 标题关键词优先
    assertEquals("上岗前", ex.extract("7.1.1", "上岗前职业健康检查", ""));
    // 两段编号不代表阶段
    assertEquals("", ex.extract("7.1", "噪声", ""));
    // 非危害因素章节不猜
    assertEquals("", ex.extract("4.8.2", "报告", ""));
    assertFalse(ex.extract("5.1.2", "在岗期间职业健康检查", "").isBlank());
  }
}
