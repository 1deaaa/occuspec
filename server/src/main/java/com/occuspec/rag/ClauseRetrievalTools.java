package com.occuspec.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.occuspec.entity.Clause;
import com.occuspec.llm.LlmGateway;
import com.occuspec.mapper.ClauseMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 判定检索工具集：暴露给判定 Agent 的检索能力（Agentic RAG 的工具层）。
 * 每次调用记录决策日志（查了什么/过滤条件/命中数），供审计回放。
 */
@Component
public class ClauseRetrievalTools {
  private static final Logger log = LoggerFactory.getLogger(ClauseRetrievalTools.class);
  private final ClauseMapper clauseMapper;
  private final ClauseVectorStore vectorStore;
  private final LlmGateway llmGateway;
  private final ObjectMapper objectMapper;

  public ClauseRetrievalTools(
      ClauseMapper clauseMapper, ClauseVectorStore vectorStore, LlmGateway llmGateway, ObjectMapper objectMapper) {
    this.clauseMapper = clauseMapper;
    this.vectorStore = vectorStore;
    this.llmGateway = llmGateway;
    this.objectMapper = objectMapper;
  }

  /** 工具调用记录。 */
  public record ToolCall(String tool, Map<String, Object> args, int hits, String note) {}

  /** 召回的条款（含来源标注：向量/精确/展开）。 */
  public record RetrievedClause(
      long clauseId, String standardCode, String clauseNo, String title,
      String quote, Integer pageNo, String source, double score) {}

  /**
   * 语义检索 + 元数据过滤。
   *
   * @param query 查询文本
   * @param topK 返回条数
   * @param hazardCode 危害因素过滤（可空）
   * @param standardCode 标准号过滤（可空）
   */
  public RetrievalResult retrieve(String query, int topK, String hazardCode, String standardCode) {
    Map<String, Object> args = Map.of("query", query, "topK", topK,
        "hazard", hazardCode == null ? "" : hazardCode,
        "standard", standardCode == null ? "" : standardCode);
    List<float[]> vecs = llmGateway.embed(List.of(query));
    List<RetrievedClause> clauses = new ArrayList<>();
    String note;
    if (vecs.isEmpty() || vecs.get(0).length == 0) {
      // 向量不可用：降级为关键词直查
      note = "向量服务不可用，已降级为关键词直查";
      clauses.addAll(keywordSearch(query, topK, hazardCode, standardCode));
    } else {
      var hits = vectorStore.search(vecs.get(0), topK, hazardCode, standardCode, null);
      for (var hit : hits) {
        Clause clause = clauseMapper.selectById(hit.clauseId());
        if (clause == null) {
          continue;
        }
        clauses.add(new RetrievedClause(clause.getId(), clause.getStandardCode(), clause.getClauseNo(),
            clause.getTitle(), head(clause.getContent(), 600), clause.getPageNo(), "VECTOR", hit.distance()));
      }
      note = "向量召回 " + hits.size() + " 条";
      // 召回质量差时二次检索：放宽过滤条件再查一次
      if (clauses.isEmpty() && ((hazardCode != null && !hazardCode.isBlank())
          || (standardCode != null && !standardCode.isBlank()))) {
        var retry = vectorStore.search(vecs.get(0), topK, null, null, null);
        for (var hit : retry) {
          Clause clause = clauseMapper.selectById(hit.clauseId());
          if (clause == null) {
            continue;
          }
          clauses.add(new RetrievedClause(clause.getId(), clause.getStandardCode(), clause.getClauseNo(),
              clause.getTitle(), head(clause.getContent(), 600), clause.getPageNo(), "VECTOR_RELAXED", hit.distance()));
        }
        note = "首次召回为空，已放宽过滤二次检索，命中 " + retry.size() + " 条";
      }
    }
    log.info("检索工具 retrieve query={} hazard={} hits={} note={}", head(query, 60), hazardCode, clauses.size(), note);
    return new RetrievalResult(clauses, new ToolCall("clause_retrieve", args, clauses.size(), note));
  }

