package com.occuspec.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.occuspec.common.HotCache;
import com.occuspec.entity.Clause;
import com.occuspec.llm.LlmGateway;
import com.occuspec.mapper.ClauseMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 判定检索工具集：暴露给判定流程的检索能力（Agentic RAG 的工具层）。
 * 每次调用记录决策日志（查了什么/过滤条件/命中数/是否放宽），供审计回放。
 */
@Component
public class ClauseRetrievalTools {
  private static final Logger log = LoggerFactory.getLogger(ClauseRetrievalTools.class);
  /** 条款全文的热点缓存 TTL：条款内容近乎静态，可较长。 */
  private static final Duration CLAUSE_TTL = Duration.ofHours(6);
  private final ClauseMapper clauseMapper;
  private final ClauseVectorStore vectorStore;
  private final LlmGateway llmGateway;
  private final ObjectMapper objectMapper;
  private final Executor batchExecutor;
  private final HotCache hotCache;

  public ClauseRetrievalTools(
      ClauseMapper clauseMapper, ClauseVectorStore vectorStore, LlmGateway llmGateway,
      ObjectMapper objectMapper, @Qualifier("batchExecutor") Executor batchExecutor,
      HotCache hotCache) {
    this.clauseMapper = clauseMapper;
    this.vectorStore = vectorStore;
    this.llmGateway = llmGateway;
    this.objectMapper = objectMapper;
    this.batchExecutor = batchExecutor;
    this.hotCache = hotCache;
  }

  /** 工具调用记录。 */
  public record ToolCall(String tool, Map<String, Object> args, int hits, String note) {}

  /** 召回的条款（含来源标注：向量/精确/展开/路由）。 */
  public record RetrievedClause(
      long clauseId, String standardCode, String clauseNo, String title,
      String quote, Integer pageNo, String phase, String hazardCode, String source, double score) {}

  /**
   * 语义检索 + 元数据过滤（软加权优先，过滤无结果时显式放宽并留痕）。
   *
   * @param query 查询文本
   * @param topK 返回条数
   * @param filters 元数据过滤条件（可空）
   */
  public RetrievalResult retrieve(String query, int topK, ClauseVectorStore.Filters filters) {
    ClauseVectorStore.Filters f = filters == null ? ClauseVectorStore.Filters.NONE : filters;
    Map<String, Object> args = new HashMap<>();
    args.put("query", query);
    args.put("topK", topK);
    args.put("filters", filterMap(f));
    List<float[]> vecs = llmGateway.embed(List.of(query));
    List<RetrievedClause> clauses = new ArrayList<>();
    String note;
    boolean relaxed = false;
    if (vecs.isEmpty() || vecs.get(0).length == 0) {
      note = "向量服务不可用，已降级为关键词直查";
      clauses.addAll(keywordSearch(query, topK, f));
    } else {
      var hits = vectorStore.search(vecs.get(0), topK, f);
      clauses.addAll(toClauses(hits, "VECTOR"));
      note = "向量召回 " + hits.size() + " 条（命中元数据维度加权排序）";
      // 召回为空且带了过滤条件：显式放宽并留痕，不静默容忍
      if (clauses.isEmpty() && f.anyPresent()) {
        var retry = vectorStore.search(vecs.get(0), topK, ClauseVectorStore.Filters.NONE);
        clauses.addAll(toClauses(retry, "VECTOR_RELAXED"));
        relaxed = true;
        note = "过滤条件无命中，已放宽为全库检索，命中 " + retry.size()
            + " 条（过滤维度：" + filterMap(f) + "）";
      }
    }
    ToolCall call = new ToolCall("clause_retrieve", args, clauses.size(), note);
    log.info("检索 retrieve query={} filters={} hits={} relaxed={} note={}",
        head(query, 50), filterMap(f), clauses.size(), relaxed, note);
    return new RetrievalResult(clauses, call);
  }

  /** 兼容旧签名：仅按危害因素与标准号过滤。 */
  public RetrievalResult retrieve(String query, int topK, String hazardCode, String standardCode) {
    return retrieve(query, topK, new ClauseVectorStore.Filters(hazardCode, standardCode, null, null, null));
  }

