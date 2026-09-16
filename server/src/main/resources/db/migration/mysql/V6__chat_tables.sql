-- V6：对话会话与消息落库，供审计回放与多轮上下文
CREATE TABLE IF NOT EXISTS chat_sessions (
  id BIGINT NOT NULL,
  title VARCHAR(256) NOT NULL DEFAULT '' COMMENT '会话标题（首条提问摘要）',
  user_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '发起用户',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话表';

CREATE TABLE IF NOT EXISTS chat_messages (
  id BIGINT NOT NULL,
  session_id BIGINT NOT NULL COMMENT '会话',
  role VARCHAR(16) NOT NULL COMMENT 'user/assistant/tool',
  content MEDIUMTEXT NOT NULL COMMENT '正文',
  reasoning MEDIUMTEXT NULL COMMENT '推理过程（可折叠展示）',
  tool_calls JSON NULL COMMENT '工具调用记录',
  citations JSON NULL COMMENT '引用条款',
  prompt_tokens BIGINT NOT NULL DEFAULT 0,
  completion_tokens BIGINT NOT NULL DEFAULT 0,
  total_tokens BIGINT NOT NULL DEFAULT 0,
  cost_ms BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_session_time (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表：记录推理、工具调用与用量';
