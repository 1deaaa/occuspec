-- V8：危害因素编码归一化 + 清理冗余种子危害
-- 背景：危害因素目录由 GBZ 188 第 5-9 章解析生成，编码为 gbz188-<章>-<节>（如 gbz188-7-1）；
-- 而 V3 种子数据与早期规则/体检记录使用了简写短码（noise/lead/benzene/dust_silica/toluene）。
-- 两套编码并存会让"按危害因素过滤条款"与"规则按危害因素匹配"同时失效，判定退化为全库检索。
--
-- 说明：迁移在标准导入之前执行，此时 gbz188-* 危害尚未生成，无法按名称反查，
-- 因此这里按 GBZ 188 章节结构（稳定事实）直接映射；导入后由 hazards 表按规范编码存在。
-- 前端新增/编辑数据统一从 hazards 表下拉取规范编码，不再产生短码。

-- 1) 规则：短码 → 规范编码
UPDATE rules SET hazard_code = 'gbz188-7-1'  WHERE hazard_code = 'noise';
UPDATE rules SET hazard_code = 'gbz188-5-1'  WHERE hazard_code = 'lead';
UPDATE rules SET hazard_code = 'gbz188-5-19' WHERE hazard_code = 'benzene';
UPDATE rules SET hazard_code = 'gbz188-6-1'  WHERE hazard_code = 'dust_silica';
UPDATE rules SET hazard_code = 'gbz188-5-58' WHERE hazard_code = 'toluene';

-- 2) 体检记录：短码 → 规范编码
UPDATE exams SET hazard_code = 'gbz188-7-1'  WHERE hazard_code = 'noise';
UPDATE exams SET hazard_code = 'gbz188-5-1'  WHERE hazard_code = 'lead';
UPDATE exams SET hazard_code = 'gbz188-5-19' WHERE hazard_code = 'benzene';
UPDATE exams SET hazard_code = 'gbz188-6-1'  WHERE hazard_code = 'dust_silica';
UPDATE exams SET hazard_code = 'gbz188-5-58' WHERE hazard_code = 'toluene';

-- 3) 清理 V3 种子的短码危害：其语义已由导入生成的 gbz188-* 目录覆盖，
--    保留会造成同一危害两套编码。exposure_limit 描述随目录重建。
DELETE FROM hazards WHERE code IN ('lead', 'benzene', 'noise', 'dust_silica', 'toluene');
