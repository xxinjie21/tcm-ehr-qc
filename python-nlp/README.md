# python-nlp —— 中医病历 NLP 结构化抽取服务（成员B）

FastAPI 服务，加载开源中医 NER 模型 `Monor/hwtcmner`（RoBERTa，13 标签 BIO）做实体识别，
叠加规则兜底（舌象 / 脉象 / 病因 / 治法）与中药剂量正则，输出 9 类结构化结果，
供 Java 后端 `PythonNlpClient` 经 `POST /api/nlp/extract` 转发调用（端口 **8001**）。

## 1. 安装依赖

```bash
pip install -r requirements.txt
# 本机已装：fastapi 0.125 / uvicorn 0.38 / torch 2.13 / transformers 5.15
```

## 2. 预下载模型（离线可用）

```bash
python download_model.py
# 默认走 hf-mirror.com 镜像；产物在 ./model/（已被 .gitignore 排除，不入库）
```

产物文件：`config.json` / `tokenizer.json` / `tokenizer_config.json` / `special_tokens_map.json` / `vocab.txt` / `pytorch_model.bin`（约 422MB）。

## 3. 启动服务

```bash
uvicorn main:app --host 127.0.0.1 --port 8001
```

## 4. 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查：`{status, modelAvailable}` |
| POST | `/api/nlp/extract` | 入参 `{text}`；出参 9 类（`diseases/symptoms/tongueList/pulseList/patternList/causeList/treatmentList/formulaList/herbs`）+ `modelAvailable` |

- NER 6 类标签：来源 / 方剂 / 证型 / 病名 / 症状 / 本草（**「来源」不映射 9 类**）；
- 实体带 `confidence` 与 `source`（`model` / `rule`）；中药 `herbs` 含 `dosage`；
- **降级**：`model/` 缺失时服务仍启动，`modelAvailable=false`，仅返回规则兜底 4 类。

## 5. 与 Java 的约定

- Java `application.yml`：`nlp.enabled`（总开关）、`nlp.service-url: http://localhost:8001`；
- Java `nlp.enabled=false` 或本服务未启动 → Java 侧返回空 9 类 + `modelAvailable=false`（不做 Java 规则兜底）。
