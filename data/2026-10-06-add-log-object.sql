-- 批次14 · 对标 D3：给审计日志加「对象标识」，让对象级活动/历史入口成为可能
--
-- 背景：operation_log 原本只有 target(文本) / detail(文本)，没有对象身份。
-- 实测全库 0 条日志提到任何病历 ID —— 也就是说「这条病历被谁改过」在库里根本查不出来，
-- 只能靠人去翻「病历修改」这类操作再肉眼比对 detail。这就是文档里 D3 标「待补」的原因。
--
-- 做法：加 object_type + object_id 两列（可空），并在 (org_id, object_type, object_id, log_time)
-- 上建索引。**可空是有意的**：批量重算/数据清洗这类操作没有单一对象，留 NULL 是正确的表达，
-- 不应为了"整齐"给它们编一个假对象。
--
-- 回滚（如确需）：
--   ALTER TABLE operation_log DROP INDEX idx_log_object, DROP COLUMN object_type, DROP COLUMN object_id;

ALTER TABLE operation_log
  ADD COLUMN object_type VARCHAR(32) NULL
    COMMENT '对象类型（record/dictionary/...）；无单一对象的批量操作留 NULL' AFTER action,
  ADD COLUMN object_id VARCHAR(64) NULL
    COMMENT '对象 ID（如病历 ID）；与 object_type 成对使用' AFTER object_type,
  ADD INDEX idx_log_object (org_id, object_type, object_id, log_time);
