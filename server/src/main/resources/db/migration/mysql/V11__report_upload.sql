-- V11：报告上传与抽取
-- 外部体检报告以图片形式拍照上传，经多模态模型抽取为结构化检查项，
-- 人工核对后才写入体检记录。中间态需留痕：便于回溯抽取质量、定位错漏，
-- 且"抽取→核对→入库"三段分离，避免模型输出未经人工确认直接进入判定。
CREATE TABLE IF NOT EXISTS report_uploads (
  id BIGINT NOT NULL,
  file_name VARCHAR(256) NOT NULL DEFAULT '' COMMENT '原始文件名',
  mime_type VARCHAR(64) NOT NULL DEFAULT '' COMMENT '图片类型',
  file_size BIGINT NOT NULL DEFAULT 0 COMMENT '字节数',
  person_id BIGINT NULL COMMENT '关联体检对象（可空）',
  hazard_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '危害因素',
  status VARCHAR(24) NOT NULL DEFAULT 'EXTRACTED' COMMENT 'EXTRACTED/CONFIRMED/IMPORTED/FAILED',
  raw_text MEDIUMTEXT NULL COMMENT '模型抽取的原始 JSON',
  extract_note VARCHAR(512) NOT NULL DEFAULT '' COMMENT '抽取说明或失败原因',
  exam_id BIGINT NULL COMMENT '确认入库后生成的体检记录',
  creator_id VARCHAR(64) NOT NULL DEFAULT '',
  cost_ms BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_person_time (person_id, created_at),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告图片上传与抽取记录';

CREATE TABLE IF NOT EXISTS report_upload_items (
  id BIGINT NOT NULL,
  upload_id BIGINT NOT NULL COMMENT '所属上传记录',
  item_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '抽取到的检查项名称（原文）',
  item_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '归一化后的规范 fact 编码',
  value_num DECIMAL(18,4) NULL COMMENT '数值结果',
  value_text VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '文本结果',
  unit VARCHAR(32) NOT NULL DEFAULT '',
  confidence DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '模型自评置信度 0-1',
  confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '人工核对是否采纳',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_upload (upload_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告抽取检查项（人工核对单位）';
