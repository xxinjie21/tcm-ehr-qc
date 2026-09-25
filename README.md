# 中医电子病历质控与标准化系统

## 项目简介

面向中医电子病历的智能结构化与数据质量控制系统，实现从原始非结构化病历到高质量标准化数据集的全流程自动化处理。

## 业务流程

```
Excel导入（去重、格式规整）
    ↓
NLP实体抽取（hwtcmner 模型 + 正则补充舌象/脉象/病因/治法）
    ↓
术语标准化（ES国标映射）
    ↓
质控检查（缺失/格式/查重）+ 质控评分（相对评分，满分100）
    ↓
分级路由：
├─ ≥90分且无严重问题 → 合格 ──────────→ 导出
├─ 60~89分或轻微冲突 → 人工复核队列 ──→ 修正后重新评分
│                                          ├─ 合格 → 导出
│                                          └─ 仍不合格 → 脏数据隔离
└─ <60分或严重逻辑错误 → 无效脏数据 → 隔离归档（禁止导出）
```

## 技术栈

| 层次 | 技术 | 说明 |
|------|------|------|
| 前端 | Vue.js 3 + Element Plus | SPA单页应用 |
| 后端 | Spring Boot（Java） | RESTful API、业务逻辑 |
| NLP抽取 | Python FastAPI + transformers（hwtcmner） | 独立服务 :8001，Java `PythonNlpClient` 转发；模型缺失降级为规则兜底 |
| 数据库 | MySQL | 4张核心表（users / records / review_tasks / operation_log） |
| 搜索引擎 | Elasticsearch | 术语归一查询 |
| 文件存储 | JSON文件 | 术语库配置 |

## 项目结构

```
tcm-ehr-qc/
├── docs/                          # 设计文档
├── java-backend/                  # Java后端（Spring Boot）
├── frontend/                      # Vue.js前端
├── data/
│   └── dictionaries/              # 术语库配置
├── .gitignore
├── LICENSE
└── README.md
```

## 接口数量

- Java后端：50个接口（Spring Boot :8080；openapi 46 路径 / 50 操作）
- 总计：50个接口
- 契约基准：`docs/中医电子病历质控与标准化系统-openapi.yaml`（接口增删先改该文件 → 此处数量随之同步）

## 核心算法

- NLP实体抽取：Python FastAPI 服务（`transformers` 加载 hwtcmner + 正则兜底舌象/脉象/病因/治法），Java 转发；服务/模型不可用降级为空 9 类
- 质控评分：相对评分（基于病历自身缺陷项扣分），满分100分，缺失-15，逻辑冲突-10，格式-5
- 诊疗逻辑校验：固定规则表（3 条证候-治法/方剂 + 2 条舌脉；未覆盖证候不判冲突）
- 术语归一：Elasticsearch索引匹配，精确-包含-模糊
- 数据清洗：去重（21字段固定顺序拼接 + MD5；字段顺序见 `RecordUtil.textHash` 类注释，变更需回归去重）、格式规整、脏数据隔离
