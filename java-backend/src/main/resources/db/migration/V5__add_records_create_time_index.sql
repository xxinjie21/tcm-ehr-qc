-- V5：为 records 的「导入时间」范围筛选补索引。
--
-- 背景：RangeFilter 新增「导入时间」维度（按 records.create_time 过滤，与「就诊时间」
-- 即 visit_time 是两个独立维度，可同时生效取交集）。此前 create_time 上没有任何索引，
-- 筛「今天导入的病历」只能全表扫 —— 35k 行虽可接受，但这是列表页的常规操作，
-- 且筛选后还要按默认序（visit_time DESC, id ASC）排序，全表读整行 + 排序代价明显。
--
-- 做法：org_id 等值（数据域）+ create_time 范围 + id 稳定序。注意它**不能**满足默认序
-- （默认序按 visit_time），故它只服务 WHERE，ORDER BY 仍走 idx_records_org_vt_id / 全表排序。
-- 之所以仍加：数据域收窄后 create_time 的范围扫能先把候选集降到「今天导入的那几十上百条」，
-- 再排序，代价远低于全表。
--
-- 幂等：全新库由 database-init.sql 直接建成 idx_records_org_ct_id，故本脚本需判存在性。
-- MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，沿用 V2/V4 的 information_schema 守卫写法。
--
-- 回退：Flyway 已执行的版本不能改内容、也不能删脚本。要回退请另写 V6，
-- DROP INDEX idx_records_org_ct_id。

-- 建索引（若已存在则空操作）
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_org_ct_id');
SET @s := IF(@c = 0,
    'ALTER TABLE records ADD INDEX idx_records_org_ct_id (org_id, create_time DESC, id ASC)',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
