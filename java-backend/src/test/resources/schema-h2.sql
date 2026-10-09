-- 集成测试用最小 H2 模式（MODE=MySQL）。只建被测链路真正用到的三张表。
-- 不用 database-init.sql：那份是 MySQL 专有（CREATE DATABASE / ENGINE=InnoDB / JSON 列 / 生成列），
-- H2 不能直接执行。列必须与 PO 字段一一对应 —— BaseMapper 会 SELECT 出 PO 的全部映射列，
-- 少一列就报「列不存在」。
-- H2 把 TEXT/JSON 统一用 CLOB 代替：MyBatis 按属性类型（String）取 getString，CLOB 也读得回来。

CREATE TABLE IF NOT EXISTS records (
  id                VARCHAR(36) PRIMARY KEY,
  registration_no   VARCHAR(50),
  outpatient_no     VARCHAR(50),
  gender            VARCHAR(10),
  age               VARCHAR(20),
  visit_count       INT,
  western_diagnosis CLOB,
  tcm_diagnosis     CLOB,
  present_illness   CLOB,
  chief_complaint   CLOB,
  self_report       CLOB,
  inspection        CLOB,
  pulse             CLOB,
  tongue            CLOB,
  physical_exam     CLOB,
  pattern           CLOB,
  prescription      CLOB,
  follow_up         CLOB,
  treatment_effect  CLOB,
  department        VARCHAR(50),
  doctor_id         VARCHAR(50),
  visit_time        DATETIME,
  structured_data   CLOB,
  qc_results        CLOB,
  score             INT,
  grade             VARCHAR(20),
  org_id            VARCHAR(36) DEFAULT '',
  status            VARCHAR(20),
  governed          TINYINT NOT NULL DEFAULT 0,
  -- 人工修改标记：线上是 MySQL STORED 生成列（由 structured_data._meta.manuallyEdited 派生，
  -- 见 data/2026-10-08-add-records-manually-edited.sql）。H2 没有等价的 JSON 函数，
  -- 这里退化为普通列，只为满足 MyBatis-Plus 全列 SELECT/INSERT；本测试不校验该标记。
  manually_edited   TINYINT NOT NULL DEFAULT 0,
  create_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
  text_hash         VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS nlp_task (
  id                VARCHAR(36) PRIMARY KEY,
  status            VARCHAR(20) NOT NULL,
  total             INT NOT NULL DEFAULT 0,
  done              INT NOT NULL DEFAULT 0,
  success           INT NOT NULL DEFAULT 0,
  failed            INT NOT NULL DEFAULT 0,
  current_label     VARCHAR(255),
  filters_json      CLOB,
  request_key       VARCHAR(64),
  created_by        VARCHAR(50),
  failure_list      CLOB,
  failure_truncated TINYINT NOT NULL DEFAULT 0,
  org_id            VARCHAR(36),
  create_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at        DATETIME,
  finished_at       DATETIME,
  cancel_requested  TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS nlp_task_items (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id     VARCHAR(64) NOT NULL,
  record_id   VARCHAR(64) NOT NULL,
  seq         INT NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  reason      VARCHAR(255),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
