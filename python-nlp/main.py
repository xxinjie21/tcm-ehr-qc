"""中医电子病历 NLP 结构化抽取服务（成员B）。

FastAPI 服务，加载 Monor/hwtcmner（RoBERTa，13 标签 BIO）做 NER，
叠加规则兜底（舌象 / 脉象 / 病因 / 治法）与中药剂量正则，输出 9 类结构化结果。

运行：
    uvicorn main:app --host 127.0.0.1 --port 8001
模型：默认读 ./model/（由 download_model.py 预下载）；缺失时 modelAvailable=false 且仅规则兜底。
"""
import logging
import os
import re
from contextlib import asynccontextmanager
from typing import Dict, List, Optional, Tuple

from fastapi import FastAPI
from pydantic import BaseModel, Field

logger = logging.getLogger("nlp")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

# 模型目录：默认仍是相对路径 "model"，可用 NLP_MODEL_DIR 覆盖。
# 相对路径按**当前工作目录**解析 —— 在 python-nlp/ 之外启动会静默加载失败
# （服务照常起、/health 也 ok，只是 modelAvailable=false），所以启动时把解析结果打出来。
MODEL_DIR = os.environ.get("NLP_MODEL_DIR", "model")

# 单次请求的文本上限（字符）。超长直接 422，而不是悄悄截断
MAX_TEXT_CHARS = 20000
# NER 的 token 上限；超出部分模型看不到（规则兜底仍用全文，见 _rules）
NER_MAX_TOKENS = 510

# NER 标签类型 → 结构化字段（来源不映射 9 类，丢弃）
LABEL_FIELD = {
    "病名": "diseases",
    "症状": "symptoms",
    "证型": "patternList",
    "方剂": "formulaList",
    "本草": "herbs",
}
RULE_TONGUE = re.compile(r"舌[^，。、；;、\s]{1,8}")
RULE_PULSE = re.compile(r"脉[^，。、；;、\s]{1,6}")
RULE_TREATMENT = re.compile(r"治[以法]?[^，。；;\s]{1,10}")
RULE_CAUSE_WORDS = ["风寒", "风热", "暑湿", "风湿", "湿热", "寒湿", "气虚", "血虚", "阴虚", "阳虚",
                    "情志", "饮食", "劳倦", "外伤", "痰", "瘀", "外感"]
# 注：不要加「湿热蕴结」这类含更短词的条目 —— 命中判定是 `w in text`，
# 文本里有「湿热蕴结」时「湿热」已经命中，再加一条会让同一个病因出现两次
DOSAGE_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(g|ml|mg|片|枚|条|张|付)")

# 9 类结构化字段的固定顺序（响应组装与前端展示都按它走）
FIELDS = ["diseases", "symptoms", "tongueList", "pulseList", "patternList",
          "causeList", "treatmentList", "formulaList", "herbs"]

@asynccontextmanager
async def lifespan(_app: FastAPI):
    """启动时加载模型。

    原先用 @app.on_event("startup") —— FastAPI 已废弃该写法（0.141 上会告警）。
    """
    _load_model()
    yield


app = FastAPI(title="tcm-nlp", version="1.0", lifespan=lifespan)

_tokenizer = None
_model = None
_id2label: Dict[int, str] = {}


class ExtractRequest(BaseModel):
    text: str = Field(max_length=MAX_TEXT_CHARS)


class Entity(BaseModel):
    content: str
    sourceText: str
    confidence: Optional[float] = None
    source: str = "model"


class Herb(BaseModel):
    name: str
    dosage: Optional[str] = None
    sourceText: str
    confidence: Optional[float] = None
    source: str = "model"


class ExtractResponse(BaseModel):
    diseases: List[Entity] = []
    symptoms: List[Entity] = []
    tongueList: List[Entity] = []
    pulseList: List[Entity] = []
    patternList: List[Entity] = []
    causeList: List[Entity] = []
    treatmentList: List[Entity] = []
    formulaList: List[Entity] = []
    herbs: List[Herb] = []
    modelAvailable: bool = False
    # 是否因长度被截断（模型只看前 NER_MAX_TOKENS 个 token；规则兜底始终用全文）
    truncated: bool = False


def _load_model():
    """加载本地 NER 模型与分词器；失败则置空，服务降级为纯规则兜底。"""
    global _tokenizer, _model, _id2label
    # 1. 打印解析后的绝对路径，便于定位「相对路径解析到错误目录」的问题
    logger.info("[nlp] 模型目录解析为: %s", os.path.abspath(MODEL_DIR))
    try:
        # 2. 加载权重与标签表
        import torch
        from transformers import AutoModelForTokenClassification, AutoTokenizer

        _tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)
        _model = AutoModelForTokenClassification.from_pretrained(MODEL_DIR)
        _model.eval()
        _id2label = {int(k): v for k, v in _model.config.id2label.items()}
        logger.info("[nlp] 模型加载成功: %s, labels=%d", MODEL_DIR, len(_id2label))
    except Exception as e:  # 模型缺失/异常 → 仅规则兜底
        _tokenizer = None
        _model = None
        _id2label = {}
        logger.exception("[nlp] 模型加载失败（将仅规则兜底）")


