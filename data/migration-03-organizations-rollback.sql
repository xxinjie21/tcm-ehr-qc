-- ============================================================================
-- 迁移 03 回滚脚本（正向：data/migration-03-organizations.sql）
--
-- ⚠️ **会丢数据**：五张配置新表内的数据、text_hash 值、取消标记都会消失；
--    组织表 / 归属列 / 索引恢复成 group_* 形态。
-- 执行前确认已备份。
-- ============================================================================

-- ============================================================================
-- 反向脚本（回滚用）
--
-- 用法：单独执行本文件（`mysql <db> < data/migration-03-organizations-rollback.sql`）。
-- ⚠️ 回滚会**丢数据**：五张新表内的数据、text_hash 值、取消标记都会消失。
--    组织表 / 归属列 / 索引会恢复成 group_* 形态。
-- ============================================================================

-- R.9 取消标记列
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task'
      AND COLUMN_NAME = 'cancel_requested') = 1,
  'ALTER TABLE nlp_task DROP COLUMN cancel_requested', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task'
      AND COLUMN_NAME = 'cancel_requested') = 1,
  'ALTER TABLE qc_task DROP COLUMN cancel_requested', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- R.8 复核「至多一条活跃任务」约束
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND INDEX_NAME = 'uk_record_active') = 1,
  'ALTER TABLE review_tasks DROP INDEX uk_record_active', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND COLUMN_NAME = 'active_key') = 1,
  'ALTER TABLE review_tasks DROP COLUMN active_key', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- R.7 去重兜底
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'uk_records_org_text_hash') = 1,
  'ALTER TABLE records DROP INDEX uk_records_org_text_hash', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND COLUMN_NAME = 'text_hash') = 1,
  'ALTER TABLE records DROP COLUMN text_hash', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- R.6 五张新表
DROP TABLE IF EXISTS dictionary_terms;
DROP TABLE IF EXISTS dictionary_backups;
DROP TABLE IF EXISTS dictionary_versions;
DROP TABLE IF EXISTS qc_rules;
DROP TABLE IF EXISTS user_llm_config;

-- R.5 users.status 还原为 pending 语义
ALTER TABLE users
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'pending'
    COMMENT 'pending=待分配池或审批中 / active=有生效组 / disabled=停用',
  MODIFY COLUMN has_pending_group TINYINT NOT NULL DEFAULT 0
    COMMENT '1=已申请建组待审批，从待分配池隐藏';

-- R.4 日志索引还原
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_operator') = 0,
  'ALTER TABLE operation_log ADD INDEX idx_operator (operator)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_group_time') = 1,
  'ALTER TABLE operation_log DROP INDEX idx_group_time', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_operator_time') = 1,
  'ALTER TABLE operation_log DROP INDEX idx_operator_time', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- R.3 归属列还原
ALTER TABLE organization_members
                         CHANGE COLUMN org_id group_id VARCHAR(36) NOT NULL;
ALTER TABLE records      CHANGE COLUMN org_id group_id VARCHAR(36) DEFAULT '',
                         DROP INDEX idx_records_org,      ADD INDEX idx_records_group (group_id);
ALTER TABLE nlp_task     CHANGE COLUMN org_id group_id VARCHAR(36),
                         DROP INDEX idx_nlp_task_org,    ADD INDEX idx_nlp_task_group (group_id);
ALTER TABLE qc_task      CHANGE COLUMN org_id group_id VARCHAR(36),
                         DROP INDEX idx_qc_task_org,     ADD INDEX idx_qc_task_group (group_id);
ALTER TABLE review_tasks CHANGE COLUMN org_id group_id VARCHAR(36),
                         DROP INDEX idx_review_task_org,  ADD INDEX idx_review_task_group (group_id);
ALTER TABLE operation_log CHANGE COLUMN org_id group_id VARCHAR(36) COMMENT '操作时所属组，留痕用';

-- R.2 组织表还原：加回审核四列、去掉授权开关与 owner_user_id、status 默认值还原
ALTER TABLE organizations
  ADD COLUMN applied_by VARCHAR(36) COMMENT '申请人（= 首任所有者）',
  ADD COLUMN reviewed_by VARCHAR(36),
  ADD COLUMN reviewed_at DATETIME,
  ADD COLUMN reject_reason VARCHAR(300) COMMENT '拒绝理由，申请人可见',
  DROP COLUMN owner_user_id,
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'pending'
    COMMENT 'pending/active/rejected/stopped';

ALTER TABLE organization_members
  DROP COLUMN can_write_dictionary,
  DROP COLUMN can_write_qc_rules,
  MODIFY COLUMN role VARCHAR(20) NOT NULL COMMENT 'owner=组长 / member=组员';

-- R.1 表名还原
RENAME TABLE organizations TO research_groups, organization_members TO group_members;

-- ============================================================================
-- 回滚后自检（两条都应为 0 行 / 0 命中）
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members';  -- 应 0
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records' AND COLUMN_NAME = 'org_id';  -- 应 0
-- ============================================================================
