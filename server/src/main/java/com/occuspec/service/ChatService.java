package com.occuspec.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.llm.ChatMsg;
import com.occuspec.llm.LlmGateway;
import com.occuspec.llm.LlmToolCall;
import com.occuspec.llm.LlmToolResponse;
import com.occuspec.llm.LlmToolSpec;
import com.occuspec.llm.LlmUsage;
import com.occuspec.rag.ClauseRetrievalTools;
import com.occuspec.rag.ClauseVectorStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对话编排深模块：小接口 chat(消息历史) → 回答 + 引用 + 工具调用记录。
 *
 * <p>Agentic 循环：模型自主决定调用哪个检索工具、传什么参数；工具结果回填后继续推理，
 * 直到模型给出最终回答或达到轮次上限。检索参数（危害因素/阶段等）由模型自己选择，
 * 可用值通过元数据发现工具动态获取，不写死枚举。
 */
@Service
public class ChatService {
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  /** 工具调用最大轮次：防止模型陷入无效循环。 */
  private static final int MAX_ROUNDS = 5;

  private final LlmGateway llmGateway;
  private final ClauseRetrievalTools retrievalTools;
  private final ObjectMapper objectMapper;

  public ChatService(LlmGateway llmGateway, ClauseRetrievalTools retrievalTools, ObjectMapper objectMapper) {
    this.llmGateway = llmGateway;
    this.retrievalTools = retrievalTools;
    this.objectMapper = objectMapper;
  }

  /** 对话进度回调：供 SSE 推送推理、工具调用与正文增量。 */
  public interface ChatProgress {
    void onReasoning(String text);
    void onToolCall(String tool, Map<String, Object> args);
    void onToolResult(String tool, int hits, String note, List<Citation> citations);
    void onContent(String delta);
  }

  /** 引用条款。 */
  public record Citation(
      String standardCode, String clauseNo, String title, Integer pageNo, String quote) {}

  /** 一轮工具调用记录。 */
  public record ToolTrace(String tool, Map<String, Object> args, int hits, String note) {}

  /** 对话结果。 */
  public record ChatResult(
      String answer, String reasoning, List<Citation> citations,
      List<ToolTrace> toolTraces, LlmUsage usage, int rounds) {}

