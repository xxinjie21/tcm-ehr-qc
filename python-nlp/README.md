# python-nlp —— 中医病历 NLP 结构化抽取服务（成员B）

FastAPI 服务，加载开源中医 NER 模型 `Monor/hwtcmner`（RoBERTa，13 标签 BIO）做实体识别，
叠加规则兜底（舌象 / 脉象 / 病因 / 治法）与中药剂量正则，输出 9 类结构化结果，
供 Java 后端 `PythonNlpClient` 经 `POST /api/nlp/extract` 转发调用（端口 **8001**）。

## 0. 代码结构

```
main.py             FastAPI 入口：app / lifespan / 两条路由（启动仍用 `uvicorn main:app`）
schemas.py          Pydantic 模型与共享字段清单（MAX_TEXT_CHARS / FIELDS）
nlp_rules.py        规则兜底 + 舌脉归位（纯规则，不涉及 torch）
nlp_model.py        模型加载 + NER 管线（唯一涉及 torch 的模块）
run_tests.py        回归闸门，等价于 Java 侧的 `mvn test`
tests/              测试脚本（test_extract.py 是手动调试脚本，不进闸门）
download_model.py   预下载模型权重到 ./model/
```

依赖方向单向：`schemas <- nlp_rules <- nlp_model <- main`。

⚠️ 不要写 `from nlp_model import _model`：它由 `_load_model()` 在启动时**重新赋值**，
import 只会拿到 import 期的 None 快照；统一走 `nlp_model._model` 取当前值。

## 1. 安装依赖

```bash
pip install -r requirements.txt
# 本机已装（实测）：fastapi 0.141.1 / uvicorn 0.53.0 / torch 2.14.0+cpu / transformers 5.17.0
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
