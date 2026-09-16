package com.occuspec.controller;

import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.rag.ClauseRetrievalTools;
import com.occuspec.rag.ClauseVectorStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索运维控制器：向量入库进度、检索试调用、危害路由试调用。
 */
@RestController
@RequestMapping("/admin/rag")
public class RagAdminController {
  private final ClauseRetrievalTools tools;
  private final ClauseVectorStore vectorStore;

  public RagAdminController(ClauseRetrievalTools tools, ClauseVectorStore vectorStore) {
    this.tools = tools;
    this.vectorStore = vectorStore;
  }

  /** 向量入库：分批向量化条款并写入 PG。 */
  @PostMapping("/embed")
  @AuditLog(action = "向量入库")
  public ApiResponse<ClauseRetrievalTools.EmbedResult> embed(
      @RequestParam(defaultValue = "16") int batchSize, @RequestParam(defaultValue = "500") int limit) {
    return ApiResponse.ok(tools.embedClauses(batchSize, limit));
  }

  /** 向量库状态。 */
  @GetMapping("/status")
  public ApiResponse<Map<String, Object>> status() {
    return ApiResponse.ok(Map.of("vectors", vectorStore.count()));
  }

  /** 检索试调用。 */
  @PostMapping("/retrieve")
  public ApiResponse<Map<String, Object>> retrieve(@RequestBody Map<String, Object> body) {
    String query = String.valueOf(body.getOrDefault("query", ""));
    int topK = body.get("topK") == null ? 5 : Integer.parseInt(String.valueOf(body.get("topK")));
    String hazard = body.get("hazard") == null ? null : String.valueOf(body.get("hazard"));
    String standard = body.get("standard") == null ? null : String.valueOf(body.get("standard"));
    var result = tools.retrieve(query, topK, hazard, standard);
    return ApiResponse.ok(Map.of("clauses", result.clauses(), "call", result.call()));
  }

  /** 危害路由试调用。 */
  @GetMapping("/route")
  public ApiResponse<Map<String, Object>> route(@RequestParam String hazard) {
    var result = tools.route(hazard);
    return ApiResponse.ok(Map.of("clauses", result.clauses(), "call", result.call()));
  }

  /** 精确取条款。 */
  @GetMapping("/fetch")
  public ApiResponse<Map<String, Object>> fetch(
      @RequestParam String standard, @RequestParam String clause) {
    var result = tools.fetch(standard, clause);
    return ApiResponse.ok(Map.of("clauses", result.clauses(), "call", result.call()));
  }

  /** 引用展开。 */
  @GetMapping("/expand")
  public ApiResponse<Map<String, Object>> expand(@RequestParam long clauseId) {
    var result = tools.expand(clauseId);
    return ApiResponse.ok(Map.of("clauses", result.clauses(), "call", result.call()));
  }

  /** 批量精确取条款（前端报告页用）。 */
  @PostMapping("/fetch-batch")
  public ApiResponse<List<Map<String, Object>>> fetchBatch(@RequestBody List<Map<String, String>> keys) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, String> key : keys) {
      var r = tools.fetch(key.get("standard"), key.get("clause"));
      for (var c : r.clauses()) {
        result.add(Map.of("standardCode", c.standardCode(), "clauseNo", c.clauseNo(),
            "title", c.title() == null ? "" : c.title(), "quote", c.quote() == null ? "" : c.quote(),
            "pageNo", c.pageNo() == null ? 0 : c.pageNo()));
      }
    }
    return ApiResponse.ok(result);
  }
}
