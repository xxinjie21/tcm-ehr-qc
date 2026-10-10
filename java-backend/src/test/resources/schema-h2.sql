-- 集成测试用最小 H2 模式（MODE=MySQL）。只建被测链路真正用到的表。
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

-- 词典提案 / 归档四张表：P2-7 把「列表里逐条 selectCount」改成一次 GROUP BY 计数，
-- 计数取值必须钉住（取错键会让 snapshotPresent 全为 false、界面显示「快照已清理」且拒绝回滚）。
CREATE TABLE IF NOT EXISTS dict_proposal (
  id             VARCHAR(36) PRIMARY KEY,
  org_id         VARCHAR(36) NOT NULL DEFAULT '',
  type           VARCHAR(32) NOT NULL,
  submit_user_id VARCHAR(36),
  status         VARCHAR(16) NOT NULL,
  audit_user_id  VARCHAR(36),
  audit_comment  VARCHAR(500),
  create_time    DATETIME,
  audit_time     DATETIME,
  purge_after    DATETIME
);

CREATE TABLE IF NOT EXISTS dict_proposal_term (
  id            VARCHAR(36) PRIMARY KEY,
  proposal_id   VARCHAR(36) NOT NULL,
  standard_term VARCHAR(200) NOT NULL,
  aliases       CLOB
);

CREATE TABLE IF NOT EXISTS dict_archive_version (
  id            VARCHAR(36) PRIMARY KEY,
  org_id        VARCHAR(36) NOT NULL DEFAULT '',
  type          VARCHAR(32) NOT NULL,
  version_no    INT NOT NULL,
  proposal_id   VARCHAR(36),
  merge_time    DATETIME,
  merge_user_id VARCHAR(36),
  comment       VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS dict_archive_term (
  id            VARCHAR(36) PRIMARY KEY,
  version_id    VARCHAR(36) NOT NULL,
  standard_term VARCHAR(200) NOT NULL,
  aliases       CLOB
);
