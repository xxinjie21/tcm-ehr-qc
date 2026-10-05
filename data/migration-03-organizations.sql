-- ============================================================================
-- 迁移 03 正向脚本：组织化重构
-- 反向：data/migration-03-organizations-rollback.sql（⚠️ 回滚会丢数据）
--
-- 用途：把「组织化重构之前」的存量库升到当前结构。
--       全新装库不要用本脚本，直接用 database-init.sql（权威建表脚本）。
--
-- 幂等：每条 DDL 都用 information_schema 守卫或 MODIFY（天然幂等），
--       可在已迁移库上重复执行 —— 这正是当初的验收口径「演练环境跑通且可重复执行」。
--
-- 执行：mysql --default-character-set=utf8mb4 <db> < data/migration-03-organizations.sql
--       执行前先 mysqldump 备份；改存量库务必带 --default-character-set=utf8mb4。
--
-- 关于「五张配置表」：本脚本建 4 张（dictionary_terms / dictionary_versions / qc_rules /
--   user_llm_config）。原计划的第五张 dictionary_backups 已被迁移 04 显式 DROP，
--   这里不再重建 —— 于是「03 → 04」重放的终态与 database-init.sql 一致；
--   回滚脚本用 DROP TABLE IF EXISTS 删它，缺表也无害。
--
-- 表结构以 database-init.sql 为准；本脚本只做增量，不重复定义整表（新建的 4 张除外）。
-- ============================================================================

-- ============================================================================
-- F.1 表名：research_groups / group_members → organizations / organization_members
-- ============================================================================
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'research_groups') = 1,
  'RENAME TABLE research_groups TO organizations', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'group_members') = 1,
  'RENAME TABLE group_members TO organization_members', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- F.2 组织表：去掉审核四列、加 owner_user_id、status 改 active 语义；
--     成员表：加两个写授权开关
-- ============================================================================
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'applied_by') = 1,
  'ALTER TABLE organizations DROP COLUMN applied_by', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reviewed_by') = 1,
  'ALTER TABLE organizations DROP COLUMN reviewed_by', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reviewed_at') = 1,
  'ALTER TABLE organizations DROP COLUMN reviewed_at', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reject_reason') = 1,
  'ALTER TABLE organizations DROP COLUMN reject_reason', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'owner_user_id') = 0,
  'ALTER TABLE organizations ADD COLUMN owner_user_id VARCHAR(36)
     COMMENT ''所有者冗余展示，权威以 organization_members.role=owner''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- status：MODIFY 幂等，直接写目标形态（不再有审核，故不存在 pending）
ALTER TABLE organizations
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active/stopped/archived';

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members'
      AND COLUMN_NAME = 'can_write_dictionary') = 0,
  'ALTER TABLE organization_members ADD COLUMN can_write_dictionary TINYINT NOT NULL DEFAULT 0
     COMMENT ''词典写授权开关''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members'
      AND COLUMN_NAME = 'can_write_qc_rules') = 0,
  'ALTER TABLE organization_members ADD COLUMN can_write_qc_rules TINYINT NOT NULL DEFAULT 0
     COMMENT ''质控规则写授权开关''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE organization_members
  MODIFY COLUMN role VARCHAR(20) NOT NULL COMMENT 'owner=所有者 / member=成员';

