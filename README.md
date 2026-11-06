# 中医电子病历智能结构化与数据质量控制系统

面向中医电子病历的结构化与数据质量控制系统。主链路：

```
Excel 导入（去重、格式规整）
  → NLP 实体抽取（python-nlp，hwtcmner + 规则兜底）
  → 术语归一（ES 召回 + Java 判定）
  → 质控评分（完整性 / 逻辑 / 格式 / 标准化 / 重复）
  → 分级（≥90 合格 · 60~89 待复核 · <60 或真缺失≥3 无效）
  → 人工复核 / 清洗导出
```

## 快速开始

### 1. 环境

| 组件 | 版本 |
|---|---|
| JDK | 17+（编译目标 17） |
| Maven | 3.9+ |
| Node.js | 18+ |
| Python | 3.11+（依赖见 `python-nlp/requirements.txt`） |
| MySQL | 8.x（库名 `tcm_ehr`） |
| Redis | 5+（本项目用 db1） |
| Elasticsearch | 7.17.x + IK 分词插件 |

### 2. 建库 —— 只需执行一个 SQL 文件

```bash
mysql -u root -p --default-character-set=utf8mb4 < database-init.sql
```

该文件包含全部表、索引与种子账号，首行 `SET NAMES utf8mb4` 保证中文不乱码。
应用启动时 Flyway 以 baseline v1 自动接管，`java-backend/src/main/resources/db/migration/V2~V9`
在新库上全是幂等空操作，**无需手工执行**。

⚠️ 本文件使用 `CREATE TABLE IF NOT EXISTS`，**不能用于旧库升级**；升级由 Flyway 完成。
改表结构时必须同时改 `database-init.sql`（新库）与新增 `V{n}__*.sql`（存量库）。

### 3. 配置

编辑 `java-backend/src/main/resources/application.yml`：

- 数据源 / Redis 连接按本机修改；`DB_USERNAME`、`DB_PASSWORD`、`REDIS_PASSWORD` 可用环境变量覆盖。
- `spring.ai.model.*` 六项须保持 `none`，否则缺 api-key 时启动失败。
- `llm.enabled` 默认 `false`（仅启动基线）；`nlp.enabled` 默认 `true`，开启前先起 :8001。
- 词典运行时来源为 `classpath:dictionaries/*.json`（`java-backend/src/main/resources/dictionaries/`），
  启动时按内容版本重播基础层并重建 ES 索引；改完词典重启即生效。

### 4. 启动（先起 MySQL / Redis / ES，再依次起三个服务，各占一个终端）

```bash
# 后端 :8080
cd java-backend && mvn spring-boot:run

# 前端 :3000（Vite 代理 /api → 8080）
cd frontend && npm install && npm run dev

# NLP :8001
cd python-nlp && .venv/Scripts/python.exe -m uvicorn main:app --host 127.0.0.1 --port 8001
```

`python-nlp/model/` 需含 `hwtcmner` 权重；缺失时服务自动降级为规则兜底（`/health` 返回 `modelAvailable=false`）。
仓库不含 `start-all.bat` 之类的本机启动脚本，请按上述命令在各自环境启动。

### 5. 演示数据

- 数据：`docs/电子病历精简脱敏数据_500行.xlsx`，通过「病历导入」上传。
- 账号：`admin / 123456`（管理员，默认组织 owner）、`auditor / 123456`（普通成员，有词典与质控规则写权限）、
  `zhangsan / 123456`、`lisi / 123456`。
- ⚠️ 500 行文本互不相同，但「主诉 + 辨证结论」仅约 120 个组合 / 10 类疾病。
  统计请按临床模板口径，不要按行数推算增幅。

### 6. 回归

```bash
cd java-backend && mvn test
cd frontend && node node_modules/vite/bin/vite.js build
python-nlp/.venv/Scripts/python.exe python-nlp/run_tests.py
```

## 目录结构

```
tcm-ehr-qc/
├── database-init.sql            # 建库唯一权威（单文件部署）
├── docs/                        # 需求 / 设计 / 契约 / 开发指南 / 演示数据
├── frontend/                    # Vue3 + Vite6 + Element Plus（:3000）
├── java-backend/                # Spring Boot 4.1.1 + Spring AI 2.0.1（:8080）
│   └── src/main/resources/
│       ├── db/migration/        # Flyway V2~V9（存量库升级）
│       └── dictionaries/        # 8 本术语词典（运行时真源）
├── python-nlp/                  # FastAPI NLP 服务（:8001）
└── tools/                       # 离线校验脚本
```

## 权威文件

| 内容 | 文件 |
|---|---|
| 建库 DDL 与种子数据 | `database-init.sql` |
| 接口契约 | `docs/中医电子病历智能结构化与数据质量控制系统-openapi.yaml` |
| 需求 / 功能 / 设计 | `docs/需求分析报告.md` · `docs/功能设计文档.md` · `docs/项目设计文档.md` |
| 开发规范、技术决策与已知问题 | `docs/开发指南.md` |
| 质控规则与评分 | `docs/质控规则与评分标准.md` · `docs/质控分级口径.md` |
| 权限五档 | `docs/角色与权限矩阵.md` |
| structuredData 结构 | `docs/功能设计文档.md` 附录 A |
| 演示数据 | `docs/电子病历精简脱敏数据_500行.xlsx` |

接手前请先读 `docs/开发指南.md` 最后一章「技术决策与已知问题」——其中列出了
契约差异、回归闸门现状与注释规范回退等需要优先处理的事项。
