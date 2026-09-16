package com.occuspec.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 元数据抽取单测：不联网。 */
class MetadataExtractorTest {
  @Test
  void 抽取检查类别与周期() {
    String content = "实验室检查必检项目：血常规。\n周期：每1年1次。\n";
    assertEquals("必检", MetadataExtractor.extractCheckClass(content));
    assertTrue(MetadataExtractor.extractPeriod(content).contains("1年"));
  }

  @Test
  void 抽取引用回链() {
    String content = "在岗禁忌证同5.1.1.1，按GBZ 37诊断，参见附录C。";
    var relations = MetadataExtractor.extractRelations(content);
    assertTrue(relations.contains("SAME:5.1.1.1"));
    assertTrue(relations.contains("REF:GBZ37"));
    assertTrue(relations.contains("APPENDIX:C"));
  }

  @Test
  void 附录C默认推荐性() {
    assertEquals("推荐性", MetadataExtractor.forceType("C", "检查内容"));
    assertEquals("强制性", MetadataExtractor.forceType("", "检查内容"));
  }

  @Test
  void 标准号提取与危害映射() {
    String code = StandardMeta.extractStandardCode("GBZ 188—2025 代替旧版", "未知.md");
    assertTrue(code.contains("188"));
    assertEquals("noise", StandardMeta.mapHazard("7.1.2", ""));
    assertEquals("lead", StandardMeta.mapHazard("5.1.1", ""));
    assertEquals("", StandardMeta.mapHazard("3.1", "术语和定义"));
  }
}
