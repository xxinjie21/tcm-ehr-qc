# 中医电子病历质控与标准化系统

## 项目简介

面向中医电子病历的智能结构化与数据质量控制系统，实现从原始非结构化病历到高质量标准化数据集的全流程自动化处理。

## 业务流程

```
Excel导入（去重、格式规整）
    ↓
NLP实体抽取（TCMNER + jieba + 正则补充舌象/脉象/治法）
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
| NLP服务 | FastAPI（Python） | NLP模型调用 |
| 数据库 | MySQL | 3张核心表 |
| 搜索引擎 | Elasticsearch | 术语归一查询 |
| 文件存储 | JSON文件 | 术语库配置 |

## 项目结构

```
tcm-ehr-qc/
├── docs/                          # 设计文档
├── java-backend/                  # Java后端（Spring Boot）
├── python-nlp/                    # Python NLP服务（FastAPI）
├── frontend/                      # Vue.js前端
├── data/
│   └── dictionaries/              # 术语库配置
├── .gitignore
├── LICENSE
└── README.md
```

## 接口数量

- Java后端：22个接口（Spring Boot :8080）
- Python NLP服务：1个接口（FastAPI :8001）
- 总计：23个接口

## 核心算法

- NLP实体抽取：hwtcmner/TCMNER模型 + jieba分词 + 正则后处理
- 质控评分：相对评分（基于病历自身缺陷项扣分），满分100分，缺失-15，逻辑冲突-10，格式-5
- 诊疗逻辑校验：固定2-3条核心规则
- 术语归一：Elasticsearch索引匹配，精确-包含-模糊
- 数据清洗：去重、格式规整、脏数据隔离
