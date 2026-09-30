-- 中医电子病历质控与标准化系统 - 数据库初始化脚本
-- 字符集：utf8mb4（支持4字节生僻字，如"㿠"U+3FE0）

CREATE DATABASE IF NOT EXISTS tcm_ehr
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE tcm_ehr;

-- ============================================================================
-- 【存量库升级】把旧结构升级到组织化结构（幂等，新装库整段跳过）
--
-- 为什么必须有这段：下面所有建表语句都是 CREATE TABLE IF NOT EXISTS，
-- 对**存量库**的「改名 / 加列 / 删列 / 加索引」一律不生效。旧库不升级，
-- 启动就会 Unknown column。
--
-- 全部步骤幂等（判存在再改），可重复执行。执行顺序不可颠倒：
-- 先改名 → 补列 → 填数据 → 最后加约束。中途失败不会留下
-- 「迁移未完成 = 全库可见」的危险中间态：org_id 为空时代码一律 fail-closed。
--
-- ⚠️ 0.1 是 2026-09-30 的事故修复：当天验证本脚本语法时误把脚本喂给真实库，
--    建出两张**空表** organizations / organization_members，使 ① 的改名守卫
--    （目标表必须不存在）失效 —— 迁移会被静默跳过，数据留在 research_groups，
--    而代码查 organizations，表现为「所有组织数据不可见」。
--    0.1 把这种双表并存状态纠正回来。
--
-- 回滚请单独执行：data/migration-03-organizations-rollback.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0.1 纠正「旧表与误建空表并存」：把误建的那张挪走，再让 ① 正常改名。
--     用 RENAME 而不是 DROP —— 万一它真有数据，也只是被挪走而不是丢掉。
--     前置校验会先打印两张表的行数，请确认 organizations 为 0 再继续。
-- ---------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.TABLES
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') AS has_organizations,
       (SELECT COUNT(*) FROM information_schema.TABLES
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'research_groups') AS has_research_groups;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 1
  AND (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'research_groups') = 1,
  'RENAME TABLE organizations TO _stale_organizations',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members') = 1
  AND (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'group_members') = 1,
  'RENAME TABLE organization_members TO _stale_organization_members',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ① 组织侧改名（幂等：源表存在且目标表不存在才改）
--    fk_member_group 的外键会随 RENAME 自动跟随，不需要先删
-- ---------------------------------------------------------------------------
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'research_groups') = 1
  AND (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 0,
  'RENAME TABLE research_groups TO organizations, group_members TO organization_members',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- ② 组织侧补列 / 删审核残留列（幂等：先判存在再改）
--
--    ⚠️ **审核残留列本批只删不清理语义**：`applied_by` / `reviewed_by` /
--    `reviewed_at` / `reject_reason` 的写入方在「去审核」改造（批次 6）里才移除，
--    本批删列后到批次 6 之间存在一个「代码仍会写 pending 组、但表里没有审核痕迹」的
--    窗口。这是刻意接受的：status 默认值改 active（见 ②.3）后，代码写 pending 仍能
--    存下（VARCHAR(20) 不校验取值），不会中断。批次 6 落地后语义彻底统一。
--    提前到本批删 PENDING/REJECTED 常量会让 register 的建组申请当场编译不过。
-- ---------------------------------------------------------------------------
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members'
      AND COLUMN_NAME = 'can_write_dictionary') = 0,
  "ALTER TABLE organization_members
     ADD COLUMN can_write_dictionary TINYINT NOT NULL DEFAULT 0 COMMENT '词典写授权开关',
     ADD COLUMN can_write_qc_rules   TINYINT NOT NULL DEFAULT 0 COMMENT '质控规则写授权开关'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members'
      AND COLUMN_NAME = 'role') = 1,
  "ALTER TABLE organization_members
     MODIFY COLUMN role VARCHAR(20) NOT NULL COMMENT 'owner=所有者 / member=成员'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'owner_user_id') = 0,
  "ALTER TABLE organizations
     ADD COLUMN owner_user_id VARCHAR(36) COMMENT '所有者冗余展示，权威以 organization_members.role=owner'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ②.2 删审核残留四列（逐列判存在，缺一列就跳）
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'applied_by') = 1,
  'ALTER TABLE organizations DROP COLUMN applied_by',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reviewed_by') = 1,
  'ALTER TABLE organizations DROP COLUMN reviewed_by',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reviewed_at') = 1,
  'ALTER TABLE organizations DROP COLUMN reviewed_at',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reject_reason') = 1,
  'ALTER TABLE organizations DROP COLUMN reject_reason',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ②.3 status：默认值改 active（active/stopped/archived 是最终口径）
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'status') = 1,
  "ALTER TABLE organizations
     MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active'
       COMMENT 'active/stopped/archived'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- ③ 归属列改名 + 索引改名
