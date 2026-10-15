-- V4：让 records 的默认序（visit_time DESC, id ASC）走索引，消除管理员「看全部」时的全表排序。
--
-- 背景：列表默认序是 visit_time DESC, id ASC（RecordFilter 默认分支）。
-- 原索引 idx_records_visit_time(visit_time) 是单列；InnoDB 二级索引隐含主键，
-- 故它物理上等价于 (visit_time, id) —— 正扫两列皆升序、反扫两列皆降序，
-- 永远凑不出「一降一升」，这个默认序无法由索引满足。
-- 管理员（viewAllOrgs，不带 org_id 条件）又用不上 idx_records_org_vt_id 的 org 前缀，
-- 只能全表读整行再排序：EXPLAIN 为 Table scan + Sort cost=14209，
-- 实测 POST /api/records/search 2.8~3.1s / 35352 行；
-- 普通成员因带 org_id 等值条件走 idx_records_org_vt_id，仅 18~62ms。
--
-- 做法：把单列索引替换成同序复合索引。新索引是原索引的超集
-- （visit_time 的等值/范围查仍走最左前缀），二级索引总数保持 7 条不变，
-- 不增加批量回写的写放大（见 database-init.sql 的「索引治理」注释）。
--
-- 幂等：全新库由 database-init.sql 直接建成 idx_records_vt_desc_id，故本脚本需判存在性。
-- MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，沿用 V2/V3 的 information_schema 守卫写法。
--
-- 回退：Flyway 已执行的版本不能改内容、也不能删脚本。要回退请另写 V5，
-- 把 idx_records_vt_desc_id 换回 idx_records_visit_time(visit_time)。

-- 1. 先建新索引（若已存在则空操作）。
--    顺序上先建后删 —— 中途不会出现「两个索引都不在」的窗口。
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_vt_desc_id');
SET @s := IF(@c = 0,
    'ALTER TABLE records ADD INDEX idx_records_vt_desc_id (visit_time DESC, id ASC)',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 再删被它取代的单列索引（若仍在）
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_visit_time');
SET @s := IF(@c > 0,
    'ALTER TABLE records DROP INDEX idx_records_visit_time',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
