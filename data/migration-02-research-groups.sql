-- ============================================================================
-- 迁移 02：阶段2 R1 · 多课题组数据模型（建 2 表 + 改 4 表 + 存量迁移）
--
-- 为什么需要单独脚本：database-init.sql 不被 Spring 自动执行（application.yml 无
-- spring.sql.init），且只有 CREATE TABLE IF NOT EXISTS —— 对**存量库**的改列 / 删列 /
-- 加索引都不会生效。
--
-- **全部幂等**：可重复执行。执行顺序不可颠倒（先建表/加列 → 再填数据 → 最后加约束）。
-- 本次不加 NOT NULL：即使本脚本没跑完，代码对空 group_id 也降级为「无数据」，
-- 不会出现「迁移未完成 = 全库可见」这种危险中间态。
--
-- 配套的新表（research_groups / group_members）与 database-init.sql 中的建表语句
-- 一致；本脚本只负责存量库的新增 / 数据回填。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 新表（幂等：IF NOT EXISTS）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS research_groups (
  id VARCHAR(36) PRIMARY KEY,
  code VARCHAR(50) NOT NULL UNIQUE COMMENT '组唯一编码，如 NEURO-2026；申请时即校验，防抢占',
  name VARCHAR(100) NOT NULL COMMENT '组显示名，可重复',
  purpose VARCHAR(500) COMMENT '用途说明（可选），供管理员审批判断',
  status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/active/rejected/stopped',
  applied_by VARCHAR(36) COMMENT '申请人（= 首任组长）',
  reviewed_by VARCHAR(36),
  reviewed_at DATETIME,
  reject_reason VARCHAR(300) COMMENT '拒绝理由，申请人可见',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_group_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课题组';

CREATE TABLE IF NOT EXISTS group_members (
  id VARCHAR(36) PRIMARY KEY,
  group_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT 'owner=组长 / member=组员',
  is_primary TINYINT NOT NULL DEFAULT 1 COMMENT '主组标记，预留一人多组',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_group_user (group_id, user_id),
  INDEX idx_member_user (user_id, is_primary) COMMENT '每请求按 user_id 取主组，必须有',
  CONSTRAINT fk_member_group FOREIGN KEY (group_id) REFERENCES research_groups(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课题组成员';

-- ---------------------------------------------------------------------------
-- 1. users 加 2 列（幂等：先判存在再改）
-- ---------------------------------------------------------------------------
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'status') = 0,
  "ALTER TABLE users
     ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'pending'
       COMMENT 'pending=待分配池或审批中 / active=有生效组 / disabled=停用',
     ADD COLUMN has_pending_group TINYINT NOT NULL DEFAULT 0
       COMMENT '1=已申请建组待审批，从待分配池隐藏'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2. records / operation_log / nlp_task / review_tasks 各加 group_id（幂等）
-- ---------------------------------------------------------------------------
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records' AND COLUMN_NAME = 'group_id') = 0,
  "ALTER TABLE records
     ADD COLUMN group_id VARCHAR(36) DEFAULT '' COMMENT '归属课题组',
     ADD INDEX idx_records_group (group_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' AND COLUMN_NAME = 'group_id') = 0,
  "ALTER TABLE operation_log ADD COLUMN group_id VARCHAR(36) COMMENT '操作时所属组，留痕用'",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task' AND COLUMN_NAME = 'group_id') = 0,
  "ALTER TABLE nlp_task
     ADD COLUMN group_id VARCHAR(36) COMMENT '提交时所属组',
     ADD INDEX idx_nlp_task_group (group_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_tasks' AND COLUMN_NAME = 'group_id') = 0,
  "ALTER TABLE review_tasks
     ADD COLUMN group_id VARCHAR(36) COMMENT '任务所属组',
     ADD INDEX idx_review_task_group (group_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.6 qc_task 加 group_id（幂等）。任务表在 database-init.sql 里建表时没有该列，
--    是 QcBatchServiceImpl 提交快照列（R3 起 RecordFilter 取 group_id 而非 role），
--    漏加会导致批量重算 INSERT 直接 SQLSyntaxErrorException。新装库由
--    database-init.sql 的 DDL 列覆盖，这里是存量库升级路径。
SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task' AND COLUMN_NAME = 'group_id') = 0,
  "ALTER TABLE qc_task
     ADD COLUMN group_id VARCHAR(36) COMMENT '提交时所属组快照，供 worker 重建 RecordFilter',
     ADD INDEX idx_qc_task_group (group_id)",
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3. 存量数据归入兜底组（全部幂等）
-- ---------------------------------------------------------------------------

-- 3.1 兜底组：已存在则跳过
INSERT INTO research_groups (id, code, name, status, purpose)
SELECT 'grp-default-2026', 'DEFAULT-2026', '默认课题组', 'active', '存量数据归集'
WHERE NOT EXISTS (SELECT 1 FROM research_groups WHERE code = 'DEFAULT-2026');

-- 3.2 存量病历 / 任务归入兜底组（group_id 为空才写，重复执行不覆盖已有归属）
UPDATE records      SET group_id = 'grp-default-2026' WHERE group_id IS NULL OR group_id = '';
UPDATE review_tasks SET group_id = 'grp-default-2026' WHERE group_id IS NULL OR group_id = '';
UPDATE nlp_task     SET group_id = 'grp-default-2026' WHERE group_id IS NULL OR group_id = '';

-- 3.3 账号状态：admin / auditor 视为「有生效组」
UPDATE users SET status = 'active' WHERE username IN ('admin', 'auditor');

-- 3.4 兜底组组长 = auditor（兜底组必须有 owner，否则无人能管成员）
INSERT INTO group_members (id, group_id, user_id, role, is_primary)
SELECT 'gm-audit-0001', 'grp-default-2026', u.id, 'owner', 1
FROM users u
WHERE u.username = 'auditor'
  AND NOT EXISTS (SELECT 1 FROM group_members gm WHERE gm.user_id = u.id);

-- 3.5 admin 以普通 member 入组，使其当前能看到存量数据。
--     ⚠️ 刻意不给 owner：owner 身份会牵连「auth.admin-can-view-data」开关
--     （§4.4 置 false 时管理员连本组也看不到），两种身份分开更可控。
--     将来改为「管理员不能看数据」时只把该配置置 false，无需改数据。
INSERT INTO group_members (id, group_id, user_id, role, is_primary)
SELECT 'gm-admin-0001', 'grp-default-2026', u.id, 'member', 1
FROM users u
WHERE u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM group_members gm WHERE gm.user_id = u.id);
