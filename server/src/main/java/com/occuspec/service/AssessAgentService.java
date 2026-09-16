package com.occuspec.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.enums.Conclusion;
import com.occuspec.llm.ChatMsg;
import com.occuspec.llm.LlmGateway;
import com.occuspec.llm.LlmToolCall;
import com.occuspec.llm.LlmToolResponse;
import com.occuspec.llm.LlmToolSpec;
import com.occuspec.llm.LlmUsage;
import com.occuspec.rag.ClauseRetrievalTools;
import com.occuspec.rag.ClauseVectorStore;
import com.occuspec.rag.RetrievalToolCatalog;
import com.occuspec.rule.ConclusionPolicy;
import com.occuspec.rule.RuleEvaluator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 判定 Agent 深模块：小接口 {@code assess(input)} → 结论 + 证据 + 决策轨迹。
 *
 * <p>与"固定四步流程"的区别：这里只给模型工具与规则底线，不给流程。
 * 模型自主决定查什么（危害路由/语义检索/精确取条款/引用展开）、传什么过滤维度、
 * 是否先探查元数据分布，最后必须调用 {@code record_conclusion} 提交结论与依据。
 *
 * <p>安全约束：规则引擎结果为不可下调的下限，由 {@link ConclusionPolicy} 在终止工具中强制；
 * 模型可上调结论（发现更多风险），但不得下调，被拦截时记录原因供复核。
 *
 * <p>降级：模型不可用或未提交结论时，回退为规则结论，绝不抛穿到接口层。
 */
@Service
public class AssessAgentService {
  private static final Logger log = LoggerFactory.getLogger(AssessAgentService.class);
  /** 工具调用最大轮次：兼顾多步检索与耗时上限。 */
  private static final int MAX_ROUNDS = 8;
  /** 终止工具名：模型用它提交结论。 */
  private static final String FINAL_TOOL = "record_conclusion";

  private final LlmGateway llmGateway;
  private final ClauseRetrievalTools retrievalTools;
  private final RetrievalToolCatalog toolCatalog;
  private final ObjectMapper objectMapper;

  public AssessAgentService(
      LlmGateway llmGateway, ClauseRetrievalTools retrievalTools,
      RetrievalToolCatalog toolCatalog, ObjectMapper objectMapper) {
    this.llmGateway = llmGateway;
    this.retrievalTools = retrievalTools;
    this.toolCatalog = toolCatalog;
    this.objectMapper = objectMapper;
  }

  /** 判定输入：检查项事实、危害因素与规则下限。 */
  public record AssessInput(
      String hazardCode, String hazardName, Map<String, Object> facts,
      List<RuleEvaluator.RuleSpec> rules, String examSummary) {}

  /** Agent 进度回调：供 SSE 推送推理与工具事件。 */
  public interface AgentProgress {
    void onReasoning(String text);
    /** 工具调用（含参数）。 */
    void onToolCall(String tool, Map<String, Object> args);
    /** 工具结果（含命中数与说明）。 */
    void onToolResult(String tool, int hits, String note);
  }

  /** 决策轨迹中的一步。 */
  public record ToolTrace(String tool, Map<String, Object> args, int hits, String note) {}

  /** Agent 判定结果。 */
  public record AgentResult(
      Conclusion conclusion,
      String decisionSource,
      boolean floorApplied,
      boolean degraded,
      String reasoning,
      String answer,
      String rationale,
      List<AssessAgentService.CitedClause> citations,
      List<ToolTrace> traces,
      List<String> evidenceFacts,
      LlmUsage usage,
      int rounds) {}

  /** Agent 引用到的条款。 */
  public record CitedClause(
      long clauseId, String standardCode, String clauseNo, String title,
      String quote, Integer pageNo, String source) {}

