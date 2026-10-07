-- 病历列表 40,000 条 · 性能索引（性能审查报告-病历列表4万条，2026-10-07）
--
-- 背景（详见 docs/性能审查报告-病历列表4万条.md）：
--   列表排序键是 ORDER BY visit_time DESC, id ASC，而 records 表没有任何能匹配
--   该排序键的索引 → 每次列表查询都读全机构 4 万行 + filesort（EXPLAIN ANALYZE 实测）。
--   加上缺 (org_id, department) 与 (org_id, grade, governed) 后，COUNT/聚合/科室下拉
--   全部全表扫描。纯 DDL 修复（索引是新加对象，不碰任何业务逻辑与数据），
--   4 万行实测：列表首页 176.7ms → 1.8ms（98×）、overview 聚合 578ms → 11.9ms（48×）。
--
-- 幂等：每个索引创建前先查 information_schema，已存在则跳过，可重复执行。
-- 注意：MySQL 9.x 已移除 ALTER 的 LOCK 子句，也无需写 ALGORITHM（默认即 INPLACE），
--       这里用最简语法，兼容 8.0 / 9.x 全系。
-- 回滚：见文件底部注释（DROP INDEX 即可）。

SET @schema = DATABASE();

-- ① P0-1：与列表排序键 (visit_time DESC, id ASC) 逐字对齐的降序复合索引 —— 消灭 filesort
SET @sql = NULL;
SELECT IF(COUNT(*) = 0,
  'ALTER TABLE records ADD INDEX idx_records_org_vt_id (org_id, visit_time DESC, id ASC)',
  NULL)
  INTO @sql
  FROM information_schema.statistics
 WHERE table_schema = @schema AND table_name = 'records' AND index_name = 'idx_records_org_vt_id';
SET @sql = IFNULL(@sql, 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ② P1-3：机构 + 科室 —— COUNT+department 与 DISTINCT department 从全表扫描变覆盖索引
SET @sql = NULL;
SELECT IF(COUNT(*) = 0,
  'ALTER TABLE records ADD INDEX idx_records_org_department (org_id, department)',
  NULL)
  INTO @sql
  FROM information_schema.statistics
 WHERE table_schema = @schema AND table_name = 'records' AND index_name = 'idx_records_org_department';
SET @sql = IFNULL(@sql, 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ③ P1-6：机构 + 分级 + 治理标记覆盖索引 —— /api/stats/overview 与 /api/governance/stats 纯索引扫描、不回表
SET @sql = NULL;
SELECT IF(COUNT(*) = 0,
  'ALTER TABLE records ADD INDEX idx_records_org_grade_gov (org_id, grade, governed)',
  NULL)
  INTO @sql
  FROM information_schema.statistics
 WHERE table_schema = @schema AND table_name = 'records' AND index_name = 'idx_records_org_grade_gov';
SET @sql = IFNULL(@sql, 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ④ migration-06 声明但线上缺失的 (org_id, visit_time)：不着排序，但对 COUNT(*) WHERE org_id=? 等仍有用，一并补齐
SET @sql = NULL;
SELECT IF(COUNT(*) = 0,
  'ALTER TABLE records ADD INDEX idx_records_org_visit_time (org_id, visit_time)',
  NULL)
  INTO @sql
  FROM information_schema.statistics
 WHERE table_schema = @schema AND table_name = 'records' AND index_name = 'idx_records_org_visit_time';
SET @sql = IFNULL(@sql, 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 回滚（如需）：
--   ALTER TABLE records DROP INDEX idx_records_org_vt_id;
--   ALTER TABLE records DROP INDEX idx_records_org_department;
--   ALTER TABLE records DROP INDEX idx_records_org_grade_gov;
--   ALTER TABLE records DROP INDEX idx_records_org_visit_time;