-- V2：补齐本批次新增的列（对本批次之前就已存在的库做升级）。
-- 全新的库由 database-init.sql 建好、已含这些列，故这里对每列先查 information_schema，
-- 已存在则空操作 —— MySQL 8 不支持 ADD COLUMN IF NOT EXISTS。

-- nlp_task.mode
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task' AND COLUMN_NAME = 'mode');
SET @s := IF(@c = 0,
    'ALTER TABLE nlp_task ADD COLUMN mode VARCHAR(20) NULL COMMENT ''EXTRACT / NORMALIZE_ONLY''',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- nlp_task.failed_ids
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nlp_task' AND COLUMN_NAME = 'failed_ids');
SET @s := IF(@c = 0,
    'ALTER TABLE nlp_task ADD COLUMN failed_ids JSON NULL COMMENT ''完整失败记录ID集合（仅重试失败用）''',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- qc_task.failed_ids
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qc_task' AND COLUMN_NAME = 'failed_ids');
SET @s := IF(@c = 0,
    'ALTER TABLE qc_task ADD COLUMN failed_ids JSON NULL COMMENT ''完整失败记录ID集合（仅重试失败用）''',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
