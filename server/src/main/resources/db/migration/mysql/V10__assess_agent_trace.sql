-- V10：判定记录 Agent 决策轨迹
-- 判定已由固定四步流程改为 Agent 自主编排（模型自行决定检索工具、过滤维度与轮次）。
-- 为支持审计回放与效果评估，记录决策轨迹：轮次、决策来源、工具调用序列与推理摘要。
ALTER TABLE assessments
  ADD COLUMN agent_rounds INT NOT NULL DEFAULT 0 COMMENT 'Agent 工具循环轮次' AFTER rule_version,
  ADD COLUMN agent_traces JSON NULL COMMENT 'Agent 工具调用轨迹（工具/参数/命中数/说明）' AFTER agent_rounds,
  ADD COLUMN agent_reasoning MEDIUMTEXT NULL COMMENT 'Agent 推理过程摘要' AFTER agent_traces,
  ADD COLUMN rationale MEDIUMTEXT NULL COMMENT 'Agent 提交的判定依据' AFTER agent_reasoning;

-- conclusion_source 取值语义扩展：AGENT / RULE_FLOOR / RULE_FALLBACK（原 RULE/LLM 保留兼容旧数据）
-- 历史数据：LLM 视为模型给出，RULE 视为规则回退，与旧逻辑一致，无需改写。