--    records / nlp_task / qc_task / review_tasks 四表：group_id -> org_id，索引同步改名
--    operation_log：它**原本就没有 idx_*_group**（只有 idx_log_time / idx_operator /
--    idx_action），列改名后它的索引在 ④ 单独处理
-- ---------------------------------------------------------------------------
-- ③.0 organization_members：RENAME 只改了表名，列名还是 group_id。
--      实体 OrganizationMember.orgId 映射的是 org_id，不改这里每查一次成员就 Unknown column。
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members'
      AND COLUMN_NAME = 'group_id') = 1,
  'ALTER TABLE organization_members
     CHANGE COLUMN group_id org_id VARCHAR(36) NOT NULL',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE records
     CHANGE COLUMN group_id org_id VARCHAR(36) DEFAULT '',
     DROP INDEX idx_records_group,
     ADD INDEX idx_records_org (org_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE nlp_task
     CHANGE COLUMN group_id org_id VARCHAR(36) COMMENT '提交时所属组织',
     DROP INDEX idx_nlp_task_group,
     ADD INDEX idx_nlp_task_org (org_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE qc_task
     CHANGE COLUMN group_id org_id VARCHAR(36) COMMENT '提交时所属组织快照，供 worker 重建 RecordFilter',
     DROP INDEX idx_qc_task_group,
     ADD INDEX idx_qc_task_org (org_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE review_tasks
     CHANGE COLUMN group_id org_id VARCHAR(36) COMMENT '任务所属组织',
     DROP INDEX idx_review_task_group,
     ADD INDEX idx_review_task_org (org_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE operation_log
     CHANGE COLUMN group_id org_id VARCHAR(36) COMMENT '操作时所属组织，留痕用'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- ④ 日志索引：单列 idx_operator 被 (operator, create_time) 覆盖后删除
--    idx_operator_time 同时覆盖 AI 助手的 listRecentByOperator
--    （WHERE operator=? ORDER BY create_time DESC），可安全删单列
-- ---------------------------------------------------------------------------
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log') = 1
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_group_time') = 0,
  "ALTER TABLE operation_log
     ADD INDEX idx_group_time (org_id, log_time),
     ADD INDEX idx_operator_time (operator, log_time)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log') = 1
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_operator') = 1,
  'ALTER TABLE operation_log DROP INDEX idx_operator',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- ⑤ users.status 与组织 status 的存量归一
--
-- ⚠️ 计划原文只刷了 users.status，**漏了组织表**：库里存在 status='pending' 的
--    待审批组织（迁移 02 建的申请组），只改默认值不动这些行，它们会永远停在
--    已废弃的 pending 上 —— 既不在「已生效」列表里，也没有审批人能处理。
--    故一并归为 active，并清掉对应的 has_pending_group 标记。
-- ---------------------------------------------------------------------------
-- 下面几条是数据迁移，**必须守卫**：全新库里 organizations / users 还没建
-- （本段插在建表语句之前），裸 UPDATE 会直接报 1146 表不存在。
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations') = 1,
  "UPDATE organizations SET status = 'active' WHERE status IN ('pending', 'rejected')",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users') = 1,
  "UPDATE users SET status = 'active' WHERE status = 'pending'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users') = 1,
  "UPDATE users SET has_pending_group = 0",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'status') = 1,
  "ALTER TABLE users
     MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active'
       COMMENT 'active/disabled',
     MODIFY COLUMN has_pending_group TINYINT NOT NULL DEFAULT 0
       COMMENT '已废弃：组织改为自助创建、取消审核，代码侧不再读写（本列保留以免回滚丢数据）'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- ⑥ 五张配置新表（幂等：IF NOT EXISTS）
--    与 database-init.sql 中的建表语句保持一致
-- ---------------------------------------------------------------------------
-- 注 1：基础层用 org_id = '' 空串，**不用 NULL**。
--      MySQL 唯一索引把 NULL 视为互不相等，org_id IS NULL 的基础层不会被去重，
--      同一 (type, standard_term) 可重复插入。org_id 是 UUID 串，空串不会与真实组织撞。
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

-- 注 2：dictionary_backups 取代原「文件备份目录」，org_id='' 同样是系统基础层
CREATE TABLE IF NOT EXISTS dictionary_backups (
  id VARCHAR(36) PRIMARY KEY,
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层",
  type VARCHAR(20) NOT NULL,
  snapshot LONGTEXT NOT NULL,
  created_by VARCHAR(36),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_backup_org_type (org_id, type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='词典回滚快照';

-- 注 3：内容版本落 DB，不放 ES。_meta.version 降级为「索引结构版本」
--      （单索引 + org_id 字段的方案下，一个索引只有一份 _meta，装不下每组织一份版本）
CREATE TABLE IF NOT EXISTS dictionary_versions (
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层",
  type VARCHAR(20) NOT NULL COMMENT 'disease/pattern/symptom/herb/formula',
  version VARCHAR(32) NOT NULL COMMENT 'DB 侧内容版本：该 (org,type) 词条内容的 MD5 前 12 位',
  indexed_version VARCHAR(32) NULL COMMENT 'ES 侧已成功灌入的版本；NULL 或 <> version 即「待重建」',
  indexed_at DATETIME NULL COMMENT '最近一次成功灌入 ES 的时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (org_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='词典内容版本与 ES 索引状态（按组织）';

-- 注 4：组织在 qc_rules 无记录时回退 QcRuleSet.defaults()，不落库
CREATE TABLE IF NOT EXISTS qc_rules (
  org_id VARCHAR(36) PRIMARY KEY,
  rules_json LONGTEXT NOT NULL,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质控规则（每组织一份）';

-- 注 5：api_key 存 AES-256-GCM 密文（Base64），明文永不落库、永不返回
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


-- ---------------------------------------------------------------------------
-- ⑦ 病历去重兜底：text_hash 落库 + 联合唯一键
--
-- 决定（2026-09-29）：落库 + **(org_id, text_hash) 联合唯一**，不用单列。
--   理由：数据隔离是本项目核心诉求。text_hash 单列唯一会让「组织 B 导入同份病历」
--   直接撞唯一键，业务上说不通；联合键与代码侧「查重按本组」的口径一致。
--
-- ⚠️ **存量行的 text_hash 保持 NULL**（不为老数据回填）：
--   回填要在 SQL 里复刻 RecordUtil.textHash 的 21 字段 MD5 拼接顺序，
--   一旦与 Java 侧实现漂移就会算出不同哈希，反而把不重复的数据判成重复。
--   MySQL 唯一索引视多个 NULL 为互不相等，故存量行之间、以及存量与新行之间
--   都不会被这个键拦住 —— 代价是**老数据不受 DB 兜底保护**。
--   批次 4 随 DDL 改的代码会让**新增**数据开始写 text_hash，从此新数据受保护；
--   若需要给老数据补兜底，应另做「用同一 Java 实现逐行回填」的一次性作业，不要写进本脚本。
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records' AND COLUMN_NAME = 'text_hash') = 0,
  "ALTER TABLE records
     ADD COLUMN text_hash CHAR(32) NULL COMMENT '21 字段 MD5，见 RecordUtil.textHash；存量行 NULL = 不参与唯一约束'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records') = 1
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'uk_records_org_text_hash') = 0,
  "ALTER TABLE records
     ADD UNIQUE KEY uk_records_org_text_hash (org_id, text_hash)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- ⑧ 复核任务「至多一条活跃任务」约束：并发重复建 pending 任务的 DB 兜底
--    ⚠️ 必须与批次 3 随 DDL 改的「upsert 改原子 SQL」同批发布：
--    唯一索引上线后，原「先查后写」的 check-then-act 在并发下会从
--    「静默重复」变成「整批 500」。
--
--    ⚠️ **不能用 UNIQUE(record_id, is_obsolete)**：本表是「作废而非删除」的轨迹表，
--    一条病历反复重算会攒下多条 is_obsolete=1 的历史行，第二次作废就撞唯一键
--    （真线上会跑到的路径）。改用生成列只约束活跃行：作废行取 NULL，
--    MySQL 唯一索引视多个 NULL 互不相等，历史轨迹可以无限追加。
-- ---------------------------------------------------------------------------
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND COLUMN_NAME = 'active_key') = 0,
  "ALTER TABLE review_tasks
     ADD COLUMN active_key VARCHAR(36)
       GENERATED ALWAYS AS (IF(is_obsolete = 0, record_id, NULL)) STORED
       COMMENT '活跃行=record_id，作废行=NULL；配合 uk_record_active 约束「一个病历至多一条待复核任务」'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks') = 1
  AND (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND INDEX_NAME = 'uk_record_active') = 0,
  'ALTER TABLE review_tasks ADD UNIQUE KEY uk_record_active (active_key)',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- ⑨ 批任务取消标记列：取消位从内存态落库（工作线程改为读 DB 判断取消）
-- ---------------------------------------------------------------------------
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task'
      AND COLUMN_NAME = 'cancel_requested') = 0,
  "ALTER TABLE nlp_task ADD COLUMN cancel_requested TINYINT NOT NULL DEFAULT 0
     COMMENT '1=已请求取消；工作线程读本列判断，不依赖内存取消位'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task') = 1
  AND (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task'
      AND COLUMN_NAME = 'cancel_requested') = 0,
  "ALTER TABLE qc_task ADD COLUMN cancel_requested TINYINT NOT NULL DEFAULT 0
     COMMENT '1=已请求取消；工作线程读本列判断，不依赖内存取消位'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================

-- 0.2 收尾：清掉 0.1 挪走的空表。
--     ⚠️ 仅当它们确实是空表才应继续执行到这里（0.1 的前置校验会打印行数）；
--     若发现里面有数据，说明库不是本脚本预期的状态，请停下人工确认。
DROP TABLE IF EXISTS _stale_organizations;
DROP TABLE IF EXISTS _stale_organization_members;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(50) UNIQUE NOT NULL,
  password VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT '角色：管理员/用户',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  -- 组织化（批次 4）：账号状态两态；组织改自助创建后不再有「待分配池/审批中」
  status VARCHAR(20) NOT NULL DEFAULT 'active'
    COMMENT 'active/disabled',
  has_pending_group TINYINT NOT NULL DEFAULT 0
    COMMENT '已废弃：组织改为自助创建、取消审核，代码侧不再读写（本列保留以免存量库回滚丢数据）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ===== 组织：2 张表（阶段2 建表，批次 4 改名 + 去审核残留）=====
-- 组织内角色（owner/member）刻意不塞进 users.role：两者作用域不同
-- （users.role 是系统级，organization_members.role 是组织内级），混在一起就会出现
-- 「是某组织所有者」这种无法在 users 表上表达的状态。
CREATE TABLE IF NOT EXISTS organizations (
  id VARCHAR(36) PRIMARY KEY,
  code VARCHAR(50) NOT NULL UNIQUE COMMENT '组织唯一编码，如 NEURO-2026；创建时即校验，防抢占',
  name VARCHAR(100) NOT NULL COMMENT '组织显示名，可重复',
  purpose VARCHAR(500) COMMENT '用途说明（可选）',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active/stopped/archived',
  owner_user_id VARCHAR(36) COMMENT '所有者冗余展示，权威以 organization_members.role=owner',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_org_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织';

-- ⚠️ idx_member_user 覆盖 (user_id, is_primary) 的最左前缀：JwtInterceptor 每请求按
-- user_id 解析主组织，没有这个索引就是每请求一次全表扫。
CREATE TABLE IF NOT EXISTS organization_members (
  id VARCHAR(36) PRIMARY KEY,
  org_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT 'owner=所有者 / member=成员',
  can_write_dictionary TINYINT NOT NULL DEFAULT 0 COMMENT '词典写授权开关',
  can_write_qc_rules TINYINT NOT NULL DEFAULT 0 COMMENT '质控规则写授权开关',
  is_primary TINYINT NOT NULL DEFAULT 1 COMMENT '主组织标记，预留一人多组织',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_org_user (org_id, user_id),
  INDEX idx_member_user (user_id, is_primary) COMMENT '每请求按 user_id 取主组织，必须有',
  CONSTRAINT fk_member_org FOREIGN KEY (org_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织成员';

-- 2. 病历表（21个原始字段 + NLP/质控结果）
CREATE TABLE IF NOT EXISTS records (
  id VARCHAR(36) PRIMARY KEY COMMENT '主键UUID',
  registration_no VARCHAR(50) COMMENT '登记号',
  outpatient_no VARCHAR(50) COMMENT '门诊号',
  gender VARCHAR(10) COMMENT '性别',
  age VARCHAR(20) COMMENT '年龄',
  visit_count INT COMMENT '就诊次数',
  western_diagnosis TEXT COMMENT '西医诊断',
  tcm_diagnosis TEXT COMMENT '中医诊断',
  present_illness TEXT COMMENT '现病史',
  chief_complaint TEXT COMMENT '主诉',
  self_report TEXT COMMENT '自诉',
  inspection TEXT COMMENT '望诊',
  pulse TEXT COMMENT '脉诊',
  tongue TEXT COMMENT '舌诊',
  physical_exam TEXT COMMENT '查体',
  pattern TEXT COMMENT '辨证结论/证候',
  prescription TEXT COMMENT '草药',
  follow_up TEXT COMMENT '随访',
  treatment_effect TEXT COMMENT '治疗效果',
  department VARCHAR(50) COMMENT '开单科室',
  doctor_id VARCHAR(50) COMMENT '医生工号',
  visit_time DATETIME COMMENT '接诊时间',
  structured_data JSON COMMENT '结构化数据（NLP抽取结果）',
  qc_results JSON COMMENT '质控检查结果',
  score INT COMMENT '质控评分（满分100）',
  grade VARCHAR(20) COMMENT '分级：合格/待复核/无效（**只做质控结论**，不再当权限用）',
  org_id VARCHAR(36) DEFAULT '' COMMENT '归属组织；空=无组织，代码层降级为“无数据”（fail-closed）',
  status VARCHAR(20) COMMENT '状态：pending/reviewing/completed/invalid',
  governed TINYINT NOT NULL DEFAULT 0 COMMENT '已清洗标记（清洗+术语归一完成后置1）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_registration_no (registration_no),
  INDEX idx_status (status),
  INDEX idx_grade (grade),
  INDEX idx_department_visit_time (department, visit_time),
  -- 去重兜底：text_hash = 21 字段 MD5（RecordUtil.textHash）。
  -- ⚠️ 存量行保持 NULL：回填要在 SQL 里复刻 Java 侧拼接顺序，一旦漂移就会把
  --    不重复的数据判成重复。MySQL 唯一索引视多个 NULL 互不相等，故存量行之间
  --    不受此键约束（老数据去重仍靠导入时的代码查重），新增数据才受 DB 兜底。
  text_hash CHAR(32) NULL COMMENT '21 字段 MD5；NULL = 不参与唯一约束',
  UNIQUE KEY uk_records_org_text_hash (org_id, text_hash),
  INDEX idx_records_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='病历表';

-- 3. 复核任务表
CREATE TABLE IF NOT EXISTS review_tasks (
  id VARCHAR(36) PRIMARY KEY,
  record_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) COMMENT '状态：pending/completed',
  issue_type VARCHAR(50) COMMENT '问题类型（缺失字段/逻辑冲突/评分不达标）',
  score INT COMMENT '当前评分',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  deadline_time DATETIME COMMENT '复核截止时间（创建时间+7个工作日）',
  reviewed_by VARCHAR(50) COMMENT '复核人用户名',
  completed_time DATETIME COMMENT '复核完成时间',
  is_obsolete TINYINT NOT NULL DEFAULT 0 COMMENT '作废标记：病历重新评分后不再是待复核则置1（查询/统计/看板统一过滤 is_obsolete=0）',
  org_id VARCHAR(36) COMMENT '任务所属组织；写入时打标，避免查询期 JOIN（QueryWrapper 不便 JOIN，而 listTasks 是高频路径）',
  INDEX idx_record_id (record_id),
  INDEX idx_obsolete (is_obsolete),
  -- 并发重复建 pending 任务的 DB 兜底：必须与「upsert 改原子 SQL」同批发布，
  -- 否则先查后写的 check-then-act 会从「静默重复」变成「整批 500」。
  --
  -- ⚠️ **不能直接用 UNIQUE(record_id, is_obsolete)**：本表是「作废而非删除」的轨迹表，
  --    一条病历反复重算会攒下多条 is_obsolete=1 的历史行，第二次作废就撞唯一键。
  --    故用生成列只约束「活跃行」：作废行取 NULL，MySQL 视多个 NULL 互不相等。
  active_key VARCHAR(36) GENERATED ALWAYS AS
    (IF(is_obsolete = 0, record_id, NULL)) STORED
    COMMENT '活跃行=record_id，作废行=NULL；配合 uk_record_active 约束「一个病历至多一条待复核任务」',
  UNIQUE KEY uk_record_active (active_key),
  INDEX idx_review_task_org (org_id),
  CONSTRAINT fk_review_record FOREIGN KEY (record_id) REFERENCES records(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复核任务表';

-- 4. 操作日志表（批A·C5：OperationLogger 文件 + 入库双写；审计页 GET /api/logs 读本表做分页筛选）
--    文件 logs/operation.log 为兜底备份，本表为审计查询数据源；库写失败不阻塞业务
CREATE TABLE IF NOT EXISTS operation_log (
  id VARCHAR(36) PRIMARY KEY COMMENT '主键UUID',
  log_time DATETIME NOT NULL COMMENT '操作时间',
  operator VARCHAR(50) COMMENT '操作人用户名',
  role VARCHAR(20) COMMENT '操作人角色：管理员/用户',
  action VARCHAR(50) COMMENT '操作类型：数据清洗/数据集导出/词典导入/词典回滚/人工复核/批量重算',
  target VARCHAR(255) COMMENT '操作对象：筛选范围/文件名/词典类型/病历ID',
  detail TEXT COMMENT '操作明细',
  org_id VARCHAR(36) COMMENT '操作时所属组织，留痕用（按它做三档可见性）',
  INDEX idx_log_time (log_time),
  -- idx_operator_time 覆盖 AI 助手的 listRecentByOperator
  -- （WHERE operator=? ORDER BY log_time DESC），故单列 idx_operator 已被取代
  INDEX idx_action (action),
  INDEX idx_group_time (org_id, log_time),
  INDEX idx_operator_time (operator, log_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';

-- 5. NLP 批量解析任务表（批K·K-a：后端异步任务，进度/失败清单落库，重启后可查）
CREATE TABLE IF NOT EXISTS nlp_task (
  id VARCHAR(36) PRIMARY KEY COMMENT '任务ID(UUID)',
  status VARCHAR(20) NOT NULL COMMENT '状态：QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED',
  total INT NOT NULL DEFAULT 0 COMMENT '计划处理条数',
  done INT NOT NULL DEFAULT 0 COMMENT '已处理条数',
  success INT NOT NULL DEFAULT 0 COMMENT '成功条数',
  failed INT NOT NULL DEFAULT 0 COMMENT '失败条数',
  current_label VARCHAR(255) COMMENT '当前处理的病历标识',
  filters_json TEXT COMMENT '筛选范围(JSON)',
  created_by VARCHAR(50) COMMENT '提交人用户名',
  failure_list JSON COMMENT '失败清单(仅存前500条)',
  failure_truncated TINYINT NOT NULL DEFAULT 0 COMMENT '失败清单是否被截断',
  org_id VARCHAR(36) COMMENT '提交时所属组织（组织隔离查询用）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at DATETIME COMMENT '开始时间',
  finished_at DATETIME COMMENT '结束时间',
  INDEX idx_status (status),
  INDEX idx_create_time (create_time),
  -- 取消位落库：工作线程读本列判断，不再依赖内存 Set（重启即失）
  cancel_requested TINYINT NOT NULL DEFAULT 0 COMMENT '1=已请求取消',
  INDEX idx_nlp_task_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NLP批量解析任务表';

-- 批量质控重算任务（§七 L5）。异步化后前端提交即拿 taskId、轮询进度，不再长时间占请求线程。
-- role 列是**提交时的角色快照**：后台线程读不到 RequestContextHolder（会拿到 "unknown"），
-- worker 必须用它重建 RecordFilter，否则 domainGrade 返回 null → 退化成「不过滤 = 全库」。
CREATE TABLE IF NOT EXISTS qc_task (
  id VARCHAR(36) PRIMARY KEY COMMENT '任务ID(UUID)',
  status VARCHAR(20) NOT NULL COMMENT '状态：QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED',
  total INT NOT NULL DEFAULT 0 COMMENT '计划处理条数',
  done INT NOT NULL DEFAULT 0 COMMENT '已处理条数',
  success INT NOT NULL DEFAULT 0 COMMENT '成功条数',
  failed INT NOT NULL DEFAULT 0 COMMENT '失败条数',
  qualified INT NOT NULL DEFAULT 0 COMMENT '合格数',
  pending_review INT NOT NULL DEFAULT 0 COMMENT '待复核数',
  invalid INT NOT NULL DEFAULT 0 COMMENT '无效数',
  current_label VARCHAR(255) COMMENT '当前处理的病历标识',
  filters_json TEXT COMMENT '筛选范围(JSON)',
  created_by VARCHAR(50) COMMENT '提交人用户名',
  role VARCHAR(20) COMMENT '提交时角色快照，仅用于审计日志回填',
  org_id VARCHAR(36) COMMENT '提交时所属组织快照，供 worker 重建 RecordFilter（数据域）',
  failure_list JSON COMMENT '失败清单(仅存前500条)',
  failure_truncated TINYINT NOT NULL DEFAULT 0 COMMENT '失败清单是否被截断',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at DATETIME COMMENT '开始时间',
  finished_at DATETIME COMMENT '结束时间',
  INDEX idx_status (status),
  INDEX idx_create_time (create_time),
  cancel_requested TINYINT NOT NULL DEFAULT 0 COMMENT '1=已请求取消',
  INDEX idx_qc_task_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质控批量重算任务表';

-- ===== 配置模块：5 张新表（批次 4）=====
-- 注 1：基础层用 org_id = '' 空串，**不用 NULL**。MySQL 唯一索引把 NULL 视为互不相等，
--      org_id IS NULL 的基础层不会被去重，同一 (type, standard_term) 可重复插入。
--      org_id 是 UUID 串，空串不会与真实组织撞。
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

CREATE TABLE IF NOT EXISTS dictionary_backups (
  id VARCHAR(36) PRIMARY KEY,
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层",
  type VARCHAR(20) NOT NULL,
  snapshot LONGTEXT NOT NULL,
  created_by VARCHAR(36),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_backup_org_type (org_id, type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='词典回滚快照';

-- 内容版本落 DB，不放 ES。_meta.version 降级为「索引结构版本」：单索引 + org_id 字段的
-- 方案下一个索引只有一份 _meta，装不下「每组织一份版本」。
CREATE TABLE IF NOT EXISTS dictionary_versions (
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层",
  type VARCHAR(20) NOT NULL COMMENT 'disease/pattern/symptom/herb/formula',
  version VARCHAR(32) NOT NULL COMMENT 'DB 侧内容版本：该 (org,type) 词条内容的 MD5 前 12 位',
  indexed_version VARCHAR(32) NULL COMMENT 'ES 侧已灌入的版本；NULL 或 <> version 即「待重建」',
  indexed_at DATETIME NULL COMMENT '最近一次成功灌入 ES 的时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (org_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='词典内容版本与 ES 索引状态（按组织）';

-- 组织无记录时回退 QcRuleSet.defaults()，不落库
CREATE TABLE IF NOT EXISTS qc_rules (
  org_id VARCHAR(36) PRIMARY KEY,
  rules_json LONGTEXT NOT NULL,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质控规则（每组织一份）';

-- api_key 存 AES-256-GCM 密文（Base64），明文永不落库、永不返回
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

-- 初始化账号（密码均为123456的BCrypt加密）
-- admin/123456 = 管理员；auditor/123456 = 普通用户（存量「审核员」已不再是独立角色）
INSERT INTO users (id, username, password, role) VALUES
('admin-0001', 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员'),
('audit-0001', 'auditor', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '用户');
