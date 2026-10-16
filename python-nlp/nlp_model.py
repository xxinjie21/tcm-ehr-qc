"""NLP 模型加载与 NER 推理管线（唯一涉及 torch 的模块）。

只依赖 schemas 与 nlp_rules。torch 全部**延迟 import**（放在 _load_model / _ner /
_ner_forward 内部），这样模型缺失或 torch 未装时服务仍能起、只走规则兜底。
"""
import logging
import os
import threading
from typing import Dict, List, Optional, Tuple

from nlp_rules import DOSAGE_RE
from schemas import Entity, FIELDS, Herb

logger = logging.getLogger("nlp")

# 模型目录：默认仍是相对路径 "model"，可用 NLP_MODEL_DIR 覆盖。
# 相对路径按**当前工作目录**解析 —— 在 python-nlp/ 之外启动会静默加载失败
# （服务照常起、/health 也 ok，只是 modelAvailable=false），所以启动时把解析结果打出来。
MODEL_DIR = os.environ.get("NLP_MODEL_DIR", "model")

# 模型单窗口的 token 上限（含 [CLS]/[SEP]）。长文本由 _ner 按标点切成多个窗口
# 逐段前向再合并 —— 不再像旧实现那样把超出部分整段丢掉（规则兜底始终用全文，见 _rules）
NER_MAX_TOKENS = 510
# 无标点长句不得不按 token 硬切时的窗口重叠量：让被边界切断的实体在相邻窗口各出现一次，
# 合并（_merge_runs）时按字符区间重叠复原成完整实体
NER_WINDOW_OVERLAP = 24

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


def _env_int(name: str, default: int, lo: int, hi: int, allow_non_positive: bool = False) -> int:
    """读整型环境变量并夹到合法区间；非法值退默认并告警（C4）。

    C4 之前是 `int(os.environ.get(...))` 直读，两个问题：
    1) 非数字在 **import 期** 抛 ValueError —— 服务直接起不来，报错只有一行栈、看不出是哪个变量；
    2) 0 / 负数 / 超大值静默生效 —— `NLP_INFER_CONCURRENCY=0` 会让 Semaphore 永久阻塞（服务假死），
       超大值把 CPU 打满反而掉吞吐（实测 16 并发开始掉）。
    """
    raw = os.environ.get(name)
    if raw is None or str(raw).strip() == "":
        return default
    try:
        value = int(str(raw).strip())
    except ValueError:
        print(f"[warn] {name}={raw!r} 不是整数，改用默认值 {default}", flush=True)
        return default
    # NLP_TORCH_THREADS 的「<= 0 = 沿用 torch 默认」是文档化用法（见下方注释与 _apply_torch_threads），不能夹掉
    if allow_non_positive and value <= 0:
        return value
    if value < lo or value > hi:
        clamped = max(lo, min(hi, value))
        print(f"[warn] {name}={value} 超出允许范围 [{lo}, {hi}]，已夹到 {clamped}", flush=True)
        return clamped
    return value


INFER_CONCURRENCY = _env_int("NLP_INFER_CONCURRENCY", _DEFAULT_INFER_CONCURRENCY, 1, 64)
# 每个推理允许使用的 torch 线程数；显式设 0 = 沿用 torch 默认（不推荐，见上）。
# 与 INFER_CONCURRENCY 相乘才是真实 CPU 占用，两者一起设才能避免 oversubscribe。
TORCH_NUM_THREADS = _env_int("NLP_TORCH_THREADS", _DEFAULT_TORCH_THREADS, 1, 64,
                             allow_non_positive=True)
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

_tokenizer = None
_model = None
_id2label: Dict[int, str] = {}


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


def _ner(text: str) -> Tuple[Dict[str, List[dict]], bool]:
    """模型 NER：把长文本按标点切成多个 ≤NER_MAX_TOKENS 的窗口逐段前向，再合并。

    旧实现直接 `truncation=True, max_length=NER_MAX_TOKENS`，超过 510 token 的尾部
    会被静默丢掉 —— 而长病历的中医诊断 / 辨证 / 处方恰好都排在文本尾部，抽出来自然是
    空的。现在整段文本都会被覆盖，故恒返回 truncated=False（该标志保留只为兼容既有的
    Java / 前端接口）。

    :return (各字段实体, 是否被截断)。恒为 False —— 文本已整段覆盖。
    """
    import torch

    out: Dict[str, List[dict]] = {f: [] for f in FIELDS}
    if not text:
        return out, False

    # 1. 进程内 torch 线程数只设一次（AI-12）：避免「请求并发 × 进程内线程」双重 oversubscribe
    _apply_torch_threads(torch)

    # 2. 全部窗口的前向放在同一个信号量内：一次请求算一个并发单位
    runs: List[Tuple[str, int, int, List[float]]] = []
    with _infer_sem:
        for base, chunk in _ner_windows(text):
            _ner_forward(chunk, base, torch, runs)

    # 3. 合并跨窗口重复 / 重叠的实体后，按全局字符区间_flush 成最终结果
    for field, start, end, confs in _merge_runs(runs):
        _flush(out, field, text, start, end, confs)
    return out, False


