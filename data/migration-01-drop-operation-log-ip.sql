-- ============================================================================
-- 迁移 01：§七 L1「不记操作 IP」
--
-- 为什么需要单独脚本：database-init.sql 不被 Spring 自动执行（application.yml 无
-- spring.sql.init），且只有 CREATE TABLE IF NOT EXISTS —— 对**存量库**的改列 / 删列 /
-- 索引都不会生效。新表写进 database-init.sql 即可，本脚本只管「已有表」的结构变更。
--
-- 幂等：重复执行不报错（先判断列是否存在）。
-- ============================================================================

-- 1. 删 ip 列。代码侧已不再写该列，实体 OperationLog 也已无此字段。
--    若想保守，可不执行本条（留列恒 NULL、无害），仅为干净而删。
SET @has_ip := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND COLUMN_NAME = 'ip'
);
SET @sql := IF(@has_ip > 0,
  'ALTER TABLE operation_log DROP COLUMN ip',
  'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
