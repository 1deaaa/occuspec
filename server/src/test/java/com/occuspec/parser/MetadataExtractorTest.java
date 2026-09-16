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
  void 标准号提取() {
    String code = StandardMeta.extractStandardCode("GBZ 188—2025 代替旧版", "未知.md");
    assertTrue(code.contains("188"));
  }

  @Test
  void 危害因素按章节解析() {
    var catalog = new HazardCatalogParser().parse("# 7.1 噪声\n# 5.19 苯（CAS 号：71-43-2）\n");
    var resolver = new HazardResolver(catalog);
    assertEquals("gbz188-7-1", resolver.resolve("GBZ188-2025", "职业健康监护技术规范", "7.1.2"));
    assertEquals("gbz188-5-19", resolver.resolve("GBZ188-2025", "职业健康监护技术规范", "5.19.1.1"));
    // 章节外条款不误标
    assertEquals("", resolver.resolve("GBZ188-2025", "职业健康监护技术规范", "4.8.2"));
  }

  @Test
  void 非GBZ188按标准名解析且测量类不误标() {
    var catalog = new HazardCatalogParser().parse("# 7.1 噪声\n# 5.19 苯（CAS 号：71-43-2）\n");
    var resolver = new HazardResolver(catalog);
    assertEquals("gbz188-5-19", resolver.resolve("GBZ68-2022", "GBZ68-2022-职业性苯中毒诊断标准", "4.1"));
    // 测量/采样/分级类标准不锁定危害因素
    assertEquals("", resolver.resolve("GBZT189.9-2025", "工作场所物理因素测量 第9部分：手传振动", "4.1"));
    assertEquals("", resolver.resolve("GBZT192.1-2025", "工作场所空气中粉尘测定 第1部分：总粉尘浓度", "3.1"));
  }
}
