-- 中医电子病历质控与标准化系统 - 数据库初始化脚本
-- 字符集：utf8mb4（支持4字节生僻字，如"㿠"U+3FE0）

CREATE DATABASE IF NOT EXISTS tcm_ehr
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE tcm_ehr;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(50) UNIQUE NOT NULL,
  password VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT '角色：管理员/用户',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  -- 多课题组（阶段2）：账号状态与「是否已提交建组申请」
  status VARCHAR(20) NOT NULL DEFAULT 'pending'
    COMMENT 'pending=待分配池或审批中 / active=有生效组 / disabled=停用',
  has_pending_group TINYINT NOT NULL DEFAULT 0
    COMMENT '1=已申请建组待审批，从待分配池隐藏'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ===== 多课题组：2 张新表（阶段2 R1）=====
-- 组内角色（owner/member）刻意不塞进 users.role：两者作用域不同
-- （users.role 是系统级，group_members.role 是组内级），混在一起就会出现
-- 「是某组组长」这种无法在 users 表上表达的状态。
CREATE TABLE IF NOT EXISTS research_groups (
  id VARCHAR(36) PRIMARY KEY,
  code VARCHAR(50) NOT NULL UNIQUE COMMENT '组唯一编码，如 NEURO-2026；申请时即校验，防抢占',
  name VARCHAR(100) NOT NULL COMMENT '组显示名，可重复',
  purpose VARCHAR(500) COMMENT '用途说明（可选），供管理员审批判断',
  status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/active/rejected/stopped',
  applied_by VARCHAR(36) COMMENT '申请人（= 首任组长）',
  reviewed_by VARCHAR(36),
  reviewed_at DATETIME,
  reject_reason VARCHAR(300) COMMENT '拒绝理由，申请人可见',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_group_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课题组';

-- ⚠️ idx_member_user 覆盖 (user_id, is_primary) 的最左前缀：JwtInterceptor 每请求按
-- user_id 解析主组，没有这个索引就是每请求一次全表扫。
CREATE TABLE IF NOT EXISTS group_members (
  id VARCHAR(36) PRIMARY KEY,
  group_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT 'owner=组长 / member=组员',
  is_primary TINYINT NOT NULL DEFAULT 1 COMMENT '主组标记，预留一人多组',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_group_user (group_id, user_id),
  INDEX idx_member_user (user_id, is_primary) COMMENT '每请求按 user_id 取主组，必须有',
  CONSTRAINT fk_member_group FOREIGN KEY (group_id) REFERENCES research_groups(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课题组成员';

-- 2. 病历表（21个原始字段 + NLP/质控结果）
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
  group_id VARCHAR(36) DEFAULT '' COMMENT '归属课题组；空=无组，代码层降级为“无数据”（fail-closed）',
  status VARCHAR(20) COMMENT '状态：pending/reviewing/completed/invalid',
  governed TINYINT NOT NULL DEFAULT 0 COMMENT '已清洗标记（清洗+术语归一完成后置1）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_registration_no (registration_no),
  INDEX idx_status (status),
  INDEX idx_grade (grade),
  INDEX idx_department_visit_time (department, visit_time),
  INDEX idx_records_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='病历表';

-- 3. 复核任务表
CREATE TABLE IF NOT EXISTS review_tasks (
  id VARCHAR(36) PRIMARY KEY,
  record_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) COMMENT '状态：pending/completed',
  issue_type VARCHAR(50) COMMENT '问题类型（缺失字段/逻辑冲突/评分不达标）',
  score INT COMMENT '当前评分',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  deadline_time DATETIME COMMENT '复核截止时间（创建时间+7个工作日）',
  reviewed_by VARCHAR(50) COMMENT '复核人用户名',
  completed_time DATETIME COMMENT '复核完成时间',
  is_obsolete TINYINT NOT NULL DEFAULT 0 COMMENT '作废标记：病历重新评分后不再是待复核则置1（查询/统计/看板统一过滤 is_obsolete=0）',
  group_id VARCHAR(36) COMMENT '任务所属组；写入时打标，避免查询期 JOIN（QueryWrapper 不便 JOIN，而 listTasks 是高频路径）',
  INDEX idx_record_id (record_id),
  INDEX idx_obsolete (is_obsolete),
  INDEX idx_review_task_group (group_id),
  CONSTRAINT fk_review_record FOREIGN KEY (record_id) REFERENCES records(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复核任务表';

-- 4. 操作日志表（批A·C5：OperationLogger 文件 + 入库双写；审计页 GET /api/logs 读本表做分页筛选）
--    文件 logs/operation.log 为兜底备份，本表为审计查询数据源；库写失败不阻塞业务
CREATE TABLE IF NOT EXISTS operation_log (
  id VARCHAR(36) PRIMARY KEY COMMENT '主键UUID',
  log_time DATETIME NOT NULL COMMENT '操作时间',
  operator VARCHAR(50) COMMENT '操作人用户名',
  role VARCHAR(20) COMMENT '操作人角色：管理员/审核员',
  action VARCHAR(50) COMMENT '操作类型：数据清洗/数据集导出/词典导入/词典回滚/人工复核/批量重算',
  target VARCHAR(255) COMMENT '操作对象：筛选范围/文件名/词典类型/病历ID',
  detail TEXT COMMENT '操作明细',
  group_id VARCHAR(36) COMMENT '操作时所属组，留痕用（§七 L7 按它做三档可见性）',
  INDEX idx_log_time (log_time),
  INDEX idx_operator (operator),
  INDEX idx_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';

-- 5. NLP 批量解析任务表（批K·K-a：后端异步任务，进度/失败清单落库，重启后可查）
CREATE TABLE IF NOT EXISTS nlp_task (
  id VARCHAR(36) PRIMARY KEY COMMENT '任务ID(UUID)',
  status VARCHAR(20) NOT NULL COMMENT '状态：QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED',
  total INT NOT NULL DEFAULT 0 COMMENT '计划处理条数',
  done INT NOT NULL DEFAULT 0 COMMENT '已处理条数',
  success INT NOT NULL DEFAULT 0 COMMENT '成功条数',
  failed INT NOT NULL DEFAULT 0 COMMENT '失败条数',
  current_label VARCHAR(255) COMMENT '当前处理的病历标识',
  filters_json TEXT COMMENT '筛选范围(JSON)',
  created_by VARCHAR(50) COMMENT '提交人用户名',
  failure_list JSON COMMENT '失败清单(仅存前500条)',
  failure_truncated TINYINT NOT NULL DEFAULT 0 COMMENT '失败清单是否被截断',
  group_id VARCHAR(36) COMMENT '提交时所属组（组隔离查询用）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at DATETIME COMMENT '开始时间',
  finished_at DATETIME COMMENT '结束时间',
  INDEX idx_status (status),
  INDEX idx_create_time (create_time),
  INDEX idx_nlp_task_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NLP批量解析任务表';

-- 批量质控重算任务（§七 L5）。异步化后前端提交即拿 taskId、轮询进度，不再长时间占请求线程。
-- role 列是**提交时的角色快照**：后台线程读不到 RequestContextHolder（会拿到 "unknown"），
-- worker 必须用它重建 RecordFilter，否则 domainGrade 返回 null → 退化成「不过滤 = 全库」。
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
  role VARCHAR(20) COMMENT '提交时角色快照，供 worker 重建 RecordFilter',
  failure_list JSON COMMENT '失败清单(仅存前500条)',
  failure_truncated TINYINT NOT NULL DEFAULT 0 COMMENT '失败清单是否被截断',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at DATETIME COMMENT '开始时间',
  finished_at DATETIME COMMENT '结束时间',
  INDEX idx_status (status),
  INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质控批量重算任务表';

-- 初始化账号（密码均为123456的BCrypt加密）
-- admin/123456 = 管理员；auditor/123456 = 审核员
INSERT INTO users (id, username, password, role) VALUES
('admin-0001', 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员'),
('audit-0001', 'auditor', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '审核员');
