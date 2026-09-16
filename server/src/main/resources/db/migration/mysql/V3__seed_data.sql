-- V3：种子数据（默认用户/危害因素/基础规则/四类结论说明）
-- 默认账号：admin / 1009（BCrypt 哈希对应明文 1009，启动后请修改）。

INSERT INTO sys_users (id, username, password_hash, nickname, role, is_enabled)
VALUES (1, 'admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '系统管理员', 'ADMIN', 1)
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);

INSERT INTO hazards (code, name, category, exposure_limit) VALUES
  ('lead', '铅及其无机化合物', '化学', '血铅、尿铅限值见 GBZ 188 第 5.1 节'),
  ('benzene', '苯', '化学', '慢性苯中毒判定见 GBZ 68'),
  ('noise', '噪声', '物理', '等效声级 85dB 阈值见 GBZ 188 第 7.1 节'),
  ('dust_silica', '游离二氧化硅粉尘', '粉尘', '尘肺判定见 GBZ 70'),
  ('toluene', '甲苯（二甲苯参照执行）', '化学', '见 GBZ 188 第 5.58 节')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 基础规则：噪声听阈异常提示复核职业禁忌证（结构化表达式，规则引擎解析执行）
INSERT INTO rules (id, code, name, hazard_code, expression, conclusion, weight, is_enabled, version)
VALUES
  (1, 'NOISE_HEARING_TABOO', '噪声作业听阈异常提示', 'noise',
   '{"all": [{"fact": "hearing_avg_db", "op": ">=", "value": 40}]}',
   'OCCUPATIONAL_TABOO', 200, 1, 'v1'),
  (2, 'LEAD_BLOOD_HIGH', '血铅超标提示复查', 'lead',
   '{"all": [{"fact": "blood_lead_umol", "op": ">=", "value": 2.9}]}',
   'OTHER_ABNORMALITY', 150, 1, 'v1'),
  (3, 'DEFAULT_NORMAL', '无命中时默认未见异常', '',
   '{"all": []}',
   'NO_ABNORMALITY', 1, 1, 'v1')
ON DUPLICATE KEY UPDATE name = VALUES(name), expression = VALUES(expression);
