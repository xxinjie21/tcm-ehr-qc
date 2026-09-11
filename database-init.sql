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
  role VARCHAR(20) NOT NULL COMMENT '角色：管理员/审核员',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

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
  grade VARCHAR(20) COMMENT '分级：合格/待复核/无效',
  status VARCHAR(20) COMMENT '状态：pending/reviewing/completed/invalid',
  governed TINYINT NOT NULL DEFAULT 0 COMMENT '已治理标记（清洗+术语归一完成后置1）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_registration_no (registration_no),
  INDEX idx_status (status),
  INDEX idx_grade (grade)
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
  INDEX idx_record_id (record_id),
  CONSTRAINT fk_review_record FOREIGN KEY (record_id) REFERENCES records(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复核任务表';

-- 初始化账号（密码均为123456的BCrypt加密）
-- admin/123456 = 管理员；auditor/123456 = 审核员
INSERT INTO users (id, username, password, role) VALUES
('admin-0001', 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员'),
('audit-0001', 'auditor', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '审核员');
