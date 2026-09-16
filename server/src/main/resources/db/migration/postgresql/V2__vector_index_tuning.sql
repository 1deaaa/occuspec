-- V2：向量检索性能索引加固
--
-- 背景（来自 EXPLAIN ANALYZE 实测）：
--   1) 向量检索的排序为 "metadata_hits DESC, embedding <-> ?"，主排序键是计算表达式
--      metadata_hits，规划器因此无法使用 HNSW 索引，退化为主键顺序扫描全表 5933 行。
--      实测：走 Seq Scan 约 2.8ms（数据量增长后线性恶化），强制 HNSW 约 0.85ms。
--      根治办法是改写查询：用 HNSW 先取候选集（ORDER BY 距离 LIMIT n），
--      再在外层做元数据加权重排，使内层能命中索引。本迁移负责补齐外层所需索引。
--   2) 过滤条件 metadata->>'xxx' 无索引，过滤时需回表逐行求值（Rows Removed by Filter 5912）。
--      为常用过滤维度建表达式索引，过滤在索引层完成。
--
-- 说明：HNSW 的 ef_search 等参数在会话级设置（见 ClauseVectorStore），不在此固化。

-- 元数据过滤维度的表达式索引（与 ClauseVectorStore.appendFilter 使用的表达式一致）
CREATE INDEX IF NOT EXISTS idx_clause_emb_meta_hazard
  ON clause_embedding ((metadata->>'hazard_code'));
CREATE INDEX IF NOT EXISTS idx_clause_emb_meta_phase
  ON clause_embedding ((metadata->>'phase'));
CREATE INDEX IF NOT EXISTS idx_clause_emb_meta_check_class
  ON clause_embedding ((metadata->>'check_class'));
CREATE INDEX IF NOT EXISTS idx_clause_emb_meta_appendix
  ON clause_embedding ((metadata->>'appendix_type'));

-- 复合过滤：危害因素 + 阶段是判定流程的高频组合
CREATE INDEX IF NOT EXISTS idx_clause_emb_hazard_phase
  ON clause_embedding ((metadata->>'hazard_code'), (metadata->>'phase'));
