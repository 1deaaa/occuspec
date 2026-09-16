package com.occuspec.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则表达式求值器：解析 {"all":[{fact,op,value}],"any":[...]} 结构。
 * 支持操作符：==,!=,>,>=,<,<=,contains,in。缺失事实视为不命中。
 */
public class RuleEvaluator {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 单条规则命中结果。 */
  public record Match(String ruleCode, String conclusion, int weight) {}

  /** 带命中细节的结果：供 Agent 了解"哪些事实触发了该规则"。 */
  public record DetailedMatch(
      String ruleCode, String conclusion, int weight, List<String> matchedFacts, List<Condition> conditions) {}

  /** 命中的单个条件。 */
  public record Condition(String fact, String op, String expected, String actual) {}

  /**
   * 对一组规则按顺序求值，返回首个命中的规则及其命中细节。
   *
   * @param rules 规则表达式与结论（调用方已按危害因素过滤并按权重排序）
   * @param facts 事实表：检查项编码 → 数值/文本
   */
  public DetailedMatch firstMatchDetailed(List<RuleSpec> rules, Map<String, Object> facts) {
    for (RuleSpec rule : rules) {
      if (!matches(rule.expression(), facts)) {
        continue;
      }
      List<String> matchedFacts = new ArrayList<>();
      List<Condition> conditions = new ArrayList<>();
      try {
        JsonNode root = MAPPER.readTree(rule.expression());
        for (JsonNode cond : root.path("all")) {
          collectCondition(cond, facts, matchedFacts, conditions);
        }
        for (JsonNode cond : root.path("any")) {
          collectCondition(cond, facts, matchedFacts, conditions);
        }
      } catch (Exception ignored) {
        // 表达式解析失败不影响命中结论
      }
      return new DetailedMatch(rule.code(), rule.conclusion(), rule.weight(), matchedFacts, conditions);
    }
    return null;
  }

  private void collectCondition(
      JsonNode cond, Map<String, Object> facts, List<String> matchedFacts, List<Condition> conditions) {
    String fact = cond.path("fact").asText("");
    if (fact.isBlank()) {
      return;
    }
    Object actual = facts.get(fact);
    if (actual == null) {
      return;
    }
    matchedFacts.add(fact);
    conditions.add(new Condition(fact, cond.path("op").asText("=="),
        cond.path("value").toString(), String.valueOf(actual)));
  }

  /**
   * 对一组规则按权重从高到低求值，返回首个命中的规则。
   *
   * @param rules 规则表达式与结论（调用方已按危害因素过滤并按权重排序）
   * @param facts 事实表：检查项编码 → 数值/文本
   */
  public Match firstMatch(List<RuleSpec> rules, Map<String, Object> facts) {
    for (RuleSpec rule : rules) {
      if (matches(rule.expression(), facts)) {
        return new Match(rule.code(), rule.conclusion(), rule.weight());
      }
    }
    return null;
  }

  /** 规则描述（与数据库 rules 表对应）。 */
  public record RuleSpec(String code, String expression, String conclusion, int weight) {}

  /** 表达式求值入口。 */
  public boolean matches(String expressionJson, Map<String, Object> facts) {
    if (expressionJson == null || expressionJson.isBlank()) {
      return false;
    }
    try {
      JsonNode root = MAPPER.readTree(expressionJson);
      List<Boolean> allResults = evalGroup(root.path("all"), facts);
      List<Boolean> anyResults = evalGroup(root.path("any"), facts);
      boolean allOk = allResults.stream().allMatch(Boolean::booleanValue);
      boolean anyOk = anyResults.isEmpty() || anyResults.stream().anyMatch(Boolean::booleanValue);
      // 空表达式 {"all":[]} 视为默认命中（兜底规则用）
      if (allResults.isEmpty() && anyResults.isEmpty()) {
        return true;
      }
      return allOk && anyOk;
    } catch (Exception e) {
      return false;
    }
  }

  private List<Boolean> evalGroup(JsonNode group, Map<String, Object> facts) {
    List<Boolean> results = new ArrayList<>();
    if (group == null || !group.isArray()) {
      return results;
    }
    for (JsonNode cond : group) {
      results.add(evalCondition(cond, facts));
    }
    return results;
  }

  private boolean evalCondition(JsonNode cond, Map<String, Object> facts) {
    String fact = cond.path("fact").asText("");
    String op = cond.path("op").asText("==");
    JsonNode expected = cond.path("value");
    Object actual = facts.get(fact);
    if (actual == null) {
      return false;
    }
    return switch (op) {
      case "==" -> equalsValue(actual, expected);
      case "!=" -> !equalsValue(actual, expected);
      case ">", ">=", "<", "<=" -> compareValue(actual, expected, op);
      case "contains" -> String.valueOf(actual).contains(expected.asText(""));
      case "in" -> inValue(actual, expected);
      default -> false;
    };
  }

  private boolean equalsValue(Object actual, JsonNode expected) {
    if (expected.isNumber() && actual instanceof Number n) {
      return Double.compare(n.doubleValue(), expected.asDouble()) == 0;
    }
    return String.valueOf(actual).equals(expected.asText());
  }

  private boolean compareValue(Object actual, JsonNode expected, String op) {
    double a;
    double b = expected.asDouble(Double.NaN);
    if (Double.isNaN(b)) {
      return false;
    }
    if (actual instanceof Number n) {
      a = n.doubleValue();
    } else {
      try {
        a = Double.parseDouble(String.valueOf(actual));
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return switch (op) {
      case ">" -> a > b;
      case ">=" -> a >= b;
      case "<" -> a < b;
      case "<=" -> a <= b;
      default -> false;
    };
  }

  private boolean inValue(Object actual, JsonNode expected) {
    if (!expected.isArray()) {
      return false;
    }
    for (JsonNode option : expected) {
      if (equalsValue(actual, option)) {
        return true;
      }
    }
    return false;
  }
}