  /** 按（标准号，条款编号）精确取条款原文与页码。 */
  public RetrievalResult fetch(String standardCode, String clauseNo) {
    Map<String, Object> args = new HashMap<>();
    args.put("standard", standardCode);
    args.put("clause", clauseNo);
    List<RetrievedClause> clauses = new ArrayList<>();
    Clause clause = clauseMapper.selectOne(new LambdaQueryWrapper<Clause>()
        .eq(Clause::getStandardCode, standardCode)
        .eq(Clause::getClauseNo, clauseNo)
        .last("LIMIT 1"));
    String note;
    if (clause == null) {
      note = "未找到精确条款";
    } else {
      clauses.add(new RetrievedClause(clause.getId(), clause.getStandardCode(), clause.getClauseNo(),
          clause.getTitle(), clause.getContent(), clause.getPageNo(), "EXACT", 0));
      note = "精确命中 1 条";
    }
    return new RetrievalResult(clauses, new ToolCall("clause_fetch", args, clauses.size(), note));
  }

  /**
   * 沿引用回链展开：解析条款 relations 字段中的 SAME/REF/APPENDIX，取回引用条款。
   */
  public RetrievalResult expand(long clauseId) {
    Map<String, Object> args = Map.of("clauseId", clauseId);
    List<RetrievedClause> clauses = new ArrayList<>();
    Clause clause = clauseMapper.selectById(clauseId);
    if (clause == null || clause.getRelations() == null) {
      return new RetrievalResult(clauses, new ToolCall("clause_expand", args, 0, "条款不存在或无引用"));
    }
    try {
      var relations = objectMapper.readTree(clause.getRelations());
      for (var rel : relations) {
        String text = rel.asText();
        if (text.startsWith("SAME:")) {
          String no = text.substring(5);
          Clause target = clauseMapper.selectOne(new LambdaQueryWrapper<Clause>()
              .eq(Clause::getStandardCode, clause.getStandardCode())
              .eq(Clause::getClauseNo, no)
              .last("LIMIT 1"));
          if (target != null) {
            clauses.add(new RetrievedClause(target.getId(), target.getStandardCode(), target.getClauseNo(),
                target.getTitle(), head(target.getContent(), 600), target.getPageNo(), "EXPAND_SAME", 0));
          }
        } else if (text.startsWith("APPENDIX:")) {
          String letter = text.substring(9);
          var list = clauseMapper.selectList(new LambdaQueryWrapper<Clause>()
              .eq(Clause::getStandardCode, clause.getStandardCode())
              .likeRight(Clause::getClauseNo, letter + ".")
              .last("LIMIT 5"));
          for (Clause target : list) {
            clauses.add(new RetrievedClause(target.getId(), target.getStandardCode(), target.getClauseNo(),
                target.getTitle(), head(target.getContent(), 600), target.getPageNo(), "EXPAND_APPENDIX", 0));
          }
        }
      }
    } catch (Exception ex) {
      log.warn("引用展开失败 clauseId={} err={}", clauseId, ex.getMessage());
    }
    return new RetrievalResult(clauses, new ToolCall("clause_expand", args, clauses.size(), "展开引用 " + clauses.size() + " 条"));
  }

  /**
   * 危害因素路由：元数据直查 + 向量召回两条路，列出适用标准与条款清单。
   */
  public RouteResult route(String hazardCode) {
    var byMeta = clauseMapper.selectList(new LambdaQueryWrapper<Clause>()
        .eq(Clause::getHazardCode, hazardCode)
        .last("LIMIT 50"));
    List<RetrievedClause> clauses = new ArrayList<>();
    for (Clause clause : byMeta) {
      if (clause.getClauseNo() != null && clause.getClauseNo().startsWith("DOC:")) {
        continue;
      }
      // 路由只保留节级条款（编号短），避免返回上千条细则
      if (clause.getClauseNo() != null && clause.getClauseNo().length() <= 6) {
        clauses.add(new RetrievedClause(clause.getId(), clause.getStandardCode(), clause.getClauseNo(),
            clause.getTitle(), head(clause.getContent(), 300), clause.getPageNo(), "ROUTE_META", 0));
      }
    }
    String note = "元数据直查节级条款 " + clauses.size() + " 条";
    return new RouteResult(clauses, new ToolCall("hazard_route",
        Map.of("hazard", hazardCode), clauses.size(), note));
  }

