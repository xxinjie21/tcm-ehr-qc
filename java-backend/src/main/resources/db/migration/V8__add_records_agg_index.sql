-- V8：为 records 补一条「看板聚合覆盖索引」，消除两个大聚合的全表扫。
--
-- 背景（性能审查 P0-2 / P1-5 的实测根因）：
--   GET /stats/extra 冷路径由两条聚合主导（真实库 34182 行实测）：
--     1) 趋势  GROUP BY DATE_FORMAT(visit_time,'%Y-%m')  → 2306ms
--     2) 科室  GROUP BY department                        → 2287ms
--   二者 EXPLAIN ANALYZE 均为 `Table scan on records`。根因不是行数（3.4 万行不多），
--   而是 records 行极宽（avg_row_length≈12KB：structured_data / qc_results 两个 TEXT），
--   整表 ≈290MB；聚合只需 visit_time / department / grade 三列，却被迫读整行。
--
-- 做法：一条窄的**覆盖索引**同时覆盖两条聚合用到的列：
--     (org_id, visit_time, department, grade)
--   - 趋势用 (org_id, visit_time, grade)，科室用 (org_id, department, grade)；
--   - 两列都在这条索引里 ⇒ 聚合走 `Covering index scan`，不再回表读 290MB。
--   - 无 org 前缀也可用：管理员「看全部」（不带 org_id）时退化为整条索引的覆盖扫描，
--     索引条目仅 ~60B × 3.4 万 ≈ 2MB，代价可忽略 —— 故**不必**再建 admin 变体。
--
-- 实测（34182 行，加本条索引后）：
--   admin 趋势 2306→50ms、admin 科室 2287→16ms；
--   org 趋势 17ms、org 科室 16ms；
--   org 趋势 + 就诊区间过滤仍走 idx_records_org_vt_id 范围扫（0.23ms）。
--
-- 代价：新增 1 条二级索引，略微抬高导入/质控回写的写放大；索引本身 ≈2MB。
--   score 分布聚合已足够快（96ms），未纳入本条。
--
-- 幂等：全新库由 database-init.sql 直接建成；MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，
-- 沿用 V2/V4/V5/V7 的 information_schema 守卫写法。
--
-- 回退：Flyway 已执行版本不可改内容，需另写更高版本 DROP INDEX。

SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
      AND INDEX_NAME = 'idx_records_org_agg');
SET @s := IF(@c = 0,
    'ALTER TABLE records ADD INDEX idx_records_org_agg (org_id, visit_time, department, grade)',
    'DO 0');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
