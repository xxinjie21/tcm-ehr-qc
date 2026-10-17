-- V7：为 records.status 补索引。
--
-- 背景（性能审查 P0-3）：SearchDTO.status 谓词在 records 上没有任何索引。
-- database-init.sql 的「索引治理（2026-10-09）」注释曾误称 idx_status 被
-- 「org 前缀复合索引」覆盖，但实际 8 个二级索引无一含 status —— 于是带 status 的
-- 搜索/清洗/导出退化成全表扫（EXPLAIN 实测：Table scan on records）。
--
-- 做法：org 前缀（数据域）+ 无 org（管理员「看全部」）各一条，末级 id 保持稳定序。
-- 排序键仍是 visit_time DESC，故这两条只服务 WHERE；不为排序而建。
--
-- 代价：新增 2 条二级索引会略微抬高导入/清洗/质控回写的写放大。可接受的理由是
-- status 是唯一「零索引覆盖」的筛选列；若日后确认 status 过滤不再使用，可另写更高
-- 版本 DROP 掉。
--
-- 幂等：全新库由 database-init.sql 直接建成；MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，
-- 沿用 V2/V4/V5 的 information_schema 守卫写法。
--
-- 回退：Flyway 已执行版本不可改内容，需另写更高版本 DROP INDEX。

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_org_status_vt');
SET @s := IF(@c = 0,
    'ALTER TABLE records ADD INDEX idx_records_org_status_vt (org_id, status, visit_time DESC, id ASC)',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_status_vt');
SET @s := IF(@c = 0,
    'ALTER TABLE records ADD INDEX idx_records_status_vt (status, visit_time DESC, id ASC)',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