  private List<RetrievedClause> keywordSearch(String query, int topK, String hazardCode, String standardCode) {
    var wrapper = new LambdaQueryWrapper<Clause>().like(Clause::getContent, head(query, 20));
    if (hazardCode != null && !hazardCode.isBlank()) {
      wrapper.eq(Clause::getHazardCode, hazardCode);
    }
    if (standardCode != null && !standardCode.isBlank()) {
      wrapper.eq(Clause::getStandardCode, standardCode);
    }
    wrapper.last("LIMIT " + Math.max(1, Math.min(topK, 20)));
    List<RetrievedClause> result = new ArrayList<>();
    for (Clause clause : clauseMapper.selectList(wrapper)) {
      result.add(new RetrievedClause(clause.getId(), clause.getStandardCode(), clause.getClauseNo(),
          clause.getTitle(), head(clause.getContent(), 600), clause.getPageNo(), "KEYWORD", 0));
    }
    return result;
  }

  /** 向量入库：将条款正文分批向量化后写入 PG。 */
  public EmbedResult embedClauses(int batchSize, int limit) {
    var wrapper = new LambdaQueryWrapper<Clause>()
        .notLike(Clause::getClauseNo, "DOC:")
        .orderByAsc(Clause::getId)
        .last("LIMIT " + Math.max(1, limit));
    var clauses = clauseMapper.selectList(wrapper);
    int done = 0;
    int failed = 0;
    for (int i = 0; i < clauses.size(); i += batchSize) {
      var batch = clauses.subList(i, Math.min(i + batchSize, clauses.size()));
      List<String> texts = new ArrayList<>();
      for (Clause clause : batch) {
        texts.add(embedText(clause));
      }
      List<float[]> vecs = llmGateway.embed(texts);
      if (vecs.size() != batch.size()) {
        failed += batch.size();
        continue;
      }
      for (int j = 0; j < batch.size(); j++) {
        Clause clause = batch.get(j);
        try {
          ObjectNode meta = objectMapper.createObjectNode();
          meta.put("standard_code", clause.getStandardCode());
          meta.put("clause_no", clause.getClauseNo());
          meta.put("hazard_code", clause.getHazardCode() == null ? "" : clause.getHazardCode());
          meta.put("appendix_type", clause.getAppendixType() == null ? "" : clause.getAppendixType());
          if (clause.getPageNo() != null) {
            meta.put("page_no", clause.getPageNo());
          }
          vectorStore.upsert(clause.getId(), clause.getStandardCode(), clause.getClauseNo(),
              head(clause.getContent(), 2000), vecs.get(j), meta.toString());
          done++;
        } catch (Exception ex) {
          failed++;
          log.warn("向量写入失败 clauseId={} err={}", clause.getId(), ex.getMessage());
        }
      }
    }
    return new EmbedResult(done, failed);
  }

  private String embedText(Clause clause) {
    String title = clause.getTitle() == null ? "" : clause.getTitle();
    String content = clause.getContent() == null ? "" : clause.getContent();
    String text = clause.getStandardCode() + " " + clause.getClauseNo() + " " + title + "\n" + content;
    return head(text, 1500);
  }

  private String head(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }

  public record RetrievalResult(List<RetrievedClause> clauses, ToolCall call) {}
  public record RouteResult(List<RetrievedClause> clauses, ToolCall call) {}
  public record EmbedResult(int done, int failed) {}
}
