CREATE DATABASE IF NOT EXISTS tcm_ehr
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE tcm_ehr;

CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(50) UNIQUE NOT NULL,
  password VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT '角色：管理员/用户',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(20) NOT NULL DEFAULT 'active'
    COMMENT 'active/disabled',
  has_pending_group TINYINT NOT NULL DEFAULT 0
    COMMENT '已废弃：组织改为自助创建、取消审核，代码侧不再读写（本列保留以免存量库回滚丢数据）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS organizations (
  id VARCHAR(36) PRIMARY KEY,
  code VARCHAR(50) NOT NULL UNIQUE COMMENT '组织唯一编码，如 NEURO-2026；创建时即校验，防抢占',
  name VARCHAR(100) NOT NULL COMMENT '组织显示名，可重复',
  purpose VARCHAR(500) COMMENT '用途说明（可选）',
  status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active/stopped/archived',
  owner_user_id VARCHAR(36) COMMENT '所有者冗余展示，权威以 organization_members.role=owner',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_org_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织';

CREATE TABLE IF NOT EXISTS organization_members (
  id VARCHAR(36) PRIMARY KEY,
  org_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT 'owner=所有者 / member=成员',
  can_write_dictionary TINYINT NOT NULL DEFAULT 0 COMMENT '词典写授权开关',
  can_write_qc_rules TINYINT NOT NULL DEFAULT 0 COMMENT '质控规则写授权开关',
  is_primary TINYINT NOT NULL DEFAULT 1 COMMENT '主组织标记，预留一人多组织',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_org_user (org_id, user_id),
  INDEX idx_member_user (user_id, is_primary) COMMENT '每请求按 user_id 取主组织，必须有',
  CONSTRAINT fk_member_org FOREIGN KEY (org_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织成员';

CREATE TABLE IF NOT EXISTS records (
  id VARCHAR(36) PRIMARY KEY COMMENT '主键UUID',
  registration_no VARCHAR(50) COMMENT '登记号',
  outpatient_no VARCHAR(50) COMMENT '门诊号',
  gender VARCHAR(10) COMMENT '性别',
  age VARCHAR(20) COMMENT '年龄',
  visit_count INT COMMENT '就诊次数',
  western_diagnosis TEXT COMMENT '西医诊断',
  tcm_diagnosis TEXT COMMENT '中医诊断',
  present_illness TEXT COMMENT '现病史',
  chief_complaint TEXT COMMENT '主诉',
  self_report TEXT COMMENT '自诉',
  inspection TEXT COMMENT '望诊',
  pulse TEXT COMMENT '脉诊',
  tongue TEXT COMMENT '舌诊',
  physical_exam TEXT COMMENT '查体',
  pattern TEXT COMMENT '辨证结论/证候',
  prescription TEXT COMMENT '草药',
  follow_up TEXT COMMENT '随访',
  treatment_effect TEXT COMMENT '治疗效果',
  department VARCHAR(50) COMMENT '开单科室',
  doctor_id VARCHAR(50) COMMENT '医生工号',
  visit_time DATETIME COMMENT '接诊时间',
  structured_data JSON COMMENT '结构化数据（NLP抽取结果）',
  qc_results JSON COMMENT '质控检查结果',
  score INT COMMENT '质控评分（满分100）',
  grade VARCHAR(20) COMMENT '分级：合格/待复核/无效（**只做质控结论**，不再当权限用）',
  org_id VARCHAR(36) DEFAULT '' COMMENT '归属组织；空=无组织，代码层降级为“无数据”（fail-closed）',
  status VARCHAR(20) COMMENT '状态：pending/reviewing/completed/invalid',
  governed TINYINT NOT NULL DEFAULT 0 COMMENT '已清洗标记（清洗+术语归一完成后置1）',
  -- 人工修改标记（性能审查 P1-2#2）：STORED 生成列，值由 structured_data._meta.manuallyEdited 派生，
  -- 表达式与 StructuredDataMeta.isManuallyEdited（Java asBoolean）逐例等价。应用侧只读
  -- （Record.manuallyEdited 的 insert/updateStrategy = NEVER），因此导入 / 单条新增 / 写回结构化数据 /
  -- 清洗归一 / NLP 重解析 / 复核六条写入路径都无需各自维护，列值也不可能与 JSON 分叉。
  manually_edited TINYINT GENERATED ALWAYS AS (CASE WHEN NOT JSON_VALID(structured_data) THEN 0 WHEN LOWER(JSON_UNQUOTE(JSON_EXTRACT(structured_data, '$._meta.manuallyEdited'))) = 'true' THEN 1 WHEN JSON_TYPE(JSON_EXTRACT(structured_data, '$._meta.manuallyEdited')) IN ('INTEGER', 'DOUBLE', 'DECIMAL') AND TRUNCATE(CAST(JSON_EXTRACT(structured_data, '$._meta.manuallyEdited') AS DECIMAL(65, 30)), 0) <> 0 THEN 1 ELSE 0 END) STORED NOT NULL COMMENT '是否人工修改过结构化数据（由 structured_data._meta.manuallyEdited 派生，应用侧只读）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_registration_no (registration_no),
  INDEX idx_status (status),
  INDEX idx_grade (grade),
  INDEX idx_department_visit_time (department, visit_time),
  INDEX idx_records_visit_time (visit_time),
  text_hash CHAR(32) NULL COMMENT '21 字段 MD5；NULL = 不参与唯一约束',
  UNIQUE KEY uk_records_org_text_hash (org_id, text_hash),
  INDEX idx_records_org (org_id),
  -- 25.4：列表/导出/统计普遍是「org_id 等值（数据域）+ visit_time 范围或排序」，
  -- 两个单列索引同时存在时 MySQL 只能选其一，另一维回表过滤；复合索引才两维都走
  INDEX idx_records_org_visit_time (org_id, visit_time),
  -- 40,000 条性能审查（2026-10-07）：列表排序键 (visit_time DESC, id ASC) 的降序复合索引；
  -- (org_id, department) 覆盖 COUNT/科室下拉；(org_id, grade, governed) 覆盖 overview/governance 聚合
  INDEX idx_records_org_vt_id (org_id, visit_time DESC, id ASC),
  INDEX idx_records_org_department (org_id, department),
  INDEX idx_records_org_grade_gov (org_id, grade, governed),
  -- B3 用户可排序（2026-10-08，方案 b）：白名单 score / registration_no 的复合索引，末级 id 保稳定
  INDEX idx_records_org_score_desc_id (org_id, score DESC, id ASC),
  INDEX idx_records_org_regno_id (org_id, registration_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='病历表';

CREATE TABLE IF NOT EXISTS review_tasks (
  id VARCHAR(36) PRIMARY KEY,
  record_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) COMMENT '状态：pending/completed',
  issue_type VARCHAR(50) COMMENT '问题类型（缺失字段/逻辑冲突/评分不达标）',
  score INT COMMENT '当前评分',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  deadline_time DATETIME COMMENT '复核截止时间（创建时间+7个自然日 —— 原写「工作日」有误，2026-10-05 校正；实现按自然日算）',
  reviewed_by VARCHAR(50) COMMENT '复核人用户名',
  completed_time DATETIME COMMENT '复核完成时间',
  is_obsolete TINYINT NOT NULL DEFAULT 0 COMMENT '作废标记：病历重新评分后不再是待复核则置1（查询/统计/看板统一过滤 is_obsolete=0）',
  org_id VARCHAR(36) COMMENT '任务所属组织；写入时打标，避免查询期 JOIN（QueryWrapper 不便 JOIN，而 listTasks 是高频路径）',
  INDEX idx_record_id (record_id),
  INDEX idx_obsolete (is_obsolete),
  active_key VARCHAR(36) GENERATED ALWAYS AS
    (IF(is_obsolete = 0, record_id, NULL)) STORED
    COMMENT '活跃行=record_id，作废行=NULL；配合 uk_record_active 约束「一个病历至多一条待复核任务」',
  UNIQUE KEY uk_record_active (active_key),
  INDEX idx_review_task_org (org_id),
  CONSTRAINT fk_review_record FOREIGN KEY (record_id) REFERENCES records(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复核任务表';

CREATE TABLE IF NOT EXISTS operation_log (
  id VARCHAR(36) PRIMARY KEY COMMENT '主键UUID',
  log_time DATETIME NOT NULL COMMENT '操作时间',
  operator VARCHAR(50) COMMENT '操作人用户名',
  role VARCHAR(20) COMMENT '操作人角色：管理员/用户',
  action VARCHAR(50) COMMENT '操作类型：数据清洗/数据集导出/词典导入/词典回滚/人工复核/批量重算',
  object_type VARCHAR(32) NULL COMMENT '对象类型（record/dictionary/…）；无单一对象的批量操作留 NULL',
  object_id VARCHAR(64) NULL COMMENT '对象 ID（如病历 ID）；与 object_type 成对使用',
  target VARCHAR(255) COMMENT '操作对象：筛选范围/文件名/词典类型/病历ID',
  detail TEXT COMMENT '操作明细',
  org_id VARCHAR(36) COMMENT '操作时所属组织，留痕用（按它做三档可见性）',
  INDEX idx_log_time (log_time),
  -- idx_operator_time 覆盖 AI 助手的 listRecentByOperator
  -- （WHERE operator=? ORDER BY log_time DESC），故单列 idx_operator 已被取代
  INDEX idx_action (action),
  INDEX idx_log_object (org_id, object_type, object_id, log_time),
  INDEX idx_group_time (org_id, log_time),
  INDEX idx_operator_time (operator, log_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';

CREATE TABLE IF NOT EXISTS nlp_task (
  id VARCHAR(36) PRIMARY KEY COMMENT '任务ID(UUID)',
  status VARCHAR(20) NOT NULL COMMENT '状态：QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED',
  total INT NOT NULL DEFAULT 0 COMMENT '计划处理条数',
  done INT NOT NULL DEFAULT 0 COMMENT '已处理条数',
  success INT NOT NULL DEFAULT 0 COMMENT '成功条数',
  failed INT NOT NULL DEFAULT 0 COMMENT '失败条数',
  current_label VARCHAR(255) COMMENT '当前处理的病历标识',
  filters_json TEXT COMMENT '筛选范围(JSON)',
  request_key VARCHAR(64) NULL COMMENT '幂等键（批次5；同组织内唯一，NULL 不参与唯一性）',
  created_by VARCHAR(50) COMMENT '提交人用户名',
  failure_list JSON COMMENT '失败清单(仅存前500条)',
  failure_truncated TINYINT NOT NULL DEFAULT 0 COMMENT '失败清单是否被截断',
  org_id VARCHAR(36) COMMENT '提交时所属组织（组织隔离查询用）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at DATETIME COMMENT '开始时间',
  finished_at DATETIME COMMENT '结束时间',
  INDEX idx_status (status),
  INDEX idx_create_time (create_time),
  -- 取消位落库：工作线程读本列判断，不再依赖内存 Set（重启即失）
  cancel_requested TINYINT NOT NULL DEFAULT 0 COMMENT '1=已请求取消',
  UNIQUE KEY uk_nlp_task_req (org_id, request_key),
  INDEX idx_nlp_task_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NLP批量解析任务表';

-- 批次 16 #3：NLP 批任务的明细（ID 集合落库，支持重启后从断点续跑）
-- 与 nlp_task 同库；一行一条病历，含 seq 游标与处理状态。
CREATE TABLE IF NOT EXISTS nlp_task_items (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  task_id     VARCHAR(64)  NOT NULL                COMMENT '所属任务（nlp_task.id）',
  record_id   VARCHAR(64)  NOT NULL                COMMENT '待处理病历（records.id）',
  seq         INT          NOT NULL                COMMENT '提交顺序（0 起），进度游标按它推进',
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE / FAILED',
  reason      VARCHAR(255)     NULL                COMMENT '失败原因（仅在 FAILED 时写）',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入队时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '状态变更时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_record (task_id, record_id),
  KEY idx_task_status_seq (task_id, status, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量解析任务的记录 ID 集合与逐条状态（批次 16 工作项 3）';


CREATE TABLE IF NOT EXISTS qc_task (
  id VARCHAR(36) PRIMARY KEY COMMENT '任务ID(UUID)',
  status VARCHAR(20) NOT NULL COMMENT '状态：QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED',
  total INT NOT NULL DEFAULT 0 COMMENT '计划处理条数',
  done INT NOT NULL DEFAULT 0 COMMENT '已处理条数',
  success INT NOT NULL DEFAULT 0 COMMENT '成功条数',
  failed INT NOT NULL DEFAULT 0 COMMENT '失败条数',
  qualified INT NOT NULL DEFAULT 0 COMMENT '合格数',
  pending_review INT NOT NULL DEFAULT 0 COMMENT '待复核数',
  invalid INT NOT NULL DEFAULT 0 COMMENT '无效数',
  current_label VARCHAR(255) COMMENT '当前处理的病历标识',
  filters_json TEXT COMMENT '筛选范围(JSON)',
  created_by VARCHAR(50) COMMENT '提交人用户名',
  role VARCHAR(20) COMMENT '提交时角色快照，仅用于审计日志回填',
  org_id VARCHAR(36) COMMENT '提交时所属组织快照，供 worker 重建 RecordFilter（数据域）',
  failure_list JSON COMMENT '失败清单(仅存前500条)',
  failure_truncated TINYINT NOT NULL DEFAULT 0 COMMENT '失败清单是否被截断',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at DATETIME COMMENT '开始时间',
  finished_at DATETIME COMMENT '结束时间',
  INDEX idx_status (status),
  INDEX idx_create_time (create_time),
  cancel_requested TINYINT NOT NULL DEFAULT 0 COMMENT '1=已请求取消',
  INDEX idx_qc_task_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质控批量重算任务表';

CREATE TABLE IF NOT EXISTS dictionary_terms (
  id VARCHAR(36) PRIMARY KEY,
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层（不用 NULL，见注 1）",
  type VARCHAR(20) NOT NULL COMMENT 'disease/pattern/symptom/herb/formula',
  standard_term VARCHAR(200) NOT NULL,
  aliases JSON,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_org_type_term (org_id, type, standard_term),
  INDEX idx_org_type (org_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='术语词典词条';

CREATE TABLE IF NOT EXISTS dict_proposal (
  id VARCHAR(36) PRIMARY KEY,
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层",
  type VARCHAR(20) NOT NULL,
  submit_user_id VARCHAR(36) NOT NULL COMMENT '提交人',
  status VARCHAR(16) NOT NULL COMMENT 'pending/approved/rejected',
  audit_user_id VARCHAR(36) COMMENT '审核人（组长）',
  audit_comment VARCHAR(500),
  create_time DATETIME NOT NULL,
  audit_time DATETIME,
  purge_after DATETIME COMMENT '仅 rejected：+7天后惰性清理快照；主记录永久保留',
  INDEX idx_pending (org_id, type, status),
  INDEX idx_purge (status, purge_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='词典基线更新提案';

CREATE TABLE IF NOT EXISTS dict_proposal_term (
  id VARCHAR(36) PRIMARY KEY,
  proposal_id VARCHAR(36) NOT NULL,
  standard_term VARCHAR(200) NOT NULL,
  aliases JSON,
  INDEX idx_proposal (proposal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提案术语快照（提交时的完整目标基线）';

CREATE TABLE IF NOT EXISTS dict_archive_version (
  id VARCHAR(36) PRIMARY KEY,
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层，与组织层独立计数",
  type VARCHAR(20) NOT NULL,
  version_no INT NOT NULL COMMENT '组内递增版本号',
  proposal_id VARCHAR(36) COMMENT '来源提案；管理员直写导入时为 NULL',
  merge_time DATETIME NOT NULL,
  merge_user_id VARCHAR(36) NOT NULL,
  comment VARCHAR(300),
  UNIQUE KEY uk_org_type_no (org_id, type, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='基线归档版本元信息（永久保留）';

CREATE TABLE IF NOT EXISTS dict_archive_term (
  id VARCHAR(36) PRIMARY KEY,
  version_id VARCHAR(36) NOT NULL,
  standard_term VARCHAR(200) NOT NULL,
  aliases JSON,
  INDEX idx_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='基线归档术语快照（每组每 type 仅留最近 5 份）';

CREATE TABLE IF NOT EXISTS dictionary_versions (
  org_id VARCHAR(36) NOT NULL DEFAULT '' COMMENT "''=系统基础层",
  type VARCHAR(20) NOT NULL COMMENT 'disease/pattern/symptom/herb/formula',
  version VARCHAR(32) NOT NULL COMMENT 'DB 侧内容版本：该 (org,type) 词条内容的 MD5 前 12 位',
  indexed_version VARCHAR(32) NULL COMMENT 'ES 侧已灌入的版本；NULL 或 <> version 即「待重建」',
  indexed_at DATETIME NULL COMMENT '最近一次成功灌入 ES 的时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (org_id, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='词典内容版本与 ES 索引状态（按组织）';

CREATE TABLE IF NOT EXISTS qc_rules (
  org_id VARCHAR(36) PRIMARY KEY,
  rules_json LONGTEXT NOT NULL,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质控规则（每组织一份）';

CREATE TABLE IF NOT EXISTS user_llm_config (
  user_id VARCHAR(36) PRIMARY KEY,
  enabled TINYINT NOT NULL DEFAULT 0,
  provider VARCHAR(30),
  base_url VARCHAR(300),
  model VARCHAR(100),
  temperature DOUBLE,
  timeout INT,
  api_key VARCHAR(1000) COMMENT 'AES-256-GCM 加密后的密文（Base64）',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户 LLM 配置';

INSERT IGNORE INTO users (id, username, password, role) VALUES
('admin-0001', 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员'),
('audit-0001', 'auditor', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '用户');