  /**
   * 元数据发现工具：返回各维度的可用取值与数量，供 Agent 决定如何过滤。
   * 值域动态来自向量库与数据库，新增危害因素无需改代码。
   * 维度分布是全表聚合，代价高且变化慢，走热点缓存。
   */
  public MetadataDiscovery describeMetadata() {
    String cached = hotCache.get("occuspec:metadata:discovery", Duration.ofMinutes(30),
        () -> {
          try {
            return objectMapper.writeValueAsString(buildMetadataDiscovery());
          } catch (Exception ex) {
            log.debug("元数据发现序列化失败 err={}", ex.getMessage());
            return null;
          }
        });
    if (cached == null) {
      return buildMetadataDiscovery();
    }
    try {
      return objectMapper.readValue(cached, MetadataDiscovery.class);
    } catch (Exception ex) {
      return buildMetadataDiscovery();
    }
  }

  private MetadataDiscovery buildMetadataDiscovery() {
    Map<String, Map<String, Integer>> dimensions = new HashMap<>();
    for (String dim : List.of("hazard_code", "phase", "check_class", "appendix_type", "standard_code")) {
      Map<String, Integer> dist = vectorStore.distribution(dim);
      if (!dist.isEmpty()) {
        dimensions.put(dim, dist);
      }
    }
    // 危害因素编码到中文名的映射，便于模型理解枚举含义
    Map<String, String> hazardNames = new HashMap<>();
    Map<String, Integer> hazardDist = dimensions.getOrDefault("hazard_code", Map.of());
    if (!hazardDist.isEmpty()) {
      var ids = hazardDist.keySet().stream().toList();
      var hazards = clauseMapper.selectList(new LambdaQueryWrapper<Clause>()
          .in(Clause::getHazardCode, ids).last("LIMIT 200"));
      for (Clause c : hazards) {
        if (c.getHazardCode() != null && !c.getHazardCode().isBlank() && c.getTitle() != null) {
          hazardNames.putIfAbsent(c.getHazardCode(), c.getTitle());
        }
      }
    }
    return new MetadataDiscovery(dimensions, hazardNames,
        "过滤条件用于提升召回精确率；不确定时可不传，系统按向量相似度加权排序");
  }

  /** 元数据发现结果。 */
  public record MetadataDiscovery(
      Map<String, Map<String, Integer>> dimensions, Map<String, String> hazardNames, String hint) {}

  /** 缓存统计：供运维观测与基准测试读取。 */
  public HotCache.Stats cacheStats() {
    return hotCache.stats();
  }

  /** 按（标准号，条款编号）精确取条款原文与页码。 */
  public RetrievalResult fetch(String standardCode, String clauseNo) {
    Map<String, Object> args = new HashMap<>();
    args.put("standard", standardCode);
    args.put("clause", clauseNo);
    List<RetrievedClause> clauses = new ArrayList<>();
    Clause clause = loadClause(standardCode, clauseNo);
    String note;
    if (clause == null) {
      note = "未找到精确条款";
    } else {
      clauses.add(toClause(clause, clause.getContent(), "EXACT", 0));
      note = "精确命中 1 条";
    }
    return new RetrievalResult(clauses, new ToolCall("clause_fetch", args, clauses.size(), note));
  }

