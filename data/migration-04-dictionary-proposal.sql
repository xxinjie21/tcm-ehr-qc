-- 术语词典版本管理（批次 17）：提案 + 归档
-- 目标：个人本地词典 → 提交提案 → 组长审核合并 → 基线归档（每组每 type 最多 5 份）
-- 基线术语仍复用 dictionary_terms（org_id='' 为基础层），本迁移只加提案/归档两套。

CREATE TABLE IF NOT EXISTS dict_proposal (
  id             VARCHAR(36)  NOT NULL COMMENT '提案ID',
  org_id         VARCHAR(36)  NOT NULL COMMENT '所属组织；''=基础层',
  type           VARCHAR(20)  NOT NULL COMMENT '术语类型：disease/pattern/symptom/herb/formula',
  submit_user_id VARCHAR(36)  NOT NULL COMMENT '提交人',
  status         VARCHAR(16)  NOT NULL COMMENT 'pending/approved/rejected',
  audit_user_id  VARCHAR(36)  NULL COMMENT '审核人（组长）',
  audit_comment  VARCHAR(500) NULL COMMENT '审核意见 / 拒绝理由',
  create_time    DATETIME     NOT NULL COMMENT '提交时间',
  audit_time     DATETIME     NULL COMMENT '审核时间',
  purge_after    DATETIME     NULL COMMENT '仅 rejected 填：+7天后惰性清理快照；主记录永久保留',
  PRIMARY KEY (id),
  KEY idx_pending (org_id, type, status),
  KEY idx_purge (status, purge_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='词典基线更新提案';

CREATE TABLE IF NOT EXISTS dict_proposal_term (
  id            VARCHAR(36)  NOT NULL,
  proposal_id   VARCHAR(36)  NOT NULL,
  standard_term VARCHAR(200) NOT NULL COMMENT '标准词',
  code          VARCHAR(100) NULL COMMENT '国标代码',
  source        VARCHAR(100) NULL COMMENT '来源标准',
  aliases       JSON         NULL COMMENT '别名数组',
  PRIMARY KEY (id),
  KEY idx_proposal (proposal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提案术语快照（提交时的完整目标基线）';

CREATE TABLE IF NOT EXISTS dict_archive_version (
  id            VARCHAR(36)  NOT NULL,
  org_id        VARCHAR(36)  NOT NULL COMMENT '所属组织；''=基础层，与组织层独立计数',
  type          VARCHAR(20)  NOT NULL,
  version_no    INT          NOT NULL COMMENT '组内递增版本号',
  proposal_id   VARCHAR(36)  NULL COMMENT '来源提案；管理员直写导入时为 NULL',
  merge_time    DATETIME     NOT NULL COMMENT '合并时间',
  merge_user_id VARCHAR(36)  NOT NULL COMMENT '合并人（审核通过的组长 / 直写的管理员）',
  comment       VARCHAR(300) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_org_type_no (org_id, type, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='基线归档版本元信息（永久保留，即使快照已清理）';

CREATE TABLE IF NOT EXISTS dict_archive_term (
  id            VARCHAR(36)  NOT NULL,
  version_id    VARCHAR(36)  NOT NULL,
  standard_term VARCHAR(200) NOT NULL,
  code          VARCHAR(100) NULL,
  source        VARCHAR(100) NULL,
  aliases       JSON         NULL,
  PRIMARY KEY (id),
  KEY idx_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='基线归档术语快照（每组每 type 仅留最近 5 份）';

-- 旧的「每次导入留一份全量备份」机制被归档体系取代，且当前 0 行，直接废弃
DROP TABLE IF EXISTS dictionary_backups;
