-- ============================================================
-- migration-05：批量解析任务的「记录 ID 集合」落库（批次 16 工作项 3）
-- ============================================================
-- 背景：批量解析提交时把待处理病历 ID 存在内存里（NlpBatchServiceImpl 的队列 / Set），
--       进程重启即丢 —— 表现为「任务显示排队中/进行中，但重启后再也不会推进」，
--       而且取消与重试都拿不到那批 ID。落一张明细表即可让 ID 集合与进度一起存活。
--
-- 设计要点：
--   1) 一行一条病历（不是把 JSON 塞进一列）：进度游标要按 seq 走，失败要能按行记原因；
--   2) (task_id, record_id) 唯一键 —— 重复提交/重放同一个任务不会产生重复行（幂等）；
--   3) 保留 seq 以维持提交时的顺序（否则进度回显与「下一个待处理」会跳来跳去）；
--   4) 不加外键：与项目其它表一致（删除病历不应被这张表挡住），由应用侧保证一致性。
--
-- 幂等：全部 IF NOT EXISTS，可重复执行。
-- 回滚：DROP TABLE IF EXISTS nlp_task_items;
-- ============================================================

CREATE TABLE IF NOT EXISTS `nlp_task_items` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `task_id`     VARCHAR(64)  NOT NULL                COMMENT '所属任务（nlp_task.id）',
  `record_id`   VARCHAR(64)  NOT NULL                COMMENT '待处理病历（records.id）',
  `seq`         INT          NOT NULL                COMMENT '提交顺序（0 起），进度游标按它推进',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE / FAILED',
  `reason`      VARCHAR(255)     NULL                COMMENT '失败原因（仅在 FAILED 时写）',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入队时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '状态变更时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_record` (`task_id`, `record_id`),
  KEY `idx_task_status_seq` (`task_id`, `status`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量解析任务的记录 ID 集合与逐条状态（批次 16 工作项 3）';
