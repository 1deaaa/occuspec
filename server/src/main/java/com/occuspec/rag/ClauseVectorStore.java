package com.occuspec.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 条款向量存取：PG halfvec(1024) + HNSW，SQL 直写（向量类型需手工拼接字面量）。
 * 元数据过滤在 SQL 层完成；支持多维度组合、软加权与维度发现。
 */
@Component
public class ClauseVectorStore {
  /** 可为空的过滤条件集合：空值表示该维度不参与过滤。 */
  public record Filters(
      String hazardCode, String standardCode, String appendixType, String phase, String checkClass) {
    public static final Filters NONE = new Filters(null, null, null, null, null);

    /** 是否含至少一个有效条件。 */
    public boolean anyPresent() {
      return notBlank(hazardCode) || notBlank(standardCode) || notBlank(appendixType)
          || notBlank(phase) || notBlank(checkClass);
    }

    /** 命中的过滤维度个数，用于软加权。 */
    public int presentCount() {
      int n = 0;
      if (notBlank(hazardCode)) n++;
      if (notBlank(standardCode)) n++;
      if (notBlank(appendixType)) n++;
      if (notBlank(phase)) n++;
      if (notBlank(checkClass)) n++;
      return n;
    }

    private static boolean notBlank(String s) {
      return s != null && !s.isBlank();
    }
  }

  private final JdbcTemplate pg;
  private final int dimensions;

  public ClauseVectorStore(
      @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
      @Value("${occuspec.embedding.dimensions:1024}") int dimensions) {
    this.pg = pg;
    this.dimensions = dimensions;
  }

  /** 命中条款：distance 为向量距离，metadataHits 为额外命中的元数据维度数。 */
  public record Hit(
      long clauseId, String standardCode, String clauseNo, String chunkText,
      double distance, String metadata, int metadataHits) {}

  /** 写入单条向量（存在则更新）。 */
  public void upsert(long clauseId, String standardCode, String clauseNo, String chunkText,
      float[] embedding, String metadataJson) {
    if (embedding == null || embedding.length != dimensions) {
      throw new IllegalArgumentException(
          "向量维度不匹配，期望 " + dimensions + "，实际 " + (embedding == null ? 0 : embedding.length));
    }
    String literal = toLiteral(embedding);
    pg.update(
        "INSERT INTO clause_embedding (clause_id, standard_code, clause_no, chunk_text, embedding, metadata)"
            + " VALUES (?, ?, ?, ?, ?::halfvec, ?::jsonb)"
            + " ON CONFLICT (clause_id) DO UPDATE SET chunk_text = EXCLUDED.chunk_text,"
            + " embedding = EXCLUDED.embedding, metadata = EXCLUDED.metadata",
        clauseId, standardCode, clauseNo, chunkText, literal, metadataJson);
  }

  /**
   * 向量检索：余弦距离排序，支持多维度过滤。
   *
   * <p>两段式查询，目的是让 HNSW 索引真正生效：
   * 内层只用距离排序（ORDER BY embedding &lt;=&gt; ? LIMIT 候选数），可命中 HNSW；
   * 外层再按元数据命中维度数加权重排，取最终 topK。
   *
   * <p>若把加权表达式写进 ORDER BY 首列，规划器将无法使用 HNSW，退化为全表扫描
   * （实测 5933 行时 2.8ms，且随数据量线性增长）。两段式在数据量增长后优势更明显。
   *
   * @param query 查询向量
   * @param topK 最终返回条数
   * @param filters 硬过滤条件（可空）
   */
  public List<Hit> search(float[] query, int topK, Filters filters) {
    Filters f = filters == null ? Filters.NONE : filters;
    int limit = Math.max(1, topK);
    // 内层候选数：带过滤时多取一些，保证加权重排后有足够候选
    int candidate = f.anyPresent() ? Math.max(limit * 8, 40) : Math.max(limit * 4, 20);
    String vectorLiteral = toLiteral(query);
    String hitExpr = hitExpression(f);
    boolean weighted = !"0".equals(hitExpr);

    StringBuilder sql = new StringBuilder("SELECT clause_id, standard_code, clause_no, chunk_text,");
    sql.append(" metadata::text AS metadata, distance, ");
    sql.append(weighted ? "metadata_hits FROM (" : "0 AS metadata_hits FROM (");
    sql.append("SELECT clause_id, standard_code, clause_no, chunk_text, metadata,");
    sql.append(" embedding <-> ?::halfvec AS distance, ");
    sql.append(hitExpr).append(" AS metadata_hits");
    sql.append(" FROM clause_embedding WHERE 1=1");
    List<Object> args = new ArrayList<>();
    args.add(vectorLiteral);
    appendFilter(sql, args, "hazard_code", f.hazardCode());
    appendFilter(sql, args, "standard_code", f.standardCode());
    appendFilter(sql, args, "appendix_type", f.appendixType());
    appendFilter(sql, args, "phase", f.phase());
    appendFilter(sql, args, "check_class", f.checkClass());
    // 内层仅按距离排序，命中 HNSW 索引
    sql.append(" ORDER BY embedding <-> ?::halfvec LIMIT ?) AS candidates");
    args.add(vectorLiteral);
    args.add(candidate);
    if (weighted) {
      // 外层按元数据命中维度数加权，再按距离兜底排序
      sql.append(" ORDER BY metadata_hits DESC, distance ASC LIMIT ?");
    } else {
      sql.append(" ORDER BY distance ASC LIMIT ?");
    }
    args.add(limit);
    return pg.query(sql.toString(), args.toArray(), (rs, rowNum) -> new Hit(
        rs.getLong("clause_id"),
        rs.getString("standard_code"),
        rs.getString("clause_no"),
        rs.getString("chunk_text"),
        rs.getDouble("distance"),
        rs.getString("metadata"),
        rs.getInt("metadata_hits")));
  }