  /**
   * 执行判定：Agent 自主检索 → 提交结论 → 与规则下限合并。
   *
   * @param input 判定输入
   * @param progress 进度回调（可空）
   */
  public AgentResult assess(AssessInput input, AgentProgress progress) {
    // 先算规则下限：无论模型是否可用，下限都要有
    RuleEvaluator evaluator = new RuleEvaluator();
    RuleEvaluator.DetailedMatch ruleMatch =
        evaluator.firstMatchDetailed(input.rules(), input.facts());
    Conclusion ruleConclusion = ruleMatch == null
        ? null : Conclusion.of(ruleMatch.conclusion());
    emit(progress, "规则引擎下限：" + label(ruleConclusion) + "（"
        + (ruleMatch == null ? "无命中" : ruleMatch.ruleCode()) + "）");

    List<ChatMsg> messages = new ArrayList<>();
    messages.add(ChatMsg.system(buildSystemPrompt(input)));
    messages.add(ChatMsg.user(buildUserPrompt(input, ruleMatch)));

    List<LlmToolSpec> tools = buildTools(input);
    StringBuilder reasoning = new StringBuilder();
    StringBuilder answer = new StringBuilder();
    List<CitedClause> citations = new ArrayList<>();
    List<ToolTrace> traces = new ArrayList<>();
    List<String> evidenceFacts = new ArrayList<>();
    LlmUsage total = LlmUsage.empty();

    Submitted submitted = null;
    boolean degraded = false;
    int rounds = 0;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      rounds++;
      LlmToolResponse response = llmGateway.completeWithTools(messages, tools);
      total = total.add(response.usage());
      if (response.degraded()) {
        degraded = true;
        emit(progress, "模型不可用，降级为规则结论");
        break;
      }
      appendReasoning(response, reasoning, progress);
      if (response.content() != null && !response.content().isBlank()) {
        answer.append(response.content());
      }
      if (!response.hasToolCalls()) {
        // 模型直接给出文字而未提交结构化结论：视为未提交，走降级
        emit(progress, "模型未提交结构化结论，使用规则下限");
        break;
      }
      messages.add(ChatMsg.assistant(response.content(), toToolCallsJson(response.toolCalls())));
      boolean finished = false;
      for (LlmToolCall call : response.toolCalls()) {
        Map<String, Object> args = parseArgs(call.arguments());
        emitTool(progress, call.name(), args);
        if (FINAL_TOOL.equals(call.name())) {
          submitted = parseSubmission(args);
          messages.add(ChatMsg.tool(call.id(), "{\"ok\":true}"));
          traces.add(new ToolTrace(call.name(), args, 1, "提交结论：" + submitted.conclusionCode()));
          emitToolResult(progress, call.name(), 1, "提交结论：" + label(Conclusion.of(submitted.conclusionCode())));
          finished = true;
          break;
        }
        ToolExecution exec = executeTool(call.name(), args, input);
        messages.add(ChatMsg.tool(call.id(), exec.resultJson()));
        traces.add(new ToolTrace(call.name(), args, exec.hits(), exec.note()));
        citations.addAll(exec.citations());
        evidenceFacts.addAll(exec.evidenceFacts());
        emitToolResult(progress, call.name(), exec.hits(), exec.note());
      }
      if (finished) {
        break;
      }
    }

