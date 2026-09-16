package com.occuspec.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.enums.Conclusion;
import com.occuspec.llm.LlmGateway;
import com.occuspec.llm.LlmResult;
import com.occuspec.llm.LlmToolCall;
import com.occuspec.llm.LlmToolResponse;
import com.occuspec.llm.LlmToolSpec;
import com.occuspec.llm.LlmUsage;
import com.occuspec.rag.ClauseRetrievalTools;
import com.occuspec.rag.ClauseVectorStore;
import com.occuspec.rag.RetrievalToolCatalog;
import com.occuspec.rule.RuleEvaluator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 判定 Agent 循环单测：用假网关驱动，验证模型自主选工具、结论下限强制与降级回退。
 * 不联网、不访问数据库（检索工具以替身注入）。
 */
class AssessAgentServiceTest {

  /** 噪声禁忌证规则：听阈 ≥ 40 命中，构成 OCCUPATIONAL_TABOO 下限。 */
  private static final List<RuleEvaluator.RuleSpec> NOISE_RULES = List.of(
      new RuleEvaluator.RuleSpec("NOISE_HEARING_TABOO",
          "{\"all\":[{\"fact\":\"hearing_avg_db\",\"op\":\">=\",\"value\":40}]}",
          "OCCUPATIONAL_TABOO", 200));

  private Map<String, Object> noiseFacts() {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("hearing_avg_db", 45.0);
    return facts;
  }

  private AssessAgentService agent(LlmGateway gateway) {
    // 检索工具以替身注入：本测试聚焦 Agent 循环逻辑，不触发真实检索
    ClauseRetrievalTools tools = mock(ClauseRetrievalTools.class);
    when(tools.retrieve(anyString(), anyInt(), any(ClauseVectorStore.Filters.class)))
        .thenReturn(new ClauseRetrievalTools.RetrievalResult(List.of(),
            new ClauseRetrievalTools.ToolCall("clause_retrieve", Map.of(), 0, "替身未召回")));
    when(tools.describeMetadata()).thenReturn(new ClauseRetrievalTools.MetadataDiscovery(
        Map.of("phase", Map.of("上岗前", 1), "check_class", Map.of("必检", 1)),
        Map.of(), "替身元数据"));
    return new AssessAgentService(gateway, tools, new RetrievalToolCatalog(tools), new ObjectMapper());
  }

  private AssessAgentService.AssessInput noiseInput() {
    return new AssessAgentService.AssessInput(
        "gbz188-7-1", "噪声", noiseFacts(), NOISE_RULES, "双耳高频平均听阈=45 dB");
  }

  @Test
  void 模型自主调用工具并提交结论() {
    // 第 1 轮：探查元数据 + 提交结论；第 2 轮：无工具调用，结束
    FakeGateway gateway = new FakeGateway(
        toolRound("describe_metadata", "{}"),
        finalRound("OCCUPATIONAL_TABOO", "听阈 45 dB ≥ 40，符合噪声职业禁忌证"));
    var result = agent(gateway).assess(noiseInput(), null);

    assertEquals(Conclusion.OCCUPATIONAL_TABOO, result.conclusion());
    assertEquals("AGENT", result.decisionSource());
    assertFalse(result.floorApplied());
    assertFalse(result.degraded());
    assertTrue(result.traces().stream().anyMatch(t -> "describe_metadata".equals(t.tool())));
    assertTrue(result.traces().stream().anyMatch(t -> "record_conclusion".equals(t.tool())));
    assertTrue(result.rationale().contains("职业禁忌证"));
  }

  @Test
  void 模型下调结论被规则下限拦截() {
    FakeGateway gateway = new FakeGateway(
        finalRound("NO_ABNORMALITY", "未见明显异常"));
    var result = agent(gateway).assess(noiseInput(), null);

    assertEquals(Conclusion.OCCUPATIONAL_TABOO, result.conclusion(), "不得轻于规则下限");
    assertTrue(result.floorApplied());
    assertEquals("RULE_FLOOR", result.decisionSource());
  }

  @Test
  void 模型上调结论被采纳并记录来源() {
    FakeGateway gateway = new FakeGateway(
        finalRound("SUSPECTED_OCCUPATIONAL_DISEASE", "结合诊断标准，疑似职业性噪声聋"));
    var result = agent(gateway).assess(noiseInput(), null);

    assertEquals(Conclusion.SUSPECTED_OCCUPATIONAL_DISEASE, result.conclusion());
    assertFalse(result.floorApplied());
    assertEquals("AGENT", result.decisionSource());
  }

  @Test
  void 模型不可用时降级为规则下限() {
    FakeGateway gateway = FakeGateway.degraded();
    var result = agent(gateway).assess(noiseInput(), null);

    assertEquals(Conclusion.OCCUPATIONAL_TABOO, result.conclusion());
    assertEquals("RULE_FALLBACK", result.decisionSource());
    assertTrue(result.degraded());
    assertTrue(result.answer().contains("复核"));
  }

