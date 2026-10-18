"""中医电子病历 NLP 结构化抽取服务（成员B）。

FastAPI 服务，加载 Monor/hwtcmner（RoBERTa，13 标签 BIO）做 NER，
叠加规则兜底（舌象 / 脉象 / 病因 / 治法）与中药剂量正则，输出 9 类结构化结果；
最后把误落进「症状」的脉象 / 舌象要素归位到 `pulseList` / `tongueList`
（模型没有这两类标签，见 `nlp_rules._reroute_pulse_tongue`）。

运行：
    uvicorn main:app --host 127.0.0.1 --port 8001
模型：默认读 ./model/（由 download_model.py 预下载）；缺失时 modelAvailable=false 且仅规则兜底。

本文件只保留 **HTTP 层与启动钩子**。实现按依赖方向分三层：
    schemas.py     Pydantic 模型与共享字段清单（MAX_TEXT_CHARS / FIELDS）
    nlp_rules.py   规则兜底 + 舌脉归位（纯规则，不涉及 torch）
    nlp_model.py   模型加载 + NER 管线（唯一涉及 torch 的模块）

⚠️ 不要 `from nlp_model import _model`：`_model` / `_tokenizer` / `_id2label` 由
`_load_model()` 在启动时**重新赋值**，import 进来只会拿到 import 期的快照（None），
会让 /health 永远报 modelAvailable=false。本文件统一走 `nlp_model._model` 取当前值。
"""
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

import nlp_model
# 以下三个 import 同时起 re-export 作用：既有的 `from main import xxx`（test_batch20_rules /
# test_extract 等）继续可用，不必跟着改。
from nlp_filters import drop_noise  # noqa: F401
from nlp_model import _load_model, _ner  # noqa: F401
from nlp_rules import _reroute_pulse_tongue, _rules  # noqa: F401
from schemas import Entity, ExtractRequest, ExtractResponse, FIELDS  # noqa: F401

logger = logging.getLogger("nlp")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """启动时加载模型。

    原先用 @app.on_event("startup") —— FastAPI 已废弃该写法（0.141 上会告警）。
    """
    _load_model()
    yield


app = FastAPI(title="tcm-nlp", version="1.0", lifespan=lifespan)


@app.get("/health")
def health():
    """健康检查：同时回报模型是否加载成功，供后端判断当前是否只剩规则兜底。"""
    return {"status": "ok", "modelAvailable": nlp_model._model is not None}


@app.post("/api/nlp/extract", response_model=ExtractResponse)
def extract(req: ExtractRequest):
    """结构化抽取入口：模型 NER 与规则兜底的结果合并后返回。"""
    text = (req.text or "").strip()
    resp = ExtractResponse(modelAvailable=nlp_model._model is not None)
    # 1. 模型可用时先跑 NER
    if nlp_model._model is not None:
        # 截断只在模型这条路发生；规则兜底（_rules）始终用全文，
        # 所以 truncated=true 只说明「模型没看全」，不代表规则也没看全
        ner, truncated = _ner(text)
        resp.truncated = truncated
        for f in FIELDS:
            getattr(resp, f).extend(ner.get(f, []))
    # 2. 规则兜底叠加（模型缺失时这里是唯一来源）
    rules = _rules(text)
    for f in ("tongueList", "pulseList", "causeList", "treatmentList"):
        getattr(resp, f).extend(rules[f])
    # 3. 症状去脉/舌：把误落进 symptoms 的脉象 / 舌象要素归位
    _reroute_pulse_tongue(resp)
    # 4. 产物过滤：单字残词 / 假药名 / 否定句（放在归位之后，归位靠词形前缀判据不受影响）
    dropped = drop_noise(resp, text)
    if dropped:
        logger.info("[nlp] 产物过滤丢弃: %s", dropped)
    return resp
