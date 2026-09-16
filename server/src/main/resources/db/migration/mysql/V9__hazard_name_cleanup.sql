-- V9：危害因素名称清理
-- 源 GBZ 188 Markdown 把"β-萘胺"写成带斜体标记的"β _-_ 萘胺"，早期解析未清除该标记，
-- 目录中留下脏名称，既影响下拉展示，也影响按名称的别名匹配。
-- 按唯一可识别的"萘胺"定位，避免依赖正则中反斜杠转义。
UPDATE hazards SET name = 'β-萘胺' WHERE name LIKE '%萘胺%';
UPDATE hazards SET aliases = JSON_ARRAY('β-萘胺') WHERE name = 'β-萘胺';
