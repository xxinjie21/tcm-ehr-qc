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
import threading
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

# 推理并发上限（AI-12）：extract 是同步 def，FastAPI 会把它投进 anyio 线程池（默认上限 40）；
# torch 推理是 CPU 密集型，40 个线程同时跑只会争抢 GIL 与内存（RSS 峰值翻倍）。
# 用信号量把「同时在跑的推理」收在本值以内 —— 超出者排队，而不是一起挤。
#
# 2026-10-12 实测（32 逻辑核、263 字病历、每档 24 个请求）：
#   torch 沿用默认 16 线程 + 信号量 4 → 吞吐恒定 ~7 条/s，加并发只涨延迟
#     （单条一次就吃掉 16 个核，CPU 当场满，多发请求只是排队）
#   torch 4 线程 + 信号量 8          → 峰值 10.3 条/s（并发 8），+45%
#   torch 8 线程 + 信号量 8          → 峰值 9.6 条/s
# 结论：小模型单条推理（batch=1）不该开大线程数 —— 线程多了全是同步开销；
# 正确姿势是「少线程 × 多并发」。默认值据此按核数自适应，换机器不会退化；
# 仍可用 NLP_TORCH_THREADS / NLP_INFER_CONCURRENCY 覆盖。
_CPU = os.cpu_count() or 4
# 单条推理的 torch 线程数：按 1/8 核数取，上限 4（实测超过 4 反而更慢）
_DEFAULT_TORCH_THREADS = max(1, min(4, _CPU // 8))
# 同时在跑的推理条数：用剩下的核数补满，上限 12（实测 16 并发开始掉吞吐）
_DEFAULT_INFER_CONCURRENCY = max(2, min(12, _CPU // _DEFAULT_TORCH_THREADS))
INFER_CONCURRENCY = int(os.environ.get("NLP_INFER_CONCURRENCY", str(_DEFAULT_INFER_CONCURRENCY)))
# 每个推理允许使用的 torch 线程数；显式设 0 = 沿用 torch 默认（不推荐，见上）。
# 与 INFER_CONCURRENCY 相乘才是真实 CPU 占用，两者一起设才能避免 oversubscribe。
TORCH_NUM_THREADS = int(os.environ.get("NLP_TORCH_THREADS", str(_DEFAULT_TORCH_THREADS)))
# 推理信号量：只包住真正吃 CPU 的「编码 + 前向」
_infer_sem = threading.Semaphore(INFER_CONCURRENCY)
# torch 线程数只需设置一次
_torch_threads_set = False

# NER 标签类型 → 结构化字段（来源不映射 9 类，丢弃）。
# 这只是「模型能输出的标签集」这一维度的 5 个值，是 EntityTypes.ALL 的过滤视图，
# 不是结构化字段目录本身 —— 不要把它与 Java 侧字段清单对齐长度（批次 25.b 定案）。
LABEL_FIELD = {
    "病名": "diseases",
    "症状": "symptoms",
    "证型": "patternList",
    "方剂": "formulaList",
    "本草": "herbs",
}
# 舌象 / 脉象：批次 20 起已有 tongues.json / pulses.json 词典（此前只有格式规则）。
# 下面的正则仍在词典归一之前把子要素切成独立段（舌质/舌苔/齿痕/裂纹），
# 抽不到或词表未收录的段保留原文，不计入未归一扣分（见 EntityTypes 的 countsUnnormalized）。
# 换数据集若写「左关尤甚」（无「脉」字）或苔色单独成列，仍会漏。见 §九 9.5 风险 1/2。
#
# ①  舌：字符类由「舌」扩到「舌苔」。原正则只认「舌」起头，`舌质淡红，苔薄白` 会把
#     「苔薄白」整段丢掉（苔色、苔质是舌诊的另一半）。
# ②  舌：补**形态锚**（批次 20）。`边有齿痕`/`有裂纹`/`有瘀点` 不以「舌/苔」起头，
#     原正则整段丢掉 —— 本数据集 500 条里 50 条含「边有齿痕」，全部丢失。
#     这几类是舌形/舌面的标准子要素，丢掉等于该子要素完全不可分析。
#     只认这几个固定形态词，不泛化到任意「有X」——
#     否则「有神」「有神气」一类描述性措辞会被误当成子要素。
#     尾部的字符组用 * 而不是 +：`有裂纹` 本身只有 3 字，尾部可能为空，
#     用 {1,6} 会要求它后面还必须有字，反而漏掉最常见的写法。
#     「或」连接也收（原文是「舌红少苔，或有裂纹」），输出时去掉这个连词。
RULE_TONGUE = re.compile(r"^[舌苔][^，。、；;\s]{1,8}$")
RULE_TONGUE_MORPH = re.compile(r"(?:或)?(?:边有|有裂纹|有瘀点|有瘀)[^，。、；;\s]{0,6}")
# ③  脉：脉位必须**独立成段**（批次 20）。原正则把「，左尺无力」作为可选续接吞进
#     前一段，得到 `脉细数，左尺无力` 一条 —— 脉位（左/右 + 寸关尺）是独立于脉象的
#     标准维度，混在一起就分不出「脉细数」与「左尺无力」两个不同语义。
#     仍只认「左/右 + 寸关尺」这几个固定锚，不照搬「任意逗号续接」——
#     否则 `脉细，舌红` 的「舌红」会被吞进脉象。
RULE_PULSE = re.compile(r"^脉[^，。、；;\s]{1,6}$")
RULE_PULSE_POSITION = re.compile(r"(?:左|右)(?:寸|关|尺)[^，。、；;\s]{0,4}")
# 按标点分段的统一切分：舌象/脉象的子要素判定依赖段边界（见 _rules 注释）
SEG_SPLIT = re.compile(r"[，。、；;\s]+")
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
    # 把并发预算打出来：这两个值决定吞吐，且默认是按核数算的 ——
    # 不打印的话，换机器后「为什么变慢了/没变快」只能靠猜。
    logger.info("[nlp] CPU=%d 核 → torch 线程 %d × 并发 %d（可用 NLP_TORCH_THREADS / "
                "NLP_INFER_CONCURRENCY 覆盖）", _CPU, TORCH_NUM_THREADS, INFER_CONCURRENCY)
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


def _log_model_fingerprint():
    """启动时把模型指纹打进日志：抽取结果要能溯源到具体权重。

    为什么要记：NER 结果直接进 structured_data 并参与质控评分。若本地权重被
    换过而没人在意，同一份病历的抽取结果就会变，历史数据不可复现、批次之间
    也不能比较。记录 revision 与权重 sha256，出问题时能立刻判断「是数据变了
    还是模型变了」。

    刻意**只告警不阻断**：指纹对不上时服务仍可用（降级或照常跑），但日志必须
    留痕 —— 阻断会让「换模型」这种正常运维动作直接打不开服务。
    """
    try:
        from download_model import WEIGHTS_SHA256, MODEL_REVISION
    except Exception:
        return
    weights = os.path.join(MODEL_DIR, "pytorch_model.bin")
    if not os.path.isfile(weights):
        logger.info("[nlp] 未找到权重文件（modelAvailable=false），跳过指纹记录")
        return
    try:
        import hashlib
        h = hashlib.sha256()
        with open(weights, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        actual = h.hexdigest()
    except Exception as e:
        logger.warning("[nlp] 计算权重指纹失败（不影响服务）: %s", e)
        return
    if actual == WEIGHTS_SHA256:
        logger.info("[nlp] 模型指纹校验通过: %s@%s weights=%s",
                    "Monor/hwtcmner", MODEL_REVISION[:12], actual)
    else:
        logger.warning(
            "[nlp] 模型指纹与钉定版本不一致 —— 抽取结果可能与历史数据不可比！\n"
            "        期望 revision=%s weights=%s\n"
            "        实际 weights=%s\n"
            "        若确实要换版本，请同步更新 download_model.py 的 MODEL_REVISION 与 "
            "WEIGHTS_SHA256，并在《多批次实施计划》登记。",
            MODEL_REVISION[:12], WEIGHTS_SHA256, actual)


def _load_model():
    """加载本地 NER 模型与分词器；失败则置空，服务降级为纯规则兜底。"""
    global _tokenizer, _model, _id2label
    # 1. 打印解析后的绝对路径，便于定位「相对路径解析到错误目录」的问题
    logger.info("[nlp] 模型目录解析为: %s", os.path.abspath(MODEL_DIR))
    try:
        # 2. 加载权重与标签表
        #    这里不 import torch：真正用到它的是 _ner()，那边自己 import。
        #    删掉不影响降级路径 —— torch 缺失时下面这行 import 同样抛错，
        #    由同一个 except 兜住置空、退回纯规则。
        from transformers import AutoModelForTokenClassification, AutoTokenizer

        _tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)
        _model = AutoModelForTokenClassification.from_pretrained(MODEL_DIR)
        _model.eval()
        _id2label = {int(k): v for k, v in _model.config.id2label.items()}
        logger.info("[nlp] 模型加载成功: %s, labels=%d", MODEL_DIR, len(_id2label))
        # 3. 记录权重指纹（批次 23）：抽取结果要能溯源到具体权重
        _log_model_fingerprint()
    except Exception:  # 模型缺失/异常 → 仅规则兜底
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

    # 1. 进程内 torch 线程数只设一次（AI-12）：避免「请求并发 × 进程内线程」双重 oversubscribe
    _apply_torch_threads(torch)

    # 2. 编码 + 前向都在信号量内：并发超过 INFER_CONCURRENCY 时排队，而不是一起挤 CPU。
    #    编码并保留字符偏移，超长按 NER_MAX_TOKENS 截断
    with _infer_sem:
        enc = _tokenizer(text, return_offsets_mapping=True, return_tensors="pt",
                         truncation=True, max_length=NER_MAX_TOKENS)
        truncated = int(enc["input_ids"].shape[-1]) >= NER_MAX_TOKENS
        offsets = enc.pop("offset_mapping")[0].tolist()
        # inference_mode 比 no_grad 更彻底（P2-15）：除关闭自动求导外，还跳过版本计数与
        # 视图追踪，前向的内存与少量算子开销都更低。本处只做前向 + softmax + argmax，
        # 不产生需要参与自动求导的张量，两者数值完全一致，可放心替换。
        with torch.inference_mode():
            logits = _model(**enc).logits[0]
        probs = torch.softmax(logits, dim=-1)
        ids = logits.argmax(-1).tolist()

    # 3. 逐 token 做 BIO 解码：B 起新实体、I 续接同字段、其余收尾当前实体
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
    # 4. 收尾最后一个未闭合的实体
    _flush(out, cur_field, text, cur_start, cur_end, cur_confs)
    return out, truncated


def _apply_torch_threads(torch) -> None:
    """把进程内 torch 线程数固定下来（AI-12）；只生效一次，0 = 沿用默认。"""
    global _torch_threads_set
    if TORCH_NUM_THREADS <= 0 or _torch_threads_set:
        return
    try:
        torch.set_num_threads(TORCH_NUM_THREADS)
        _torch_threads_set = True
        logger.info("[nlp] torch 线程数已设为 %d", TORCH_NUM_THREADS)
    except Exception:  # 设置失败不影响推理，只是沿用默认线程数
        logger.exception("[nlp] 设置 torch 线程数失败（沿用默认）")


def _flush(out: Dict[str, List[Entity]], field: str, text: str,
          start: Optional[int], end: Optional[int], confs: List[float]) -> None:
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

    # 1. 舌象 / 脉象 / 治法走正则：按标点分段逐段判定。
#    分段而不是整段 finditer 的原因：形态词「或」在原文里自带连接作用
#    （「舌红少苔，或有裂纹」），只有按段判定才知道它是一段独立描述；
#    而脉位要独立成段，也依赖段边界。
    tongue = []
    for seg in SEG_SPLIT.split(text):
        if not seg:
            continue
        if RULE_TONGUE.match(seg) or RULE_TONGUE_MORPH.match(seg):
            # 输出时去掉「或」连接词：它是原文的连词，不是子要素的一部分
            tongue.append(ent(seg[1:] if seg.startswith("或") else seg))
    # 2. 脉象：脉象本体与脉位分开成段（批次 20）。
    #    脉位（左/右 + 寸关尺）是独立于脉象的标准维度，合在一段就分不出
    #    「脉细数」与「左尺无力」两个语义。按标点分段后天然独立，无需额外剥离。
    pulse = [ent(seg) for seg in SEG_SPLIT.split(text)
             if seg and RULE_PULSE.match(seg)]
    pulse.extend(ent(m.group(0)) for m in RULE_PULSE_POSITION.finditer(text)
                 if m.group(0) not in [p.content for p in pulse])
    # 脉位排在脉象之后：阅读顺序是「先读脉象，再看哪一部位异常」
    # 2. 病因走词表包含匹配；dict.fromkeys 的去重作用对此处是冗余的（RULE_CAUSE_WORDS 本身无重复项），只保证顺序与控制去重即可
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
