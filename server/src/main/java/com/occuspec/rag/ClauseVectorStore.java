package com.occuspec.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 条款向量存取：PG halfvec(1024) + HNSW，SQL 直写（向量类型需手工拼接字面量）。
 * 元数据过滤（危害因素/标准号/附录类型）在 SQL 层完成。
 */
@Component
public class ClauseVectorStore {
  private final JdbcTemplate pg;
  private final int dimensions;

  public ClauseVectorStore(
      @Qualifier("pgJdbcTemplate") JdbcTemplate pg,
      @Value("${occuspec.embedding.dimensions:1024}") int dimensions) {
    this.pg = pg;
    this.dimensions = dimensions;
  }

  /** 命中条款。 */
  public record Hit(
      long clauseId, String standardCode, String clauseNo, String chunkText, double distance, String metadata) {}

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

  /** 向量检索：余弦距离排序，支持元数据过滤。 */
  public List<Hit> search(float[] query, int topK, String hazardCode, String standardCode, String appendixType) {
    StringBuilder sql = new StringBuilder(
        "SELECT clause_id, standard_code, clause_no, chunk_text,"
            + " embedding <-> ?::halfvec AS distance, metadata::text AS metadata"
            + " FROM clause_embedding WHERE 1=1");
    List<Object> args = new ArrayList<>();
    args.add(toLiteral(query));
    if (hazardCode != null && !hazardCode.isBlank()) {
      sql.append(" AND metadata->>'hazard_code' = ?");
      args.add(hazardCode);
    }
    if (standardCode != null && !standardCode.isBlank()) {
      sql.append(" AND standard_code = ?");
      args.add(standardCode);
    }
    if (appendixType != null && !appendixType.isBlank()) {
      sql.append(" AND metadata->>'appendix_type' = ?");
      args.add(appendixType);
    }
    sql.append(" ORDER BY embedding <-> ?::halfvec LIMIT ?");
    args.add(toLiteral(query));
    args.add(topK);
    return pg.query(sql.toString(), args.toArray(), (rs, rowNum) -> new Hit(
        rs.getLong("clause_id"),
        rs.getString("standard_code"),
        rs.getString("clause_no"),
        rs.getString("chunk_text"),
        rs.getDouble("distance"),
        rs.getString("metadata")));
  }

  /** 已入库向量数。 */
  public long count() {
    Long n = pg.queryForObject("SELECT COUNT(*) FROM clause_embedding", Long.class);
    return n == null ? 0 : n;
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
