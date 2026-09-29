-- ============================================================================
-- 迁移 03：组织化改造 · DDL 单一窗口（正 + 反向）
--
-- 为什么需要单独脚本：database-init.sql 不被 Spring 自动执行（application.yml 无
-- spring.sql.init），且只有 CREATE TABLE IF NOT EXISTS —— 对**存量库**的改列 / 删列 /
-- 加索引都不会生效。
--
-- **全部幂等**：可重复执行。执行顺序不可颠倒（先改名 → 补列 → 填数据 → 最后加约束）。
-- 不加 NOT NULL：即使本脚本没跑完，代码对空 org_id 也降级为「无数据」，
-- 不会出现「迁移未完成 = 全库可见」这种危险中间态。
--
-- ⚠️ **必须与「批次 5 · 命名与双路径」同一停服窗口发布**：
--    本脚本把表名/列名改成 org_*，而批次 5 才把代码里的 group 字面名改过来。
--    只发布其中之一 = 服务直接不可用（表不存在 / Unknown column）。
--
-- 执行要求：
--   mysql --default-character-set=utf8mb4 < tcm_ehr < data/migration-03-organizations.sql
--   ⚠️ 不带 --default-character-set=utf8mb4 会把中文注释写成「?」
--   执行前先 mysqldump 备份涉及的表；执行后跑脚本末尾的【回滚演练】对照。
-- ============================================================================


-- ---------------------------------------------------------------------------
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
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members'
      AND COLUMN_NAME = 'can_write_dictionary') = 0,
  "ALTER TABLE organization_members
     ADD COLUMN can_write_dictionary TINYINT NOT NULL DEFAULT 0 COMMENT '词典写授权开关',
     ADD COLUMN can_write_qc_rules   TINYINT NOT NULL DEFAULT 0 COMMENT '质控规则写授权开关'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_members'
      AND COLUMN_NAME = 'role') = 1,
  "ALTER TABLE organization_members
     MODIFY COLUMN role VARCHAR(20) NOT NULL COMMENT 'owner=所有者 / member=成员'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'owner_user_id') = 0,
  "ALTER TABLE organizations
     ADD COLUMN owner_user_id VARCHAR(36) COMMENT '所有者冗余展示，权威以 organization_members.role=owner'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ②.2 删审核残留四列（逐列判存在，缺一列就跳）
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'applied_by') = 1,
  'ALTER TABLE organizations DROP COLUMN applied_by',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reviewed_by') = 1,
  'ALTER TABLE organizations DROP COLUMN reviewed_by',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reviewed_at') = 1,
  'ALTER TABLE organizations DROP COLUMN reviewed_at',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'reject_reason') = 1,
  'ALTER TABLE organizations DROP COLUMN reject_reason',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ②.3 status：默认值改 active（active/stopped/archived 是最终口径）
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
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
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE records
     CHANGE COLUMN group_id org_id VARCHAR(36) DEFAULT '',
     DROP INDEX idx_records_group,
     ADD INDEX idx_records_org (org_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE nlp_task
     CHANGE COLUMN group_id org_id VARCHAR(36) COMMENT '提交时所属组织',
     DROP INDEX idx_nlp_task_group,
     ADD INDEX idx_nlp_task_org (org_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE qc_task
     CHANGE COLUMN group_id org_id VARCHAR(36) COMMENT '提交时所属组织快照，供 worker 重建 RecordFilter',
     DROP INDEX idx_qc_task_group,
     ADD INDEX idx_qc_task_org (org_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks' AND COLUMN_NAME = 'org_id') = 0,
  "ALTER TABLE review_tasks
     CHANGE COLUMN group_id org_id VARCHAR(36) COMMENT '任务所属组织',
     DROP INDEX idx_review_task_group,
     ADD INDEX idx_review_task_org (org_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
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
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log'
      AND INDEX_NAME = 'idx_group_time') = 0,
  "ALTER TABLE operation_log
     ADD INDEX idx_group_time (org_id, create_time),
     ADD INDEX idx_operator_time (operator, create_time)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
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
UPDATE organizations SET status = 'active' WHERE status IN ('pending', 'rejected');

UPDATE users SET status = 'active' WHERE status = 'pending';
UPDATE users SET has_pending_group = 0;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
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
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records' AND COLUMN_NAME = 'text_hash') = 0,
  "ALTER TABLE records
     ADD COLUMN text_hash CHAR(32) NULL COMMENT '21 字段 MD5，见 RecordUtil.textHash；存量行 NULL = 不参与唯一约束'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
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
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND COLUMN_NAME = 'active_key') = 0,
  "ALTER TABLE review_tasks
     ADD COLUMN active_key VARCHAR(36)
       GENERATED ALWAYS AS (IF(is_obsolete = 0, record_id, NULL)) STORED
       COMMENT '活跃行=record_id，作废行=NULL；配合 uk_record_active 约束「一个病历至多一条待复核任务」'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks'
      AND INDEX_NAME = 'uk_record_active') = 0,
  'ALTER TABLE review_tasks ADD UNIQUE KEY uk_record_active (active_key)',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------------
-- ⑨ 批任务取消标记列：取消位从内存态落库（工作线程改为读 DB 判断取消）
-- ---------------------------------------------------------------------------
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task'
      AND COLUMN_NAME = 'cancel_requested') = 0,
  "ALTER TABLE nlp_task ADD COLUMN cancel_requested TINYINT NOT NULL DEFAULT 0
     COMMENT '1=已请求取消；工作线程读本列判断，不依赖内存取消位'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task'
      AND COLUMN_NAME = 'cancel_requested') = 0,
  "ALTER TABLE qc_task ADD COLUMN cancel_requested TINYINT NOT NULL DEFAULT 0
     COMMENT '1=已请求取消；工作线程读本列判断，不依赖内存取消位'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ============================================================================
-- 反向脚本（回滚用）
--
-- 用法：从本文件末尾单独复制执行，或见 docs/archive/ 下的拆分版本。
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
