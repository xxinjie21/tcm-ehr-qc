-- B3 用户可排序（方案 b）· 排序复合索引（2026-10-08）
--
-- 白名单三列（见 RecordFilter.SORT_WHITELIST）与索引对照：
--   score            → idx_records_org_score_desc_id (org_id, score DESC, id ASC)
--                      列 desc = 正扫；列 asc = 反扫（id 随之 DESC，全序稳定）
--   visit_time       → 复用已有 idx_records_org_vt_id (org_id, visit_time DESC, id ASC)（零新增）
--   registration_no  → idx_records_org_regno_id (org_id, registration_no, id) 升序
--                      列 asc = 正扫（id ASC）；列 desc = 反扫（id DESC）
-- 次级键 id 每条都必须带上：缺失会在分页页边界产生重复行/漏行（性能审查 P2-4）。
--
-- 幂等：每个索引创建前查 information_schema，已存在则跳过，可重复执行。
-- 兼容：MySQL 8/9 全系（与 2026-10-07 迁移同一口径，不用 LOCK 子句 —— 9.x 已移除）。
-- 回滚：ALTER TABLE records DROP INDEX idx_records_org_score_desc_id;
--       ALTER TABLE records DROP INDEX idx_records_org_regno_id;

SET @schema = DATABASE();

SET @sql = NULL;
SELECT IF(COUNT(*) = 0,
  'ALTER TABLE records ADD INDEX idx_records_org_score_desc_id (org_id, score DESC, id ASC)',
  NULL)
  INTO @sql
  FROM information_schema.statistics
 WHERE table_schema = @schema AND table_name = 'records' AND index_name = 'idx_records_org_score_desc_id';
SET @sql = IFNULL(@sql, 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = NULL;
SELECT IF(COUNT(*) = 0,
  'ALTER TABLE records ADD INDEX idx_records_org_regno_id (org_id, registration_no, id)',
  NULL)
  INTO @sql
  FROM information_schema.statistics
 WHERE table_schema = @schema AND table_name = 'records' AND index_name = 'idx_records_org_regno_id';
SET @sql = IFNULL(@sql, 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;