    // 合并：模型结论不得轻于规则下限
    Conclusion agentConclusion = submitted == null ? null : Conclusion.of(submitted.conclusionCode());
    ConclusionPolicy.Decision decision = ConclusionPolicy.merge(ruleConclusion, agentConclusion);
    if (decision.overriddenByFloor()) {
      emit(progress, "模型结论 " + label(agentConclusion) + " 低于规则下限，已按 "
          + label(ruleConclusion) + " 处理");
    }
    // 模型未提交或失败时标记降级来源，便于前端与审计识别
    String source = submitted == null ? "RULE_FALLBACK" : decision.source();
    boolean floorApplied = decision.overriddenByFloor();
    if (answer.length() == 0) {
      answer.append(defaultAnswer(decision.conclusion(), floorApplied, submitted == null));
    }
    log.info("Agent 判定完成 hazard={} rounds={} tools={} citations={} source={} floor={} tokens={}",
        input.hazardCode(), rounds, traces.size(), citations.size(), source, floorApplied, total.totalTokens());
    return new AgentResult(decision.conclusion(), source, floorApplied, degraded,
        reasoning.toString(), answer.toString(), submitted == null ? "" : submitted.rationale(),
        dedup(citations), traces, evidenceFacts, total, rounds);
  }

  /** 模型提交的结论。 */
  private record Submitted(
      String conclusionCode, String rationale, List<String> evidenceFacts, List<String> citedClauses) {}

  /** 解析 record_conclusion 的参数，缺字段时给出安全默认。 */
  private Submitted parseSubmission(Map<String, Object> args) {
    String code = str(args.get("conclusion"));
    String rationale = str(args.get("rationale"));
    List<String> facts = strList(args.get("evidenceFacts"));
    List<String> clauses = strList(args.get("citedClauses"));
    return new Submitted(code == null ? "" : code, rationale == null ? "" : rationale, facts, clauses);
  }

  private String buildSystemPrompt(AssessInput input) {
    return """
        你是职业健康检查辅助判定助手。你的结论为建议性质，必须经主检医师复核签字后生效。

        工作要求：
        1. 你可以自主调用工具检索标准条款，自行决定检索什么、用什么过滤维度。
           不确定危害因素编码、检查阶段等取值时，先调用 describe_metadata 探查。
        2. 检索到条款后，结合体检检查项事实判断是否存在职业禁忌证或疑似职业病。
        3. 必须调用 record_conclusion 提交结论，不能只输出文字。
        4. 结论只能从以下四类中选择（源自 GBZ 188 第 4.8.2.2 条）：
           NO_ABNORMALITY（目前未见异常）、OTHER_ABNORMALITY（其他疾病或异常）、
           OCCUPATIONAL_TABOO（职业禁忌证）、SUSPECTED_OCCUPATIONAL_DISEASE（疑似职业病）。
        5. 系统已给出规则引擎的结论下限，你不得给出比它更轻的结论；
           如果你发现更严重的问题，可以给出更重的结论，并在 rationale 中说明依据。
        6. 不得使用"确诊""患有职业病"等确定性表述；表述应体现"疑似/建议复核"。
        7. 判定依据必须引用具体条款编号与检查项数值，不得凭空推测。
        """;
  }

  private String buildUserPrompt(AssessInput input, RuleEvaluator.DetailedMatch ruleMatch) {
    StringBuilder sb = new StringBuilder();
    sb.append("危害因素：").append(nz(input.hazardName()));
    if (input.hazardCode() != null && !input.hazardCode().isBlank()) {
      sb.append("（编码 ").append(input.hazardCode()).append("）");
    }
    sb.append("\n");
    if (input.examSummary() != null && !input.examSummary().isBlank()) {
      sb.append("检查项事实：").append(input.examSummary()).append("\n");
    } else {
      sb.append("检查项事实：").append(input.facts()).append("\n");
    }
    if (ruleMatch != null) {
      sb.append("规则引擎下限：").append(label(Conclusion.of(ruleMatch.conclusion())))
          .append("（规则 ").append(ruleMatch.ruleCode()).append("，")
          .append("触发事实 ").append(ruleMatch.matchedFacts()).append("）\n");
    } else {
      sb.append("规则引擎下限：无规则命中\n");
    }
    sb.append("请检索适用条款后调用 record_conclusion 提交结论。");
    return sb.toString();
  }

  /** 工具声明：检索类工具来自共享目录（含动态枚举），仅补充判定专有工具。 */
  private List<LlmToolSpec> buildTools(AssessInput input) {
    List<LlmToolSpec> tools = new ArrayList<>(toolCatalog.retrievalTools());
    tools.add(new LlmToolSpec("list_exam_facts",
        "列出本次体检的全部检查项事实（编码、名称、数值、单位）。用于核对可用的判定事实。",
        Map.of("type", "object", "properties", Map.of(), "required", List.of())));
    tools.add(new LlmToolSpec(FINAL_TOOL,
        "提交最终判定结论。必须在充分检索后调用，且只能调用一次。",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "conclusion", Map.of("type", "string",
                    "description", "结论编码：NO_ABNORMALITY/OTHER_ABNORMALITY/OCCUPATIONAL_TABOO/"
                        + "SUSPECTED_OCCUPATIONAL_DISEASE",
                    "enum", List.of("NO_ABNORMALITY", "OTHER_ABNORMALITY", "OCCUPATIONAL_TABOO",
                        "SUSPECTED_OCCUPATIONAL_DISEASE")),
                "rationale", Map.of("type", "string",
                    "description", "判定依据，须引用具体条款编号与检查项数值"),
                "evidenceFacts", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "支撑结论的检查项编码列表"),
                "citedClauses", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "引用条款，格式 标准号#条款编号")),
            "required", List.of("conclusion", "rationale"))));
    return tools;
  }

  /** 工具执行结果。 */
  private record ToolExecution(
      String resultJson, int hits, String note, List<CitedClause> citations, List<String> evidenceFacts) {}

  /** 执行工具：结果转 JSON 回填给模型，同时收集引用与证据事实。 */
  private ToolExecution executeTool(String name, Map<String, Object> args, AssessInput input) {
    try {
      return switch (name) {
        case "describe_metadata" -> {
          var discovery = retrievalTools.describeMetadata();
          yield new ToolExecution(toJson(discovery), discovery.dimensions().size(),
              "元数据维度发现完成", List.of(), List.of());
        }
        case "list_exam_facts" -> {
          Map<String, Object> facts = input.facts() == null ? Map.of() : input.facts();
          yield new ToolExecution(toJson(facts), facts.size(),
              "检查项事实 " + facts.size() + " 项", List.of(), new ArrayList<>(facts.keySet()));
        }
        case "clause_retrieve" -> {
          String query = str(args.get("query"));
          int topK = args.get("topK") == null ? 6 : Integer.parseInt(String.valueOf(args.get("topK")));
          var filters = new ClauseVectorStore.Filters(
              str(args.get("hazard")), str(args.get("standard")), str(args.get("appendixType")),
              str(args.get("phase")), str(args.get("checkClass")));
          var r = retrievalTools.retrieve(query, topK, filters);
          yield new ToolExecution(toClauseJson(r.clauses()), r.clauses().size(), r.call().note(),
              toCitations(r.clauses()), List.of());
        }
        case "clause_fetch" -> {
          var r = retrievalTools.fetch(str(args.get("standard")), str(args.get("clause")));
          yield new ToolExecution(toClauseJson(r.clauses()), r.clauses().size(), r.call().note(),
              toCitations(r.clauses()), List.of());
        }
        case "clause_expand" -> {
          long clauseId = args.get("clauseId") == null ? 0L : Long.parseLong(String.valueOf(args.get("clauseId")));
          var r = retrievalTools.expand(clauseId);
          yield new ToolExecution(toClauseJson(r.clauses()), r.clauses().size(), r.call().note(),
              toCitations(r.clauses()), List.of());
        }
        case "hazard_route" -> {
          var r = retrievalTools.route(str(args.get("hazard")));
          yield new ToolExecution(toClauseJson(r.clauses()), r.clauses().size(), r.call().note(),
              toCitations(r.clauses()), List.of());
        }
        default -> new ToolExecution("{\"error\":\"未知工具 " + name + "\"}", 0, "未知工具",
            List.of(), List.of());
      };
    } catch (Exception ex) {
      log.warn("判定工具执行失败 tool={} err={}", name, ex.getMessage());
      return new ToolExecution("{\"error\":\"" + ex.getMessage() + "\"}",
          0, "执行失败：" + ex.getMessage(), List.of(), List.of());
    }
  }

  private List<CitedClause> toCitations(List<ClauseRetrievalTools.RetrievedClause> clauses) {
    List<CitedClause> citations = new ArrayList<>();
    for (var c : clauses) {
      citations.add(new CitedClause(c.clauseId(), c.standardCode(), c.clauseNo(), c.title(),
          head(c.quote(), 600), c.pageNo(), c.source()));
    }
    return citations;
  }

  /** 去重：同标准同条款只保留首次引用。 */
  private List<CitedClause> dedup(List<CitedClause> citations) {
    Map<String, CitedClause> unique = new LinkedHashMap<>();
    for (CitedClause c : citations) {
      unique.putIfAbsent(c.standardCode() + "#" + c.clauseNo(), c);
    }
    return new ArrayList<>(unique.values());
  }

  private String toClauseJson(List<ClauseRetrievalTools.RetrievedClause> clauses) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var c : clauses) {
      Map<String, Object> row = new HashMap<>();
      row.put("clauseId", c.clauseId());
      row.put("standardCode", c.standardCode());
      row.put("clauseNo", c.clauseNo());
      row.put("title", c.title());
      row.put("quote", head(c.quote(), 700));
      row.put("pageNo", c.pageNo());
      row.put("phase", c.phase());
      row.put("source", c.source());
      rows.add(row);
    }
    return toJson(Map.of("count", rows.size(), "clauses", rows));
  }

  private String toToolCallsJson(List<LlmToolCall> calls) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (LlmToolCall call : calls) {
      rows.add(Map.of("id", call.id(), "type", "function",
          "function", Map.of("name", call.name(), "arguments", call.arguments())));
    }
    return toJson(rows);
  }

  private void appendReasoning(LlmToolResponse response, StringBuilder reasoning, AgentProgress progress) {
    if (response.reasoning() != null && !response.reasoning().isBlank()) {
      reasoning.append(response.reasoning());
      if (progress != null) {
        progress.onReasoning(response.reasoning());
      }
    }
  }

  private String defaultAnswer(Conclusion conclusion, boolean floorApplied, boolean modelMissing) {
    StringBuilder sb = new StringBuilder();
    sb.append("建议结论：").append(conclusion.getLabel()).append("。");
    if (floorApplied) {
      sb.append("模型给出的结论轻于规则引擎下限，已按规则下限处理，请主检医师重点复核。");
    } else if (modelMissing) {
      sb.append("模型未提交结构化结论，本次依据规则引擎结果给出，请主检医师复核。");
    }
    sb.append("本结论为建议性质，须经主检医师复核签字后生效。");
    return sb.toString();
  }

  private String label(Conclusion conclusion) {
    return conclusion == null ? "无" : conclusion.getLabel();
  }

  private void emit(AgentProgress progress, String text) {
    if (progress != null) {
      progress.onReasoning(text);
    }
  }

  private void emitTool(AgentProgress progress, String tool, Map<String, Object> args) {
    if (progress != null) {
      progress.onToolCall(tool, args);
    }
  }

  private void emitToolResult(AgentProgress progress, String tool, int hits, String note) {
    if (progress != null) {
      progress.onToolResult(tool, hits, note);
    }
  }

  private Map<String, Object> parseArgs(String arguments) {
    try {
      if (arguments == null || arguments.isBlank()) {
        return Map.of();
      }
      JsonNode node = objectMapper.readTree(arguments);
      Map<String, Object> map = new LinkedHashMap<>();
      node.fields().forEachRemaining(e -> map.put(e.getKey(), toJava(e.getValue())));
      return map;
    } catch (Exception e) {
      return Map.of();
    }
  }

  private Object toJava(JsonNode node) {
    if (node.isArray()) {
      List<Object> list = new ArrayList<>();
      for (JsonNode item : node) {
        list.add(toJava(item));
      }
      return list;
    }
    if (node.isObject()) {
      Map<String, Object> map = new LinkedHashMap<>();
      node.fields().forEachRemaining(e -> map.put(e.getKey(), toJava(e.getValue())));
      return map;
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    return node.asText();
  }

  private List<String> strList(Object value) {
    List<String> list = new ArrayList<>();
    if (value instanceof List<?> raw) {
      for (Object item : raw) {
        if (item != null && !String.valueOf(item).isBlank()) {
          list.add(String.valueOf(item));
        }
      }
    } else if (value != null && !String.valueOf(value).isBlank()) {
      list.add(String.valueOf(value));
    }
    return list;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private String nz(String text) {
    return text == null ? "" : text;
  }

  private String head(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }
}
