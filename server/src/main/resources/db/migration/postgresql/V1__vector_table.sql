-- V1：向量库（条款向量表，halfvec(1024)+HNSW，pgvector 0.8.1 实测可用）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS clause_embedding (
  id BIGSERIAL PRIMARY KEY,
  clause_id BIGINT NOT NULL,
  standard_code VARCHAR(64) NOT NULL DEFAULT '',
  clause_no VARCHAR(64) NOT NULL DEFAULT '',
  chunk_text TEXT NOT NULL,
  embedding halfvec(1024) NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (clause_id)
);
CREATE INDEX IF NOT EXISTS idx_clause_emb_std ON clause_embedding (standard_code, clause_no);
CREATE INDEX IF NOT EXISTS idx_clause_emb_hnsw ON clause_embedding USING hnsw (embedding halfvec_l2_ops);
