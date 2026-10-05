-- 批次 5：批量任务幂等键（Stripe 范式）
--
-- 背景：批量解析/质控现在**按筛选范围**提交（NlpBatchDTO 只有 filters + limit）。
-- 用户点两下「开始」、或网络重试，就会产生两条几乎一样的任务 —— 同一批病历被解析两遍，
-- 既浪费算力，也会让「最近任务」列表出现难以分辨的重复项。
--
-- 做法（与项目既有范式一致 —— 病历表用 uk_records_org_text_hash 做哈希幂等）：
--   1. 加 request_key（客户端生成的幂等键，64 字符内）；
--   2. 加 UNIQUE (org_id, request_key)：同一组织内同一个键只能有一条任务；
--   3. MySQL 的唯一索引允许多个 NULL ⇒ **历史数据与"未带键的旧客户端"不受影响**，
--      只有显式带键的提交才享受幂等。
--
-- 服务侧约定：插入撞唯一键 ⇒ 查出既有任务并**原样返回**（不新建、不重跑）。
--
-- 回滚（如确需）：
--   ALTER TABLE nlp_task DROP INDEX uk_nlp_task_req, DROP COLUMN request_key;
--   ALTER TABLE qc_task  DROP INDEX uk_qc_task_req,  DROP COLUMN request_key;

ALTER TABLE nlp_task
  ADD COLUMN request_key VARCHAR(64) NULL COMMENT '幂等键（客户端生成；同组织内唯一）' AFTER filters_json,
  ADD UNIQUE KEY uk_nlp_task_req (org_id, request_key);

ALTER TABLE qc_task
  ADD COLUMN request_key VARCHAR(64) NULL COMMENT '幂等键（客户端生成；同组织内唯一）' AFTER filters_json,
  ADD UNIQUE KEY uk_qc_task_req (org_id, request_key);