  @Test
  void 模型只输出文字不提交结论时回退下限() {
    FakeGateway gateway = new FakeGateway(
        new LlmToolResponse("我认为需要进一步检查", null, List.of(), usage(), false));
    var result = agent(gateway).assess(noiseInput(), null);

    assertEquals(Conclusion.OCCUPATIONAL_TABOO, result.conclusion());
    assertEquals("RULE_FALLBACK", result.decisionSource());
  }

  @Test
  void 无规则命中且模型未提交时结论为未见异常() {
    FakeGateway gateway = new FakeGateway(
        new LlmToolResponse("无异常", null, List.of(), usage(), false));
    var input = new AssessAgentService.AssessInput(
        "gbz188-7-1", "噪声", Map.of(), List.of(), "无检查项");
    var result = agent(gateway).assess(input, null);

    assertEquals(Conclusion.NO_ABNORMALITY, result.conclusion());
    assertEquals("RULE_FALLBACK", result.decisionSource());
  }

  @Test
  void 检查项事实工具不触发数据库访问() {
    FakeGateway gateway = new FakeGateway(
        toolRound("list_exam_facts", "{}"),
        finalRound("OCCUPATIONAL_TABOO", "依据听阈事实"));
    var result = agent(gateway).assess(noiseInput(), null);

    assertEquals(Conclusion.OCCUPATIONAL_TABOO, result.conclusion());
    assertTrue(result.traces().stream().anyMatch(t -> "list_exam_facts".equals(t.tool())));
    assertTrue(result.traces().stream()
        .filter(t -> "list_exam_facts".equals(t.tool()))
        .anyMatch(t -> t.hits() >= 1));
  }

  @Test
  void 进度回调收到推理与工具事件() {
    FakeGateway gateway = new FakeGateway(
        toolRound("describe_metadata", "{}"),
        finalRound("OCCUPATIONAL_TABOO", "依据"));
    List<String> tools = new ArrayList<>();
    List<String> reasoning = new ArrayList<>();
    agent(gateway).assess(noiseInput(), new AssessAgentService.AgentProgress() {
      @Override
      public void onReasoning(String text) {
        reasoning.add(text);
      }

      @Override
      public void onToolCall(String tool, Map<String, Object> args) {
        tools.add(tool);
      }

      @Override
      public void onToolResult(String tool, int hits, String note) {
        // 无需断言
      }
    });
    assertTrue(tools.contains("describe_metadata"));
    assertTrue(tools.contains("record_conclusion"));
    assertFalse(reasoning.isEmpty(), "应推送规则下限等进度信息");
  }

  /** 构造一轮含单个工具调用的响应。 */
  private LlmToolResponse toolRound(String tool, String args) {
    return new LlmToolResponse("", null,
        List.of(new LlmToolCall("call-" + tool, tool, args)), usage(), false);
  }

  /** 构造一轮提交结论的响应。 */
  private LlmToolResponse finalRound(String conclusion, String rationale) {
    String args = "{\"conclusion\":\"" + conclusion + "\",\"rationale\":\"" + rationale + "\"}";
    return new LlmToolResponse("", null,
        List.of(new LlmToolCall("call-final", "record_conclusion", args)), usage(), false);
  }

  private static LlmUsage usage() {
    return new LlmUsage(10, 5, 15);
  }

  /** 假网关：按脚本依次返回预设响应，用尽后返回空响应。 */
  private static final class FakeGateway implements LlmGateway {
    private final Deque<LlmToolResponse> script = new ArrayDeque<>();
    private final boolean degraded;

    FakeGateway(LlmToolResponse... rounds) {
      script.addAll(List.of(rounds));
      this.degraded = false;
    }

    private FakeGateway(boolean degraded) {
      this.degraded = degraded;
    }

    static FakeGateway degraded() {
      return new FakeGateway(true);
    }

    @Override
    public LlmResult complete(String prompt) {
      return LlmResult.degraded("");
    }

    @Override
    public LlmResult complete(String systemPrompt, String userPrompt) {
      return LlmResult.degraded("");
    }

    @Override
    public void stream(String prompt, Consumer<String> onDelta, Consumer<String> onThinking,
        Consumer<LlmUsage> onUsage) {
      onUsage.accept(LlmUsage.empty());
    }

    @Override
    public List<float[]> embed(List<String> texts) {
      return List.of();
    }

    @Override
    public LlmToolResponse completeWithTools(List<com.occuspec.llm.ChatMsg> messages,
        List<LlmToolSpec> tools) {
      if (degraded) {
        return LlmToolResponse.degraded("");
      }
      LlmToolResponse next = script.poll();
      if (next == null) {
        return new LlmToolResponse("", null, List.of(), usage(), false);
      }
      return next;
    }
  }
}
