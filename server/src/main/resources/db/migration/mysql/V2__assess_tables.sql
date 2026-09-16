-- V2：判定域（评估/证据链/推荐/规则/审计/批量任务/用户）

CREATE TABLE IF NOT EXISTS assessments (
  id BIGINT NOT NULL,
  exam_id BIGINT NOT NULL COMMENT '体检记录',
  input_snapshot JSON NOT NULL COMMENT '输入快照，可复现',
  conclusion VARCHAR(32) NOT NULL COMMENT '四类结论枚举',
  conclusion_source VARCHAR(16) NOT NULL DEFAULT 'RULE' COMMENT 'RULE=规则直出，LLM=模型参与',
  rule_version VARCHAR(32) NOT NULL DEFAULT '' COMMENT '规则版本',
  reviewer_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '复核人',
  review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '待复核/已复核',
  review_comment VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '复核意见',
  prompt_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '上游实际用量',
  completion_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '上游实际用量',
  total_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '上游实际用量',
  cost_ms BIGINT NOT NULL DEFAULT 0 COMMENT '判定耗时毫秒',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam (exam_id),
  KEY idx_conclusion (conclusion)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='判定评估表：结论为建议性质，须复核生效';

CREATE TABLE IF NOT EXISTS evidences (
  id BIGINT NOT NULL,
  assessment_id BIGINT NOT NULL COMMENT '评估',
  clause_id BIGINT NOT NULL COMMENT '条款主键',
  standard_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '标准号冗余',
  clause_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '条款编号冗余',
  quote VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '原文片段',
  item_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '关联检查项',
  reason VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '命中说明',
  PRIMARY KEY (id),
  KEY idx_assessment (assessment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证据链表：结论到条款与数值的引用';

CREATE TABLE IF NOT EXISTS recommendations (
  id BIGINT NOT NULL,
  assessment_id BIGINT NOT NULL,
  item_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '建议检查项',
  item_name VARCHAR(128) NOT NULL DEFAULT '',
  reason VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '建议依据',
  is_extended TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=机构自加，必须单独展示',
  source_clause_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '标准内推荐来源条款',
  PRIMARY KEY (id),
  KEY idx_assess_ext (assessment_id, is_extended)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='额外检查推荐表';

CREATE TABLE IF NOT EXISTS rules (
  id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL COMMENT '规则编码唯一',
  name VARCHAR(128) NOT NULL COMMENT '规则名称',
  hazard_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '适用危害因素，空=通用',
  expression JSON NOT NULL COMMENT '结构化判定表达式',
  conclusion VARCHAR(32) NOT NULL COMMENT '命中后建议结论',
  weight INT NOT NULL DEFAULT 100 COMMENT '权重，冲突时高者优先',
  is_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '启停',
  version VARCHAR(32) NOT NULL DEFAULT 'v1' COMMENT '规则版本',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code),
  KEY idx_enabled_hazard (is_enabled, hazard_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='判定规则表：规则优先于模型输出';

CREATE TABLE IF NOT EXISTS audits (
  id BIGINT NOT NULL,
  biz_type VARCHAR(32) NOT NULL COMMENT '业务类型',
  biz_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '业务主键',
  actor_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作人',
  action VARCHAR(128) NOT NULL DEFAULT '' COMMENT '动作',
  diff JSON NULL COMMENT '变更明细',
  trace_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '链路ID',
  cost_ms BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_biz (biz_type, biz_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表：只追加不修改';

CREATE TABLE IF NOT EXISTS batch_tasks (
  id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL COMMENT 'CSV_IMPORT/XLSX_EXPORT',
  file_url VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '源文件路径',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '待执行/执行中/成功/失败',
  total INT NOT NULL DEFAULT 0,
  success INT NOT NULL DEFAULT 0,
  fail INT NOT NULL DEFAULT 0,
  error_url VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '失败明细路径',
  creator_id VARCHAR(64) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量任务表：状态机+定时轮询，不引入队列';

CREATE TABLE IF NOT EXISTS sys_users (
  id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL COMMENT '登录名唯一',
  password_hash VARCHAR(256) NOT NULL COMMENT '密码哈希',
  nickname VARCHAR(64) NOT NULL DEFAULT '' COMMENT '显示名',
  role VARCHAR(32) NOT NULL DEFAULT 'DOCTOR' COMMENT '角色',
  is_enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';