def _ntokens(s: str) -> int:
    """不含特殊符的 token 数，用来规划窗口大小（中文约 1 字 1 token）。"""
    return int(_tokenizer(s, add_special_tokens=False, return_tensors="pt")["input_ids"].shape[-1])


def _ner_windows(text: str):
    """规划窗口：产出 (全局起始字符, 窗口文本)，窗口拼接后完整覆盖 text。

    先按标点在句读处切块，再贪心打包到 ≤ budget 个 token。这样窗口边界落在一句话的
    结尾，几乎不会把实体切碎；只有「整块本身超过 budget」的无标点长句才按 token 硬切
    （带 NER_WINDOW_OVERLAP 重叠，交给 _merge_runs 复原）。
    """
    budget = NER_MAX_TOKENS - 2  # 预留 [CLS] / [SEP]
    if _ntokens(text) <= budget:
        yield 0, text
        return
    buf_start, buf, buf_tok = 0, [], 0
    for p_start, piece in _pieces(text):
        tok = _ntokens(piece)
        if tok > budget:  # 无标点长句：先冲掉缓冲，再单独滑动切分
            if buf:
                yield buf_start, "".join(buf)
                buf, buf_tok = [], 0
            yield from _split_long_piece(p_start, piece, budget)
            continue
        if buf and buf_tok + tok > budget:  # 当前窗口装不下 → 收尾开新窗口
            yield buf_start, "".join(buf)
            buf, buf_tok = [], 0
        if not buf:
            buf_start = p_start
        buf.append(piece)
        # 各块 token 数之和是拼接口 token 数的上界（拼接只会合并、不会新增 token），
        # 用它预算是安全的：真实窗口一定 ≤ budget
        buf_tok += tok
    if buf:
        yield buf_start, "".join(buf)


def _pieces(text: str):
    """把文本切成「以标点结尾」的小块，保留全部字符（拼接回去等于原文）。"""
    start = 0
    for i, ch in enumerate(text):
        if ch in "。！？；，、：\n":
            yield start, text[start:i + 1]
            start = i + 1
    if start < len(text):
        yield start, text[start:]


def _split_long_piece(start: int, piece: str, budget: int):
    """单个无标点长句超过预算时按 token 滑动切分，窗口间留 NER_WINDOW_OVERLAP 重叠。"""
    offs = _tokenizer(piece, return_offsets_mapping=True,
                      return_tensors="pt")["offset_mapping"][0].tolist()
    n = len(offs)
    if n <= budget:
        yield start, piece
        return
    step = max(1, budget - NER_WINDOW_OVERLAP)
    i = 0
    while i < n:
        j = min(i + budget, n)
        s, e = offs[i][0], offs[j - 1][1]
        yield start + s, piece[s:e]
        if j >= n:
            break
        i += step


def _ner_forward(chunk: str, base: int, torch, runs: List[Tuple[str, int, int, List[float]]]) -> None:
    """对单个窗口做一次前向 + BIO 解码，把实体以「全局字符区间」追加进 runs。"""
    enc = _tokenizer(chunk, return_offsets_mapping=True, return_tensors="pt")
    offsets = enc.pop("offset_mapping")[0].tolist()
    # inference_mode 比 no_grad 更彻底（P2-15）：除关闭自动求导外，还跳过版本计数与
    # 视图追踪，前向的内存与少量算子开销都更低。本处只做前向 + softmax + argmax，
    # 不产生需要参与自动求导的张量，两者数值完全一致，可放心替换。
    with torch.inference_mode():
        logits = _model(**enc).logits[0]
    probs = torch.softmax(logits, dim=-1).tolist()
    labels = logits.argmax(-1).tolist()

    # 逐 token 做 BIO 解码：B 起新实体、I 续接同字段、其余收尾当前实体
    cur = None  # (field, start, end, confs)，start/end 为全局字符区间
    for ti, ((s, e), lid) in enumerate(zip(offsets, labels)):
        if s == e:  # special token
            continue
        tag, _, typ = _id2label.get(lid, "O").partition("-")
        field = LABEL_FIELD.get(typ)
        prob = probs[ti][lid]
        if tag == "B" and field:
            if cur:
                runs.append(cur)
            cur = (field, base + s, base + e, [prob])
        elif tag == "I" and cur and field == cur[0]:
            cur = (cur[0], cur[1], base + e, cur[3] + [prob])
        else:
            if cur:
                runs.append(cur)
            cur = None
    if cur:
        runs.append(cur)


def _merge_runs(runs: List[Tuple[str, int, int, List[float]]]) -> List[Tuple[str, int, int, List[float]]]:
    """按字段合并重复 / 重叠的实体（窗口重叠或整段重复时同一实体可能被抽到多次）。"""
    merged: List[Tuple[str, int, int, List[float]]] = []
    for field in FIELDS:
        for r in sorted((x for x in runs if x[0] == field), key=lambda x: (x[1], x[2])):
            if merged and merged[-1][0] == field and r[1] < merged[-1][2]:
                m = merged[-1]
                merged[-1] = (field, m[1], max(m[2], r[2]), m[3] + r[3])
            else:
                merged.append(r)
    return merged


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
