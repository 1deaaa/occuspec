package com.occuspec.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 规则求值单测：不联网，纯内存断言，覆盖边界值。 */
class RuleEvaluatorTest {
  private final RuleEvaluator evaluator = new RuleEvaluator();

  @Test
  void 噪声听阈边界值() {
    String expr = "{\"all\": [{\"fact\": \"hearing_avg_db\", \"op\": \">=\", \"value\": 40}]}";
    assertTrue(evaluator.matches(expr, Map.of("hearing_avg_db", 40)));
    assertTrue(evaluator.matches(expr, Map.of("hearing_avg_db", 45.5)));
    assertFalse(evaluator.matches(expr, Map.of("hearing_avg_db", 39.9)));
    // 缺失事实视为不命中
    assertFalse(evaluator.matches(expr, Map.of()));
  }

  @Test
  void 血铅阈值与文本比较() {
    String expr = "{\"all\": [{\"fact\": \"blood_lead_umol\", \"op\": \">=\", \"value\": 2.9}]}";
    assertTrue(evaluator.matches(expr, Map.of("blood_lead_umol", 2.9)));
    assertFalse(evaluator.matches(expr, Map.of("blood_lead_umol", 1.5)));
    // 字符串数值同样可比
    assertTrue(evaluator.matches(expr, Map.of("blood_lead_umol", "3.1")));
    assertFalse(evaluator.matches(expr, Map.of("blood_lead_umol", "异常")));
  }

  @Test
  void 组合条件与权重优先级() {
    var rules = List.of(
        new RuleEvaluator.RuleSpec("HIGH", "{\"all\": [{\"fact\": \"a\", \"op\": \">=\", \"value\": 10}]}", "OCCUPATIONAL_TABOO", 200),
        new RuleEvaluator.RuleSpec("LOW", "{\"all\": []}", "NO_ABNORMALITY", 1));
    var hit = evaluator.firstMatch(rules, Map.of("a", 12));
    assertEquals("HIGH", hit.ruleCode());
    var fallback = evaluator.firstMatch(rules, Map.of("a", 1));
    assertEquals("LOW", fallback.ruleCode());
  }

  @Test
  void 非法表达式不抛穿() {
    assertFalse(evaluator.matches("not-json", Map.of("a", 1)));
    assertFalse(evaluator.matches(null, Map.of("a", 1)));
  }
}
