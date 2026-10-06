-- ============================================================================
-- 迁移 06 回滚脚本（正向：data/migration-06-records-org-visit-time.sql）
--
-- 只删一个索引，不动任何数据与列；回滚后「03 → 06」的终态与 database-init.sql 不一致，
-- 这是预期的 —— 回滚就是把该迁移的增量撤掉。
--
-- 幂等：information_schema 守卫；索引已不存在时无害。
-- 用法：mysql --default-character-set=utf8mb4 <db> < data/migration-06-records-org-visit-time-rollback.sql
-- ============================================================================

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_org_visit_time') = 1,
  'ALTER TABLE records DROP INDEX idx_records_org_visit_time', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 自检：应 0 行
--   SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
--      AND INDEX_NAME = 'idx_records_org_visit_time';