  /** 统计某元数据维度的取值分布，供 Agent 发现可用过滤值。 */
  public Map<String, Integer> distribution(String dimension) {
    String column = switch (dimension) {
      case "hazard_code" -> "metadata->>'hazard_code'";
      case "phase" -> "metadata->>'phase'";
      case "check_class" -> "metadata->>'check_class'";
      case "appendix_type" -> "metadata->>'appendix_type'";
      case "standard_code" -> "standard_code";
      default -> null;
    };
    if (column == null) {
      return Map.of();
    }
    Map<String, Integer> result = new LinkedHashMap<>();
    pg.query(
        "SELECT " + column + " AS v, COUNT(*) AS c FROM clause_embedding"
            + " WHERE " + column + " IS NOT NULL AND " + column + " <> ''"
            + " GROUP BY v ORDER BY c DESC",
        rs -> {
          result.put(rs.getString("v"), rs.getInt("c"));
        });
    return result;
  }

  /** 已入库向量数。 */
  public long count() {
    Long n = pg.queryForObject("SELECT COUNT(*) FROM clause_embedding", Long.class);
    return n == null ? 0 : n;
  }

  /** 生成元数据命中维度数的 SQL 表达式（用于软加权排序）。 */
  private String hitExpression(Filters f) {
    List<String> parts = new ArrayList<>();
    if (notBlank(f.hazardCode())) {
      parts.add("CASE WHEN metadata->>'hazard_code' = " + quote(f.hazardCode()) + " THEN 1 ELSE 0 END");
    }
    if (notBlank(f.standardCode())) {
      parts.add("CASE WHEN standard_code = " + quote(f.standardCode()) + " THEN 1 ELSE 0 END");
    }
    if (notBlank(f.appendixType())) {
      parts.add("CASE WHEN metadata->>'appendix_type' = " + quote(f.appendixType()) + " THEN 1 ELSE 0 END");
    }
    if (notBlank(f.phase())) {
      parts.add("CASE WHEN metadata->>'phase' = " + quote(f.phase()) + " THEN 1 ELSE 0 END");
    }
    if (notBlank(f.checkClass())) {
      parts.add("CASE WHEN metadata->>'check_class' = " + quote(f.checkClass()) + " THEN 1 ELSE 0 END");
    }
    return parts.isEmpty() ? "0" : String.join(" + ", parts);
  }

  /** 拼接硬过滤条件（参数化，防注入；值来自受控枚举）。 */
  private void appendFilter(StringBuilder sql, List<Object> args, String dimension, String value) {
    if (!notBlank(value)) {
      return;
    }
    if ("standard_code".equals(dimension)) {
      sql.append(" AND standard_code = ?");
    } else {
      sql.append(" AND metadata->>'").append(dimension).append("' = ?");
    }
    args.add(value);
  }

  /** 转义单引号，用于 hitExpression 内联字面量（值来自受控枚举，仍做转义）。 */
  private String quote(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private String toLiteral(float[] vec) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vec.length; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(vec[i]);
    }
    return sb.append("]").toString();
  }
}
