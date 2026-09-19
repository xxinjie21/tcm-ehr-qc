"""中医电子病历 NLP 结构化抽取服务（成员B）。

FastAPI 服务，加载 Monor/hwtcmner（RoBERTa，13 标签 BIO）做 NER，
叠加规则兜底（舌象 / 脉象 / 病因 / 治法）与中药剂量正则，输出 9 类结构化结果。

运行：
    uvicorn main:app --host 127.0.0.1 --port 8001
模型：默认读 ./model/（由 download_model.py 预下载）；缺失时 modelAvailable=false 且仅规则兜底。
"""
import re
from typing import Dict, List, Optional

from fastapi import FastAPI
from pydantic import BaseModel

MODEL_DIR = "model"

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
                    "情志", "饮食", "劳倦", "外伤", "痰", "瘀", "湿热蕴结", "外感"]
DOSAGE_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(g|ml|mg|片|枚|条|张|付)")

FIELDS = ["diseases", "symptoms", "tongueList", "pulseList", "patternList",
          "causeList", "treatmentList", "formulaList", "herbs"]

app = FastAPI(title="tcm-nlp", version="1.0")

_tokenizer = None
_model = None
_id2label: Dict[int, str] = {}


class ExtractRequest(BaseModel):
    text: str


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


def _load_model():
    global _tokenizer, _model, _id2label
    try:
        import torch
        from transformers import AutoModelForTokenClassification, AutoTokenizer

        _tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)
        _model = AutoModelForTokenClassification.from_pretrained(MODEL_DIR)
        _model.eval()
        _id2label = {int(k): v for k, v in _model.config.id2label.items()}
        print(f"[nlp] 模型加载成功: {MODEL_DIR}, labels={len(_id2label)}")
    except Exception as e:  # 模型缺失/异常 → 仅规则兜底
        _tokenizer = None
        _model = None
        _id2label = {}
        print(f"[nlp] 模型加载失败（将仅规则兜底）: {e}")


@app.on_event("startup")
def _startup():
    _load_model()


@app.get("/health")
def health():
    return {"status": "ok", "modelAvailable": _model is not None}


def _ner(text: str) -> Dict[str, List[dict]]:
    """模型 NER：BIO 解码 + offsets→sourceText + 置信度。"""
    import torch

    enc = _tokenizer(text, return_offsets_mapping=True, return_tensors="pt",
                     truncation=True, max_length=510)
    offsets = enc.pop("offset_mapping")[0].tolist()
    with torch.no_grad():
        logits = _model(**enc).logits[0]
    probs = torch.softmax(logits, dim=-1)
    ids = logits.argmax(-1).tolist()

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
    _flush(out, cur_field, text, cur_start, cur_end, cur_confs)
    return out


def _flush(out, field, text, start, end, confs):
    if not field or start is None:
        return
    span = text[start:end]
    conf = round(sum(confs) / len(confs), 4) if confs else None
    if field == "herbs":
        m = DOSAGE_RE.search(text[end:end + 8])
        out["herbs"].append(Herb(name=span, dosage=m.group(0) if m else None,
                                 sourceText=span, confidence=conf, source="model"))
    else:
        out[field].append(Entity(content=span, sourceText=span, confidence=conf, source="model"))


def _rules(text: str) -> Dict[str, List[Entity]]:
    def ent(s):
        return Entity(content=s, sourceText=s, source="rule")

    tongue = [ent(m.group(0)) for m in RULE_TONGUE.finditer(text)]
    pulse = [ent(m.group(0)) for m in RULE_PULSE.finditer(text)]
    cause = [ent(w) for w in dict.fromkeys(RULE_CAUSE_WORDS) if w in text]
    treatment = [ent(m.group(0)) for m in RULE_TREATMENT.finditer(text)]
    return {"tongueList": tongue, "pulseList": pulse, "causeList": cause, "treatmentList": treatment}


@app.post("/api/nlp/extract", response_model=ExtractResponse)
def extract(req: ExtractRequest):
    text = (req.text or "").strip()
    resp = ExtractResponse(modelAvailable=_model is not None)
    if _model is not None:
        ner = _ner(text)
        for f in FIELDS:
            getattr(resp, f).extend(ner.get(f, []))
    rules = _rules(text)
    for f in ("tongueList", "pulseList", "causeList", "treatmentList"):
        getattr(resp, f).extend(rules[f])
    return resp