  /**
   * 执行对话：含 Agentic 工具循环。
   *
   * @param history 历史消息（不含本轮用户输入）
   * @param userInput 本轮用户输入
   * @param progress 进度回调（可空）
   */
  public ChatResult chat(List<ChatMsg> history, String userInput, ChatProgress progress) {
    List<ChatMsg> messages = new ArrayList<>(history);
    messages.add(ChatMsg.user(userInput));
    List<LlmToolSpec> tools = buildTools();
    List<Citation> citations = new ArrayList<>();
    List<ToolTrace> toolTraces = new ArrayList<>();
    StringBuilder reasoning = new StringBuilder();
    StringBuilder answer = new StringBuilder();
    LlmUsage total = LlmUsage.empty();
    int rounds = 0;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      rounds++;
      LlmToolResponse response = llmGateway.completeWithTools(messages, tools);
      total = total.add(response.usage());
      if (response.reasoning() != null && !response.reasoning().isBlank()) {
        reasoning.append(response.reasoning());
        if (progress != null) {
          progress.onReasoning(response.reasoning());
        }
      }
      if (response.content() != null && !response.content().isBlank()) {
        answer.append(response.content());
        if (progress != null) {
          progress.onContent(response.content());
        }
      }
      if (!response.hasToolCalls()) {
        break;
      }
      // 回填助手的工具调用请求
      messages.add(new ChatMsg("assistant", response.content(), null, null, toToolCallsJson(response.toolCalls())));
      for (LlmToolCall call : response.toolCalls()) {
        Map<String, Object> args = parseArgs(call.arguments());
        if (progress != null) {
          progress.onToolCall(call.name(), args);
        }
        ToolExecution exec = executeTool(call.name(), args);
        messages.add(ChatMsg.tool(call.id(), exec.resultJson()));
        toolTraces.add(new ToolTrace(call.name(), args, exec.hits(), exec.note()));
        citations.addAll(exec.citations());
        if (progress != null) {
          progress.onToolResult(call.name(), exec.hits(), exec.note(), exec.citations());
        }
      }
    }
    // 未产生正文时给出兜底提示，避免空回答
    if (answer.length() == 0) {
      answer.append("未能从标准条款中找到明确依据，建议补充接触史或检查结果后重试。");
      if (progress != null) {
        progress.onContent(answer.toString());
      }
    }
    log.info("对话完成 rounds={} citations={} tools={} tokens={}",
        rounds, citations.size(), toolTraces.size(), total.totalTokens());
    return new ChatResult(answer.toString(), reasoning.toString(), dedup(citations), toolTraces, total, rounds);
  }

  /** 工具声明：参数用文字说明可选值范围，实际合法值经元数据发现工具获取。 */
  private List<LlmToolSpec> buildTools() {
    List<LlmToolSpec> tools = new ArrayList<>();
    tools.add(new LlmToolSpec("describe_metadata",
        "列出可用的检索过滤维度及其取值分布。当你不确定危害因素编码、检查阶段等取值时，先调用本工具。",
        Map.of("type", "object", "properties", Map.of(), "required", List.of())));
    tools.add(new LlmToolSpec("clause_retrieve",
        "按语义检索职业卫生标准条款。可选过滤维度：hazard（危害因素编码，取值见 describe_metadata）、"
            + "phase（检查阶段：上岗前/在岗期间/离岗时/应急）、checkClass（必检/补充/选检）、"
            + "standard（标准号，如 GBZ 188-2025）、appendixType（规范性/资料性）。不确定时不要传过滤条件。",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "检索文本，用中文描述要查的内容"),
                "topK", Map.of("type", "integer", "description", "返回条数，默认 6"),
                "hazard", Map.of("type", "string", "description", "危害因素编码，可选"),
                "phase", Map.of("type", "string", "description", "检查阶段，可选"),
                "checkClass", Map.of("type", "string", "description", "检查类别，可选"),
                "standard", Map.of("type", "string", "description", "标准号，可选"),
                "appendixType", Map.of("type", "string", "description", "附录类型，可选")),
            "required", List.of("query"))));
    tools.add(new LlmToolSpec("clause_fetch",
        "按标准号与条款编号精确获取条款原文。当你已知具体条款（如 GBZ 188-2025 的 7.1.1.1）时使用。",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "standard", Map.of("type", "string", "description", "标准号"),
                "clause", Map.of("type", "string", "description", "条款编号，如 7.1.1.1")),
            "required", List.of("standard", "clause"))));
    tools.add(new LlmToolSpec("clause_expand",
        "沿条款引用链展开：取回该条款引用的其他条款（如\"同7.1.1.1\"、\"按GBZ 49\"、附录引用）。",
        Map.of(
            "type", "object",
            "properties", Map.of("clauseId", Map.of("type", "integer", "description", "条款主键")),
            "required", List.of("clauseId"))));
    tools.add(new LlmToolSpec("hazard_route",
        "按危害因素列出相关标准的节级条款清单，用于快速了解某危害因素涉及哪些标准。",
        Map.of(
            "type", "object",
            "properties", Map.of("hazard", Map.of("type", "string", "description", "危害因素编码")),
            "required", List.of("hazard"))));
    return tools;
  }

  /** 工具执行结果。 */
  private record ToolExecution(String resultJson, int hits, String note, List<Citation> citations) {}

  /** 执行工具：结果转 JSON 供模型继续推理，同时收集引用。 */
  private ToolExecution executeTool(String name, Map<String, Object> args) {
    try {
      return switch (name) {
        case "describe_metadata" -> {
          var discovery = retrievalTools.describeMetadata();
          yield new ToolExecution(toJson(discovery), discovery.dimensions().size(), "元数据维度发现完成", List.of());
        }
        case "clause_retrieve" -> {
          String query = str(args.get("query"));
          int topK = args.get("topK") == null ? 6 : Integer.parseInt(String.valueOf(args.get("topK")));
          var filters = new ClauseVectorStore.Filters(
              str(args.get("hazard")), str(args.get("standard")), str(args.get("appendixType")),
              str(args.get("phase")), str(args.get("checkClass")));
          var r = retrievalTools.retrieve(query, topK, filters);
          yield new ToolExecution(toClauseJson(r.clauses()), r.clauses().size(), r.call().note(),
              toCitations(r.clauses()));
        }
        case "clause_fetch" -> {
          var r = retrievalTools.fetch(str(args.get("standard")), str(args.get("clause")));
          yield new ToolExecution(toClauseJson(r.clauses()), r.clauses().size(), r.call().note(),
              toCitations(r.clauses()));
        }
        case "clause_expand" -> {
          long clauseId = args.get("clauseId") == null ? 0L : Long.parseLong(String.valueOf(args.get("clauseId")));
          var r = retrievalTools.expand(clauseId);
          yield new ToolExecution(toClauseJson(r.clauses()), r.clauses().size(), r.call().note(),
              toCitations(r.clauses()));
        }
        case "hazard_route" -> {
          var r = retrievalTools.route(str(args.get("hazard")));
          yield new ToolExecution(toClauseJson(r.clauses()), r.clauses().size(), r.call().note(),
              toCitations(r.clauses()));
        }
        default -> new ToolExecution("{\"error\":\"未知工具 " + name + "\"}", 0, "未知工具", List.of());
      };
    } catch (Exception ex) {
      log.warn("工具执行失败 tool={} err={}", name, ex.getMessage());
      return new ToolExecution("{\"error\":\"" + ex.getMessage() + "\"}", 0, "执行失败：" + ex.getMessage(), List.of());
    }
  }

  private List<Citation> toCitations(List<ClauseRetrievalTools.RetrievedClause> clauses) {
    List<Citation> citations = new ArrayList<>();
    for (var c : clauses) {
      citations.add(new Citation(c.standardCode(), c.clauseNo(), c.title(), c.pageNo(),
          head(c.quote(), 400)));
    }
    return citations;
  }

  /** 去重：同标准同条款只保留首次引用。 */
  private List<Citation> dedup(List<Citation> citations) {
    Map<String, Citation> unique = new LinkedHashMap<>();
    for (Citation c : citations) {
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

  private Map<String, Object> parseArgs(String arguments) {
    try {
      if (arguments == null || arguments.isBlank()) {
        return Map.of();
      }
      return objectMapper.readValue(arguments, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (Exception e) {
      return Map.of();
    }
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

  private String head(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }
}