-- ============================================================================
-- F.3 归属列：group_id → org_id（五张表）+ 索引改名
-- ============================================================================
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members'
      AND COLUMN_NAME = 'group_id') = 1,
  'ALTER TABLE organization_members CHANGE COLUMN group_id org_id VARCHAR(36) NOT NULL', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND COLUMN_NAME = 'group_id') = 1,
  'ALTER TABLE records CHANGE COLUMN group_id org_id VARCHAR(36) DEFAULT ''''
     COMMENT ''归属组织；空=无组织，代码层降级为“无数据”（fail-closed）''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task'
      AND COLUMN_NAME = 'group_id') = 1,
  'ALTER TABLE nlp_task CHANGE COLUMN group_id org_id VARCHAR(36)
     COMMENT ''提交时所属组织（组织隔离查询用）''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task'
      AND COLUMN_NAME = 'group_id') = 1,
  'ALTER TABLE qc_task CHANGE COLUMN group_id org_id VARCHAR(36)
     COMMENT ''提交时所属组织快照，供 worker 重建 RecordFilter（数据域）''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND COLUMN_NAME = 'group_id') = 1,
  'ALTER TABLE review_tasks CHANGE COLUMN group_id org_id VARCHAR(36)
     COMMENT ''任务所属组织；写入时打标，避免查询期 JOIN（QueryWrapper 不便 JOIN，而 listTasks 是高频路径）''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND COLUMN_NAME = 'group_id') = 1,
  'ALTER TABLE operation_log CHANGE COLUMN group_id org_id VARCHAR(36)
     COMMENT ''操作时所属组织，留痕用（按它做三档可见性）''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 索引改名（先删旧名再建新名，各自带守卫）
SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_group') = 1,
  'ALTER TABLE records DROP INDEX idx_records_group', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_org') = 0,
  'ALTER TABLE records ADD INDEX idx_records_org (org_id)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task'
      AND INDEX_NAME = 'idx_nlp_task_group') = 1,
  'ALTER TABLE nlp_task DROP INDEX idx_nlp_task_group', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task'
      AND INDEX_NAME = 'idx_nlp_task_org') = 0,
  'ALTER TABLE nlp_task ADD INDEX idx_nlp_task_org (org_id)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task'
      AND INDEX_NAME = 'idx_qc_task_group') = 1,
  'ALTER TABLE qc_task DROP INDEX idx_qc_task_group', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task'
      AND INDEX_NAME = 'idx_qc_task_org') = 0,
  'ALTER TABLE qc_task ADD INDEX idx_qc_task_org (org_id)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND INDEX_NAME = 'idx_review_task_group') = 1,
  'ALTER TABLE review_tasks DROP INDEX idx_review_task_group', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND INDEX_NAME = 'idx_review_task_org') = 0,
  'ALTER TABLE review_tasks ADD INDEX idx_review_task_org (org_id)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- F.4 日志索引：单列 idx_operator → (org_id, log_time) + (operator, log_time)
--     idx_operator_time 覆盖 AI 助手的 listRecentByOperator（WHERE operator=? ORDER BY log_time DESC），
--     故单列 idx_operator 被取代。
-- ============================================================================
SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_operator') = 1,
  'ALTER TABLE operation_log DROP INDEX idx_operator', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_group_time') = 0,
  'ALTER TABLE operation_log ADD INDEX idx_group_time (org_id, log_time)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_operator_time') = 0,
  'ALTER TABLE operation_log ADD INDEX idx_operator_time (operator, log_time)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- F.5 users.status 改为 active/disabled 语义（不再有「待分配池 / 审批中」）
-- ============================================================================
ALTER TABLE users
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active'
    COMMENT 'active/disabled',
  MODIFY COLUMN has_pending_group TINYINT NOT NULL DEFAULT 0
    COMMENT '已废弃：组织改为自助创建、取消审核，代码侧不再读写（本列保留以免存量库回滚丢数据）';

-- ============================================================================
-- F.6 四张配置表（第五张 dictionary_backups 见文件头说明）
-- ============================================================================
CREATE TABLE IF NOT EXISTS dictionary_terms (
  id VARCHAR(36) PRIMARY KEY,
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层（不用 NULL，见注 1）",
  type VARCHAR(20) NOT NULL COMMENT 'disease/pattern/symptom/herb/formula',
  standard_term VARCHAR(200) NOT NULL,
  code VARCHAR(100) COMMENT '预留：国标代码（GB/T 15657 / 16751），当前无消费方',
  source VARCHAR(100),
  aliases JSON,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_org_type_term (org_id, type, standard_term),
  INDEX idx_org_type (org_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='术语词典词条';

CREATE TABLE IF NOT EXISTS dictionary_versions (
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层",
  type VARCHAR(20) NOT NULL COMMENT 'disease/pattern/symptom/herb/formula',
  version VARCHAR(32) NOT NULL COMMENT 'DB 侧内容版本：该 (org,type) 词条内容的 MD5 前 12 位',
  indexed_version VARCHAR(32) NULL COMMENT 'ES 侧已灌入的版本；NULL 或 <> version 即「待重建」',
  indexed_at DATETIME NULL COMMENT '最近一次成功灌入 ES 的时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (org_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='词典内容版本与 ES 索引状态（按组织）';

CREATE TABLE IF NOT EXISTS qc_rules (
  org_id VARCHAR(36) PRIMARY KEY,
  rules_json LONGTEXT NOT NULL,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质控规则（每组织一份）';

CREATE TABLE IF NOT EXISTS user_llm_config (
  user_id VARCHAR(36) PRIMARY KEY,
  enabled TINYINT NOT NULL DEFAULT 0,
  provider VARCHAR(30),
  base_url VARCHAR(300),
  model VARCHAR(100),
  temperature DOUBLE,
  timeout INT,
  api_key VARCHAR(1000) COMMENT 'AES-256-GCM 加密后的密文（Base64）',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户 LLM 配置';

-- ============================================================================
-- F.7 records 去重兜底：text_hash + 唯一键
--     NULL 不参与唯一约束（MySQL 唯一索引对 NULL 不去重），故 NULL = 不参与判重
-- ============================================================================
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND COLUMN_NAME = 'text_hash') = 0,
  'ALTER TABLE records ADD COLUMN text_hash CHAR(32) NULL
     COMMENT ''21 字段 MD5；NULL = 不参与唯一约束''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 先修形状：若同名唯一键存在但只剩一列（旧版回滚脚本的守卫写成 COUNT(*) = 1，
-- 而复合索引在 information_schema.STATISTICS 里是两行 → 那次 DROP 从未执行，
-- 索引随 text_hash 列一起被 MySQL 自动缩成单列），这里先删掉再重建。
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'uk_records_org_text_hash') = 1,
  'ALTER TABLE records DROP INDEX uk_records_org_text_hash', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'uk_records_org_text_hash') = 0,
  'ALTER TABLE records ADD UNIQUE KEY uk_records_org_text_hash (org_id, text_hash)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- F.8 review_tasks：生成列 active_key + 唯一键 uk_record_active
--     活跃行 = record_id、作废行 = NULL，于是「一个病历至多一条待复核任务」由索引仲裁，
--     不再依赖 check-then-act。
-- ============================================================================
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND COLUMN_NAME = 'active_key') = 0,
  'ALTER TABLE review_tasks ADD COLUMN active_key VARCHAR(36) GENERATED ALWAYS AS
     (IF(is_obsolete = 0, record_id, NULL)) STORED
     COMMENT ''活跃行=record_id，作废行=NULL；配合 uk_record_active 约束「一个病历至多一条待复核任务」''',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND INDEX_NAME = 'uk_record_active') = 0,
  'ALTER TABLE review_tasks ADD UNIQUE KEY uk_record_active (active_key)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- F.9 任务取消位落库（工作线程读 DB 判断取消，跨实例与重启都有效）
-- ============================================================================
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task'
      AND COLUMN_NAME = 'cancel_requested') = 0,
  'ALTER TABLE nlp_task ADD COLUMN cancel_requested TINYINT NOT NULL DEFAULT 0
     COMMENT ''1=已请求取消''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task'
      AND COLUMN_NAME = 'cancel_requested') = 0,
  'ALTER TABLE qc_task ADD COLUMN cancel_requested TINYINT NOT NULL DEFAULT 0
     COMMENT ''1=已请求取消''', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 正向自检（五条都应为 1 / 0 行）
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations';        -- 应 1
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'research_groups';      -- 应 0
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
--      AND COLUMN_NAME = 'org_id';                                            -- 应 1
--   SELECT COUNT(*) FROM information_schema.STATISTICS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
--      AND INDEX_NAME = 'uk_records_org_text_hash';                           -- 应 1
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN
--      ('dictionary_terms','dictionary_versions','qc_rules','user_llm_config'); -- 应 4
-- ============================================================================
