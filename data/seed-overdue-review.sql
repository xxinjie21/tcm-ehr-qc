-- 演示数据补充：把一条「待复核」任务的截止时间改到过去，
-- 用于验证复核工作台的「超时」高亮能被触发（UX-48）。
--
-- 背景：review_tasks 由批量评分（POST /api/qc/score/batch）自动创建，
-- 截止时间统一为「创建时间 + 7 个工作日」，正常情况下永远不会超时，
-- 导致超时相关的视觉与筛选逻辑在演示时看不到效果。
--
-- 用法（可重复执行，幂等）：
--   docker exec -i windows-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 < data/seed-overdue-review.sql

USE tcm_ehr;

-- 取截止时间最早的一条待复核任务，改为 3 天前到期。
-- 再次执行时会命中「已改为过去」的那条（它的 deadline 仍是最早的），因此幂等。
UPDATE review_tasks
SET deadline_time = DATE_SUB(NOW(), INTERVAL 3 DAY)
WHERE status = 'pending'
  AND is_obsolete = 0
ORDER BY deadline_time ASC
LIMIT 1;

SELECT id, record_id, status, create_time, deadline_time
FROM review_tasks
WHERE deadline_time < NOW()
ORDER BY deadline_time ASC;
