package com.occuspec.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 字段映射层验证：别名归一化、批量补键、未收录回退。
 */
@SpringBootTest
@ActiveProfiles("local")
class FieldMappingServiceTest {
  @Autowired FieldMappingService fieldMappingService;

  @Test
  void 中文别名归一化为规范编码() {
    assertEquals("hearing_avg_db", fieldMappingService.canonicalize("双耳高频平均听阈"));
    assertEquals("hearing_avg_db", fieldMappingService.canonicalize("双耳 高频平均听阈"));
    assertEquals("blood_lead_umol", fieldMappingService.canonicalize("血铅"));
    assertEquals("blood_lead_umol", fieldMappingService.canonicalize("Blood_Lead"));
  }

  @Test
  void 未收录字段回退原名() {
    assertEquals("机构自定义项", fieldMappingService.canonicalize("机构自定义项"));
    assertEquals("", fieldMappingService.canonicalize("   "));
  }

  @Test
  void 规范中文名可展示() {
    assertEquals("双耳高频平均听阈", fieldMappingService.canonicalName("hearing"));
    assertEquals("未知名", fieldMappingService.canonicalName("未知名"));
  }

  @Test
  void 批量归一化保留原键并补规范键() {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("双耳高频平均听阈", 45.0);
    facts.put("自定义项", "abc");
    Map<String, Object> mapped = fieldMappingService.canonicalizeFacts(facts);
    assertEquals(45.0, mapped.get("双耳高频平均听阈"));
    assertEquals(45.0, mapped.get("hearing_avg_db"), "应补出规范编码键供规则引用");
    assertEquals("abc", mapped.get("自定义项"));
    assertTrue(mapped.containsKey("hearing_avg_db"));
  }
}
