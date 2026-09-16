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
import com.occuspec.rag.RetrievalToolCatalog;
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
  private final RetrievalToolCatalog toolCatalog;
  private final ObjectMapper objectMapper;

  public ChatService(LlmGateway llmGateway, ClauseRetrievalTools retrievalTools,
      RetrievalToolCatalog toolCatalog, ObjectMapper objectMapper) {
    this.llmGateway = llmGateway;
    this.retrievalTools = retrievalTools;
    this.toolCatalog = toolCatalog;
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
      // 流式调用：正文与推理逐增量推送，工具调用整轮结束后返回
      LlmToolResponse response = llmGateway.streamWithTools(
          messages, tools,
          delta -> {
            answer.append(delta);
            if (progress != null) {
              progress.onContent(delta);
            }
          },
          delta -> {
            reasoning.append(delta);
            if (progress != null) {
              progress.onReasoning(delta);
            }
          });
      total = total.add(response.usage());
      // 流中已增量回调，此处不再重复推送正文与推理（仅做累计兜底）
      if (response.degraded()) {
        break;
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

  /** 工具声明：检索类工具统一来自 {@link RetrievalToolCatalog}（含动态枚举）。 */
  private List<LlmToolSpec> buildTools() {
    return toolCatalog.retrievalTools();
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
