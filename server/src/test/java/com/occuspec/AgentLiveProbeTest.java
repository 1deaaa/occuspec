package com.occuspec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occuspec.enums.Conclusion;
import com.occuspec.rule.ConclusionPolicy;
import com.occuspec.rule.RuleEvaluator;
import com.occuspec.service.AssessAgentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Agent 真实链路集成测试：向上去模型服务发起真实请求，观察 Agentic 行为。
 *
 * <p>与 {@code AssessAgentServiceTest} 的分工：
 * <ul>
 *   <li>单测用假网关，保证 CI 确定性与离线可跑，覆盖下限强制、降级等分支；</li>
 *   <li>本测试用真实模型，验证"模型是否真的会自主选择检索工具与过滤维度"，
 *       这是 Agentic 特性的关键证据，无法用替身证明。</li>
 * </ul>
 * 属于功能级探索测试，按项目约定允许打上游；批量回归默认排除，测试策略见 {@code docs/test-strategy.md}。
 */
@Tag("live")
@SpringBootTest
@ActiveProfiles("local")
class AgentLiveProbeTest {
  @Autowired AssessAgentService agentService;

  /** 噪声：听阈 45 dB（≥40）应触发规则下限"职业禁忌证"。 */
  @Test
  void 真实模型在噪声场景自主检索并提交结论() {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("hearing_avg_db", 45.0);
    facts.put("hearing_500hz_db", 30.0);
    facts.put("hearing_4000hz_db", 50.0);

    List<RuleEvaluator.RuleSpec> rules = List.of(
        new RuleEvaluator.RuleSpec("NOISE_HEARING_TABOO",
            "{\"all\":[{\"fact\":\"hearing_avg_db\",\"op\":\">=\",\"value\":40}]}",
            "OCCUPATIONAL_TABOO", 200));

    List<String> toolSequence = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    var input = new AssessAgentService.AssessInput(
        "gbz188-7-1", "噪声", facts, rules, "双耳高频平均听阈=45 dB；500Hz=30 dB；4000Hz=50 dB");

    var result = agentService.assess(input, new AssessAgentService.AgentProgress() {
      @Override
      public void onReasoning(String text) {
        // 推理过程不在此断言
      }

      @Override
      public void onToolCall(String tool, Map<String, Object> args) {
        toolSequence.add(tool + " " + args);
      }

      @Override
      public void onToolResult(String tool, int hits, String note) {
        notes.add(tool + " → " + hits + " 条：" + note);
      }
    });

    System.out.println("=== Agent 工具调用序列 ===");
    toolSequence.forEach(t -> System.out.println(" - " + t));
    System.out.println("=== 工具结果 ===");
    notes.forEach(n -> System.out.println(" - " + n));
    System.out.println("=== 结论 ===");
    System.out.println("conclusion=" + result.conclusion() + " source=" + result.decisionSource()
        + " floor=" + result.floorApplied() + " rounds=" + result.rounds()
        + " citations=" + result.citations().size() + " tokens=" + result.usage().totalTokens());
    System.out.println("rationale=" + result.rationale());

    // 真实性断言：模型必须提交结论（可能被下限覆盖，但不得轻于下限）
    assertFalse(result.degraded(), "真实模型不应降级（若失败请检查上游与密钥）");
    assertTrue(result.citations().size() > 0, "Agent 应至少检索到条款作为依据");
    assertTrue(result.conclusion().getSeverity() >= Conclusion.OCCUPATIONAL_TABOO.getSeverity(),
        "结论不得轻于规则下限");
    // 结论与下限的合并应自洽
    var expected = ConclusionPolicy.merge(Conclusion.OCCUPATIONAL_TABOO,
        result.floorApplied() ? Conclusion.NO_ABNORMALITY : result.conclusion());
    assertTrue(expected.conclusion().getSeverity() <= result.conclusion().getSeverity());
  }

  /** 苯：血常规异常，模型应检索苯相关条款（验证过滤维度由模型自选）。 */
  @Test
  void 真实模型在苯场景自主选择过滤维度() {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("wbc", 3.2);
    facts.put("platelet", 90.0);

    List<RuleEvaluator.RuleSpec> rules = List.of(
        new RuleEvaluator.RuleSpec("BENZENE_BLOOD_ABNORMAL",
            "{\"all\":[{\"fact\":\"wbc\",\"op\":\"<\",\"value\":4.0}]}",
            "OCCUPATIONAL_TABOO", 180));

    List<Map<String, Object>> calls = new ArrayList<>();
    var input = new AssessAgentService.AssessInput(
        "gbz188-5-19", "苯", facts, rules, "白细胞=3.2×10^9/L；血小板=90×10^9/L");

    var result = agentService.assess(input, new AssessAgentService.AgentProgress() {
      @Override
      public void onReasoning(String text) {
        // 不在此断言
      }

      @Override
      public void onToolCall(String tool, Map<String, Object> args) {
        calls.add(Map.of("tool", tool, "args", args));
      }

      @Override
      public void onToolResult(String tool, int hits, String note) {
        // 不在此断言
      }
    });

    System.out.println("=== 苯场景 Agent 工具调用 ===");
    calls.forEach(c -> System.out.println(" - " + c));
    System.out.println("conclusion=" + result.conclusion() + " source=" + result.decisionSource()
        + " citations=" + result.citations().size() + " rounds=" + result.rounds());
    result.citations().forEach(c -> System.out.println(
        "   引用：" + c.standardCode() + " " + c.clauseNo() + " " + c.title()));

    assertFalse(result.degraded());
    assertTrue(result.rounds() >= 1);
    assertTrue(calls.stream().anyMatch(c -> String.valueOf(c.get("tool"))
        .startsWith("clause_") || "describe_metadata".equals(c.get("tool"))),
        "模型应自主调用检索类工具");
  }
}