@app.get("/health")
def health():
    """健康检查：同时回报模型是否加载成功，供后端判断当前是否只剩规则兜底。"""
    return {"status": "ok", "modelAvailable": _model is not None}


def _ner(text: str) -> Tuple[Dict[str, List[dict]], bool]:
    """模型 NER：BIO 解码 + offsets→sourceText + 置信度。

    :return (各字段实体, 是否被截断)。截断时模型只看得到前 NER_MAX_TOKENS 个 token，
            尾部实体会静默丢失 —— 所以把标记回传，让调用方知道这次结果不完整。
    """
    import torch

    # 1. 编码并保留字符偏移，超长按 NER_MAX_TOKENS 截断
    enc = _tokenizer(text, return_offsets_mapping=True, return_tensors="pt",
                     truncation=True, max_length=NER_MAX_TOKENS)
    truncated = int(enc["input_ids"].shape[-1]) >= NER_MAX_TOKENS
    offsets = enc.pop("offset_mapping")[0].tolist()
    with torch.no_grad():
        logits = _model(**enc).logits[0]
    probs = torch.softmax(logits, dim=-1)
    ids = logits.argmax(-1).tolist()

    # 2. 逐 token 做 BIO 解码：B 起新实体、I 续接同字段、其余收尾当前实体
    out: Dict[str, List[dict]] = {f: [] for f in FIELDS}
    cur_field, cur_start, cur_end, cur_confs = None, None, None, []
    for ti, ((start, end), lid) in enumerate(zip(offsets, ids)):
        if start == end:  # special token
            continue
        label = _id2label.get(lid, "O")
        tag, _, typ = label.partition("-")
        field = LABEL_FIELD.get(typ)
        prob = float(probs[ti][lid])
        if tag == "B" and field:
            _flush(out, cur_field, text, cur_start, cur_end, cur_confs)
            cur_field, cur_start, cur_end, cur_confs = field, start, end, [prob]
        elif tag == "I" and field == cur_field and cur_field:
            cur_end = end
            cur_confs.append(prob)
        else:
            _flush(out, cur_field, text, cur_start, cur_end, cur_confs)
            cur_field, cur_start, cur_end, cur_confs = None, None, None, []
    # 3. 收尾最后一个未闭合的实体
    _flush(out, cur_field, text, cur_start, cur_end, cur_confs)
    return out, truncated


def _flush(out, field, text, start, end, confs):
    """把当前累积的一段实体收进结果；herbs 额外向后抓剂量。"""
    if not field or start is None:
        return
    span = text[start:end]
    # 置信度取该实体所有 token 概率的均值
    conf = round(sum(confs) / len(confs), 4) if confs else None
    if field == "herbs":
        # 中药常紧跟剂量（如「麻黄 6g」），从实体末尾再向后看 8 个字符
        m = DOSAGE_RE.search(text[end:end + 8])
        out["herbs"].append(Herb(name=span, dosage=m.group(0) if m else None,
                                 sourceText=span, confidence=conf, source="model"))
    else:
        out[field].append(Entity(content=span, sourceText=span, confidence=conf, source="model"))


def _rules(text: str) -> Dict[str, List[Entity]]:
    """规则兜底：正则抽舌象 / 脉象 / 治法，病因表按包含匹配；始终基于全文。"""
    def ent(s):
        return Entity(content=s, sourceText=s, source="rule")

    # 1. 舌象 / 脉象 / 治法走正则
    tongue = [ent(m.group(0)) for m in RULE_TONGUE.finditer(text)]
    pulse = [ent(m.group(0)) for m in RULE_PULSE.finditer(text)]
    # 2. 病因走词表包含匹配，dict.fromkeys 去重并保序，避免同一病因重复出现
    cause = [ent(w) for w in dict.fromkeys(RULE_CAUSE_WORDS) if w in text]
    treatment = [ent(m.group(0)) for m in RULE_TREATMENT.finditer(text)]
    return {"tongueList": tongue, "pulseList": pulse, "causeList": cause, "treatmentList": treatment}


@app.post("/api/nlp/extract", response_model=ExtractResponse)
def extract(req: ExtractRequest):
    """结构化抽取入口：模型 NER 与规则兜底的结果合并后返回。"""
    text = (req.text or "").strip()
    resp = ExtractResponse(modelAvailable=_model is not None)
    # 1. 模型可用时先跑 NER
    if _model is not None:
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
    return resp
