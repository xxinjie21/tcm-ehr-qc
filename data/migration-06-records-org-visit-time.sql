-- ============================================================================
-- 迁移 06 正向脚本：records 补 (org_id, visit_time) 复合索引（批次 25 · 25.4）
-- 反向：data/migration-06-records-org-visit-time-rollback.sql（无数据影响）
--
-- 背景：病历列表/导出/统计查询普遍是「org_id 等值（数据域）+ visit_time 范围或排序」
--       （见 RecordFilter 的 operatorScope 与列表排序）。已有 idx_records_org(org_id)
--       与 idx_records_visit_time(visit_time) 皆为单列，MySQL 只能选其一，另一维回表过滤。
--       复合索引让两维同时下推到索引。
--
-- 取舍：不删除 idx_records_org —— 复合索引的前缀虽已覆盖它，但 migration-03 的回滚脚本
--       显式引用该索引名，删掉会让「03 → 06」往返验证对不上；单列索引的写放大代价可忽略。
--
-- 幂等：information_schema 守卫，可重复执行。
-- 执行：mysql --default-character-set=utf8mb4 <db> < data/migration-06-records-org-visit-time.sql
-- ============================================================================

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_org_visit_time') = 0,
  'ALTER TABLE records ADD INDEX idx_records_org_visit_time (org_id, visit_time)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 自检：应 1 行
--   SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
--      AND INDEX_NAME = 'idx_records_org_visit_time';
