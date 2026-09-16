-- V7：检查项字段映射层
-- 外部报告字段名各异（中文名、英文简写、机构自定义列名），与规则表达式使用的
-- 规范 fact 编码之间存在映射缺口。本表维护"别名 → 规范编码"的映射，
-- 录入与导入时统一归一化，避免规则因字段名不一致而漏命中。
CREATE TABLE IF NOT EXISTS item_field_mapping (
  id BIGINT NOT NULL,
  alias VARCHAR(128) NOT NULL COMMENT '外部字段别名（中文名或英文简写），统一小写去空格后匹配',
  canonical_code VARCHAR(64) NOT NULL COMMENT '规范 fact 编码，对应规则表达式的 fact 与 exam_items.item_code',
  canonical_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '规范中文名',
  unit VARCHAR(32) NOT NULL DEFAULT '' COMMENT '期望单位',
  source VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：SEED/MANUAL/REPORT',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_alias (alias),
  KEY idx_canonical (canonical_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检查项字段别名映射表';

-- 种子映射：覆盖已有规则所用 fact 与常见报告字段写法
INSERT INTO item_field_mapping (id, alias, canonical_code, canonical_name, unit, source) VALUES
  (1, 'hearing_avg_db', 'hearing_avg_db', '双耳高频平均听阈', 'dB', 'SEED'),
  (2, '双耳高频平均听阈', 'hearing_avg_db', '双耳高频平均听阈', 'dB', 'SEED'),
  (3, '高频平均听阈', 'hearing_avg_db', '双耳高频平均听阈', 'dB', 'SEED'),
  (4, '双耳听力', 'hearing_avg_db', '双耳高频平均听阈', 'dB', 'SEED'),
  (5, 'hearing', 'hearing_avg_db', '双耳高频平均听阈', 'dB', 'SEED'),
  (6, 'blood_lead_umol', 'blood_lead_umol', '血铅', 'μmol/L', 'SEED'),
  (7, '血铅', 'blood_lead_umol', '血铅', 'μmol/L', 'SEED'),
  (8, '血铅值', 'blood_lead_umol', '血铅', 'μmol/L', 'SEED'),
  (9, 'blood_lead', 'blood_lead_umol', '血铅', 'μmol/L', 'SEED'),
  (10, '铅血', 'blood_lead_umol', '血铅', 'μmol/L', 'SEED')
ON DUPLICATE KEY UPDATE canonical_code = VALUES(canonical_code), canonical_name = VALUES(canonical_name);