  /**
   * 精确条款查询（带热点缓存）：Agent 常反复 fetch 同一条款，
   * cache-aside 减少数据库读；空结果以短 TTL 占位防穿透。
   */
  private Clause loadClause(String standardCode, String clauseNo) {
    if (standardCode == null || clauseNo == null) {
      return null;
    }
    String key = "occuspec:clause:exact:" + standardCode + ":" + clauseNo;
    String json = hotCache.get(key, CLAUSE_TTL, () -> {
      Clause found = clauseMapper.selectOne(new LambdaQueryWrapper<Clause>()
          .eq(Clause::getStandardCode, standardCode)
          .eq(Clause::getClauseNo, clauseNo)
          .last("LIMIT 1"));
      if (found == null) {
        return null;
      }
      try {
        return objectMapper.writeValueAsString(found);
      } catch (Exception ex) {
        log.debug("条款序列化失败 std={} no={} err={}", standardCode, clauseNo, ex.getMessage());
        return null;
      }
    });
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, Clause.class);
    } catch (Exception ex) {
      log.debug("条款反序列化失败 std={} no={} err={}", standardCode, clauseNo, ex.getMessage());
      return null;
    }
  }

  /** 沿引用回链展开：解析条款 relations 字段中的 SAME/REF/APPENDIX，取回引用条款。 */
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
          String no = text.substring("SAME:".length());
          Clause target = clauseMapper.selectOne(new LambdaQueryWrapper<Clause>()
              .eq(Clause::getStandardCode, clause.getStandardCode())
              .eq(Clause::getClauseNo, no)
              .last("LIMIT 1"));
          if (target != null) {
            clauses.add(toClause(target, head(target.getContent(), 600), "EXPAND_SAME", 0));
          }
        } else if (text.startsWith("APPENDIX:")) {
          String letter = text.substring("APPENDIX:".length());
          var list = clauseMapper.selectList(new LambdaQueryWrapper<Clause>()
              .eq(Clause::getStandardCode, clause.getStandardCode())
              .likeRight(Clause::getClauseNo, letter + ".")
              .last("LIMIT 5"));
          for (Clause target : list) {
            clauses.add(toClause(target, head(target.getContent(), 600), "EXPAND_APPENDIX", 0));
          }
        }
      }
    } catch (Exception ex) {
      log.warn("引用展开失败 clauseId={} err={}", clauseId, ex.getMessage());
    }
    return new RetrievalResult(clauses,
        new ToolCall("clause_expand", args, clauses.size(), "展开引用 " + clauses.size() + " 条"));
  }

  /**
   * 危害因素路由：按元数据直查节级条款。
   * 节级判定改为显式规则（编号段数 ≤ 2，如 7.1），不再用字符串长度魔法数。
   */
  public RouteResult route(String hazardCode) {
    var byMeta = clauseMapper.selectList(new LambdaQueryWrapper<Clause>()
        .eq(Clause::getHazardCode, hazardCode)
        .last("LIMIT 200"));
    List<RetrievedClause> clauses = new ArrayList<>();
    for (Clause clause : byMeta) {
      if (!isSectionLevel(clause.getClauseNo())) {
        continue;
      }
      clauses.add(toClause(clause, head(clause.getContent(), 300), "ROUTE_META", 0));
    }
    String note = "元数据直查节级条款 " + clauses.size() + " 条";
    return new RouteResult(clauses, new ToolCall("hazard_route",
        Map.of("hazard", hazardCode), clauses.size(), note));
  }

  /** 节级条款判定：GBZ 188 的危害因素节为两段编号（如 7.1），阶段条款三段及以上。 */
  private boolean isSectionLevel(String clauseNo) {
    if (clauseNo == null || clauseNo.isBlank() || clauseNo.startsWith("DOC:")) {
      return false;
    }
    long dots = clauseNo.chars().filter(ch -> ch == '.').count();
    return dots <= 1;
  }

  private List<RetrievedClause> keywordSearch(String query, int topK, ClauseVectorStore.Filters f) {
    var wrapper = new LambdaQueryWrapper<Clause>().like(Clause::getContent, head(query, 20));
    if (f.hazardCode() != null && !f.hazardCode().isBlank()) {
      wrapper.eq(Clause::getHazardCode, f.hazardCode());
    }
    if (f.standardCode() != null && !f.standardCode().isBlank()) {
      wrapper.eq(Clause::getStandardCode, f.standardCode());
    }
    if (f.phase() != null && !f.phase().isBlank()) {
      wrapper.eq(Clause::getPhase, f.phase());
    }
    wrapper.last("LIMIT " + Math.max(1, Math.min(topK, 20)));
    List<RetrievedClause> result = new ArrayList<>();
    for (Clause clause : clauseMapper.selectList(wrapper)) {
      result.add(toClause(clause, head(clause.getContent(), 600), "KEYWORD", 0));
    }
    return result;
  }

  private List<RetrievedClause> toClauses(List<ClauseVectorStore.Hit> hits, String source) {
    if (hits.isEmpty()) {
      return List.of();
    }
    // 批量取回，避免逐条查询造成 N+1
    List<Long> ids = hits.stream().map(ClauseVectorStore.Hit::clauseId).toList();
    Map<Long, Clause> byId = new HashMap<>();
    for (Clause clause : clauseMapper.selectBatchIds(ids)) {
      byId.put(clause.getId(), clause);
    }
    List<RetrievedClause> result = new ArrayList<>();
    for (ClauseVectorStore.Hit hit : hits) {
      Clause clause = byId.get(hit.clauseId());
      if (clause == null) {
        continue;
      }
      result.add(toClause(clause, head(clause.getContent(), 600), source, hit.distance()));
    }
    return result;
  }

  private RetrievedClause toClause(Clause clause, String quote, String source, double score) {
    return new RetrievedClause(clause.getId(), clause.getStandardCode(), clause.getClauseNo(),
        clause.getTitle(), quote, clause.getPageNo(), clause.getPhase(),
        clause.getHazardCode(), source, score);
  }

  private Map<String, Object> filterMap(ClauseVectorStore.Filters f) {
    Map<String, Object> map = new HashMap<>();
    if (f.hazardCode() != null && !f.hazardCode().isBlank()) {
      map.put("hazard", f.hazardCode());
    }
    if (f.standardCode() != null && !f.standardCode().isBlank()) {
      map.put("standard", f.standardCode());
    }
    if (f.phase() != null && !f.phase().isBlank()) {
      map.put("phase", f.phase());
    }
    if (f.checkClass() != null && !f.checkClass().isBlank()) {
      map.put("checkClass", f.checkClass());
    }
    if (f.appendixType() != null && !f.appendixType().isBlank()) {
      map.put("appendixType", f.appendixType());
    }
    return map;
  }

  /**
   * 向量入库：条款分批向量化后写入 PG。
   * 并发分批 + 信号量限流（上游每分钟 2000 次请求、100 万 token 上限），
   * 信号量按批调用的 token 预算动态获取，避免超限。
   */
  public EmbedResult embedClauses(int batchSize, int limit) {
    List<Clause> clauses = clauseMapper.selectList(new LambdaQueryWrapper<Clause>()
        .notLike(Clause::getClauseNo, "DOC:")
        .orderByAsc(Clause::getId)
        .last("LIMIT " + Math.max(1, limit)));
    if (clauses.isEmpty()) {
      return new EmbedResult(0, 0);
    }
    // 分批
    List<List<Clause>> batches = new ArrayList<>();
    for (int i = 0; i < clauses.size(); i += batchSize) {
      batches.add(clauses.subList(i, Math.min(i + batchSize, clauses.size())));
    }
    AtomicInteger done = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    AtomicLong tokensUsed = new AtomicLong();
    // 并发度受限：上游 QPS 与 token 双重约束下，4 路并发足够且安全
    Semaphore concurrency = new Semaphore(4);
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (List<Clause> batch : batches) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          concurrency.acquire();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          failed.addAndGet(batch.size());
          return;
        }
        try {
          List<String> texts = batch.stream().map(this::embedText).toList();
          tokensUsed.addAndGet(texts.stream().mapToLong(t -> t.length()).sum());
          List<float[]> vecs = llmGateway.embed(texts);
          if (vecs.size() != batch.size()) {
            failed.addAndGet(batch.size());
            return;
          }
          for (int j = 0; j < batch.size(); j++) {
            Clause clause = batch.get(j);
            try {
              vectorStore.upsert(clause.getId(), clause.getStandardCode(), clause.getClauseNo(),
                  head(clause.getContent(), 2000), vecs.get(j), buildMetadata(clause).toString());
              done.incrementAndGet();
            } catch (Exception ex) {
              failed.incrementAndGet();
              log.warn("向量写入失败 clauseId={} err={}", clause.getId(), ex.getMessage());
            }
          }
        } catch (Exception ex) {
          failed.addAndGet(batch.size());
          log.warn("批次向量化失败 size={} err={}", batch.size(), ex.getMessage());
        } finally {
          concurrency.release();
        }
      }, batchExecutor);
      futures.add(future);
    }
    // 整体超时保护：单批最长 5 分钟，避免上游挂死拖垮入库
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .orTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
          .join();
    } catch (Exception ex) {
      log.warn("向量入库存在超时或失败任务 err={}", ex.getMessage());
    }
    log.info("向量入库完成 done={} failed={} batches={} charsApprox={}",
        done.get(), failed.get(), batches.size(), tokensUsed.get());
    return new EmbedResult(done.get(), failed.get());
  }

  /** 组装向量元数据：判定与过滤依赖的维度在此统一落库。 */
  private ObjectNode buildMetadata(Clause clause) {
    ObjectNode meta = objectMapper.createObjectNode();
    meta.put("standard_code", clause.getStandardCode() == null ? "" : clause.getStandardCode());
    meta.put("clause_no", clause.getClauseNo() == null ? "" : clause.getClauseNo());
    meta.put("hazard_code", clause.getHazardCode() == null ? "" : clause.getHazardCode());
    meta.put("appendix_type", clause.getAppendixType() == null ? "" : clause.getAppendixType());
    meta.put("phase", clause.getPhase() == null ? "" : clause.getPhase());
    meta.put("check_class", clause.getCheckClass() == null ? "" : clause.getCheckClass());
    if (clause.getPageNo() != null) {
      meta.put("page_no", clause.getPageNo());
    }
    return meta;
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
