-- V5：条款新增检查阶段字段；危害因素表补充来源与别名（全量 97 项由解析器生成）
ALTER TABLE clauses
  ADD COLUMN phase VARCHAR(16) NOT NULL DEFAULT '' COMMENT '检查阶段：上岗前/在岗期间/离岗时/应急' AFTER appendix_type;

ALTER TABLE hazards
  ADD COLUMN section_no VARCHAR(16) NOT NULL DEFAULT '' COMMENT '来源章节号，如 7.1' AFTER code,
  ADD COLUMN source_standard VARCHAR(64) NOT NULL DEFAULT '' COMMENT '来源标准号' AFTER section_no,
  ADD COLUMN aliases JSON NULL COMMENT '别名，用于自然语言匹配' AFTER exposure_limit;

CREATE INDEX idx_phase ON clauses (phase);
