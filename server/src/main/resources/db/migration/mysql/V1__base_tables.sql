-- V1：业务基表（标准/条款/危害/人员/体检/判定/证据/推荐/规则/审计/批量任务）
-- 字符集统一 utf8mb4，时间统一 DATETIME(3)。

CREATE TABLE IF NOT EXISTS standards (
  code VARCHAR(64) NOT NULL COMMENT '标准号，如 GBZ 188-2025',
  name VARCHAR(256) NOT NULL COMMENT '标准名称',
  version VARCHAR(32) NOT NULL DEFAULT '' COMMENT '版本',
  publish_date DATE NULL COMMENT '发布日期',
  effective_date DATE NULL COMMENT '实施日期',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准主表';

CREATE TABLE IF NOT EXISTS clauses (
  id BIGINT NOT NULL COMMENT '雪花ID',
  standard_code VARCHAR(64) NOT NULL COMMENT '标准号',
  clause_no VARCHAR(64) NOT NULL COMMENT '条款编号，如 5.1.2 / B.1.4',
  title VARCHAR(512) NOT NULL DEFAULT '' COMMENT '条款标题',
  content MEDIUMTEXT NOT NULL COMMENT '条款正文原文',
  page_no INT NULL COMMENT '页码溯源',
  appendix_type VARCHAR(16) NOT NULL DEFAULT '' COMMENT '规范性/资料性/空=正文',
  hazard_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '危害因素编码',
  check_class VARCHAR(16) NOT NULL DEFAULT '' COMMENT '必检/补充/选检',
  target_text VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '目标疾病或禁忌证摘录',
  period_text VARCHAR(256) NOT NULL DEFAULT '' COMMENT '检查周期摘录',
  force_type VARCHAR(16) NOT NULL DEFAULT '' COMMENT '强制性/推荐性',
  relations JSON NULL COMMENT '引用回链',
  content_hash CHAR(64) NOT NULL COMMENT '正文哈希，去重用',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_std_clause (standard_code, clause_no),
  KEY idx_hazard (hazard_code),
  KEY idx_content_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='条款表：最小可引用单元';

CREATE TABLE IF NOT EXISTS hazards (
  code VARCHAR(64) NOT NULL COMMENT '危害因素编码，如 benzene/noise',
  name VARCHAR(128) NOT NULL COMMENT '危害因素名称',
  category VARCHAR(32) NOT NULL DEFAULT '' COMMENT '化学/粉尘/物理/生物/特殊作业',
  exposure_limit VARCHAR(256) NOT NULL DEFAULT '' COMMENT '接触限值摘录',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='危害因素表';

CREATE TABLE IF NOT EXISTS persons (
  id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL COMMENT '姓名',
  id_card_hash CHAR(64) NOT NULL DEFAULT '' COMMENT '证件号哈希，不存明文',
  gender VARCHAR(8) NOT NULL DEFAULT '' COMMENT '性别',
  birth_date DATE NULL,
  company VARCHAR(256) NOT NULL DEFAULT '' COMMENT '用人单位',
  job_type VARCHAR(128) NOT NULL DEFAULT '' COMMENT '工种',
  exposure_history JSON NULL COMMENT '接害史',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_idcard (id_card_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体检对象表';

CREATE TABLE IF NOT EXISTS exams (
  id BIGINT NOT NULL,
  person_id BIGINT NOT NULL COMMENT '体检对象',
  hazard_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '本次接害因素',
  exam_date DATE NOT NULL COMMENT '体检日期',
  operator_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '录入人',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '草稿/已提交',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_person_date (person_id, exam_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体检记录表';

CREATE TABLE IF NOT EXISTS exam_items (
  id BIGINT NOT NULL,
  exam_id BIGINT NOT NULL COMMENT '体检记录',
  item_code VARCHAR(64) NOT NULL COMMENT '检查项编码',
  item_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '检查项名称',
  value_num DECIMAL(18,4) NULL COMMENT '数值结果',
  value_text VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '文本结果',
  unit VARCHAR(32) NOT NULL DEFAULT '' COMMENT '单位',
  PRIMARY KEY (id),
  KEY idx_exam_item (exam_id, item_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体检明细表';
