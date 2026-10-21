# NLP 服务性能提升计划（可执行版）

> **现状基线**：FastAPI + Monor/hwtcmner（RoBERTa base），CPU 推理，吞吐 ~7–10 条/s（32 核）。
> **范围**：只做纯代码改动，不需要人工标注、不需要训练模型、不需要你动手配置。
> **新增依赖**：`pip install onnxruntime`（一条命令，我来做）。

> **2026-10-17 更新**：任务 1 与任务 3 已实测否定，**不要执行**；任务 4 已完成（`56948a6`，两处加词已撤回 `97d5dd8`/`957b808`，只保留舌象 4 个锚）；理由与实测数字见各自小节。

---

## 任务 1：批量推理接口 —— 已作废（2026-10-17 实测否定）

> **结论：不要做。** 攒批在纯 CPU 上是**负优化**，而且会改变抽取结果。
>
> **实测环境**：32 核 CPU / torch 线程 4 / 推理信号量 8，模型 `Monor/hwtcmner`（RoBERTa base）。
>
> | 场景 | 吞吐 |
> |------|------|
> | 基线逐条，117 字短文本 | 11.5 条/s 串行、32.5 条/s 并发 8 |
> | 基线逐条，495 字长病历 | 2.7 条/s 串行、**7.0 条/s 并发 8** |
> | 攒批 batch = 8 / 16 / 32 × 并发 1 / 2 | **基线的 0.36~0.60x** |
>
> **根因**：① CPU 前向已吃满 4 线程，攒批把并发从 8 压到 1~2，吞吐反而下降；② batch 与单条走不同 BLAS 路径，浮点出现 ~1e-7 差异，argmax 在边界上抖动，**实体边界会变**。
>
> 因此本节「吞吐提升到 50+ 条/s」与「抽样 10 条结果一致」两条验收**均不成立**。
>
> **附带结论（省掉一次调参）**：参数扫描 torch{2,4,8,16} × 信号量{4,8,12,16}，其余组合全部 ≤ 0.98x，**现行 torch=4 / 并发=8 已是最优**，无需调参。

### 目标（原设想，已被实测证伪）
把单条请求改成攒批后一次前向，吞吐从 ~10 条/s 提升到 50+ 条/s。

### 改动文件
- `python-nlp/main.py`
- `java-backend/src/main/java/com/tcm/ehr/common/utils/PythonNlpClient.java`
- `java-backend/src/main/java/com/tcm/ehr/service/impl/NlpBatchServiceImpl.java`

### 具体步骤

#### 步骤 1.1：Python 侧攒批队列（main.py 顶部）

```python
import asyncio
from concurrent.futures import Future

BATCH_MAX_SIZE = 16
BATCH_MAX_WAIT_MS = 200

_batch_queue: "asyncio.Queue[Tuple[str, Future]]" = asyncio.Queue()
_batch_worker_started = False
```

#### 步骤 1.2：后台攒批 worker

```python
async def _batch_worker():
    loop = asyncio.get_event_loop()
    while True:
        batch = []
        first_text, first_future = await _batch_queue.get()
        batch.append((first_text, first_future))
        
        deadline = loop.time() + BATCH_MAX_WAIT_MS / 1000
        while len(batch) < BATCH_MAX_SIZE:
            remaining = deadline - loop.time()
            if remaining <= 0:
                break
            try:
                text, fut = await asyncio.wait_for(_batch_queue.get(), timeout=remaining)
                batch.append((text, fut))
            except asyncio.TimeoutError:
                break
        
        texts = [t for t, _ in batch]
        try:
            results = await loop.run_in_executor(None, _ner_batch_sync, texts)
            for (_, fut), res in zip(batch, results):
                if not fut.done():
                    fut.set_result(res)
        except Exception as e:
            for _, fut in batch:
                if not fut.done():
                    fut.set_exception(e)
```

#### 步骤 1.3：批量 NER 前向（同步函数）

```python
def _ner_batch_sync(texts: List[str]) -> List[Dict]:
    import torch
    
    if not texts:
        return []
    
    # 1. 切窗口
    all_windows = []
    for text_idx, text in enumerate(texts):
        if not text:
            continue
        for base, chunk in _ner_windows(text):
            all_windows.append((text_idx, base, chunk))
    
    if not all_windows:
        return [{} for _ in texts]
    
    # 2. 统一 tokenizer + padding
    chunks = [w[2] for w in all_windows]
    enc = _tokenizer(chunks, padding=True, truncation=True, 
                     max_length=NER_MAX_TOKENS, return_tensors="pt",
                     return_offsets_mapping=True)
    offsets = enc.pop("offset_mapping")
    
    # 3. 一次前向
    _apply_torch_threads(torch)
    with _infer_sem:
        with torch.inference_mode():
            logits = _model(**enc).logits
    
    import numpy as np
    logits_np = logits.numpy()
    probs_np = np.exp(logits_np) / np.expand_dims(np.sum(np.exp(logits_np), axis=-1), axis=-1)
    labels_np = np.argmax(logits_np, axis=-1)
    
    # 4. 按 batch 维度拆开，每条文本各自做 BIO 解码
    results: List[Dict] = [None] * len(texts)
    text_runs = {i: [] for i in range(len(texts))}
    
    for batch_idx, (text_idx, base, chunk) in enumerate(all_windows):
        batch_offsets = offsets[batch_idx].tolist()
        batch_labels = labels_np[batch_idx].tolist()
        batch_probs = probs_np[batch_idx].tolist()
        
        cur = None
        for ti, ((s, e), lid) in enumerate(zip(batch_offsets, batch_labels)):
            if s == e:
                continue
            tag, _, typ = _id2label.get(lid, "O").partition("-")
            field = LABEL_FIELD.get(typ)
            prob = batch_probs[ti][lid]
            if tag == "B" and field:
                if cur:
                    text_runs[text_idx].append(cur)
                cur = (field, base + s, base + e, [prob])
            elif tag == "I" and cur and field == cur[0]:
                cur = (cur[0], cur[1], base + e, cur[3] + [prob])
            else:
                if cur:
                    text_runs[text_idx].append(cur)
                cur = None
        if cur:
            text_runs[text_idx].append(cur)
    
    # 5. 每条文本合并窗口 + flush
    for text_idx, runs in text_runs.items():
        out = {f: [] for f in FIELDS}
        for field, start, end, confs in _merge_runs(runs):
            _flush(out, field, texts[text_idx], start, end, confs)
        results[text_idx] = out
    
    return results
```

#### 步骤 1.4：接口改造（都改成 async def）

```python
@app.post("/api/nlp/extract", response_model=ExtractResponse)
async def extract(req: ExtractRequest):
    text = (req.text or "").strip()
    resp = ExtractResponse(modelAvailable=_model is not None)
    
    if _model is not None:
        loop = asyncio.get_event_loop()
        fut = Future()
        await _batch_queue.put((text, fut))
        try:
            ner_result = await loop.run_in_executor(None, fut.result, 30)
            for f in FIELDS:
                getattr(resp, f).extend(ner_result.get(f, []))
        except Exception:
            logger.exception("[nlp] 批量推理失败，降级为规则兜底")
    
    rules = _rules(text)
    for f in ("tongueList", "pulseList", "causeList", "treatmentList"):
        getattr(resp, f).extend(rules[f])
    
    _reroute_pulse_tongue(resp)
    return resp
```

#### 步骤 1.5：Java 侧改造

`PythonNlpClient.java` 新增 `extractBatch(List<String> texts)` 方法，调 `POST /api/nlp/extract/batch`。

`NlpBatchServiceImpl.java` 改成每 16 条一组调 `extractBatch`，减少 HTTP 往返。

### 验收
- 发 100 条病历，算吞吐应该 > 50 条/s
- 抽样 10 条，结果和改造前一致

---

## 任务 2：ONNX Runtime 加速（P0）

### 目标
把模型转成 ONNX 格式，推理再快 2–3×。

### 改动文件
- `python-nlp/convert_to_onnx.py`（新增）
- `python-nlp/main.py`

### 具体步骤

#### 步骤 2.1：转换脚本

```python
# convert_to_onnx.py
import torch
from transformers import AutoModelForTokenClassification, AutoTokenizer

MODEL_DIR = "model"
ONNX_PATH = "model/model.onnx"

tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)
model = AutoModelForTokenClassification.from_pretrained(MODEL_DIR)
model.eval()

dummy = tokenizer("测试文本", return_tensors="pt")
print("输入 keys:", list(dummy.keys()))

torch.onnx.export(
    model,
    (dummy["input_ids"], dummy["attention_mask"]),
    ONNX_PATH,
    input_names=["input_ids", "attention_mask"],
    output_names=["logits"],
    dynamic_axes={
        "input_ids": {0: "batch", 1: "seq_len"},
        "attention_mask": {0: "batch", 1: "seq_len"},
        "logits": {0: "batch", 1: "seq_len"},
    },
    opset_version=14,
)
print("ONNX 导出完成:", ONNX_PATH)
```

#### 步骤 2.2：main.py 加 ONNX 分支

```python
_onnx_session = None

# _load_model() 里加载完 PyTorch 后：
onnx_path = os.path.join(MODEL_DIR, "model.onnx")
if os.path.isfile(onnx_path):
    try:
        import onnxruntime as ort
        _onnx_session = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
        logger.info("[nlp] ONNX 模型加载成功")
    except Exception as e:
        logger.warning("[nlp] ONNX 加载失败: %s", e)
```

`_ner_batch_sync()` 里的前向部分改成：
```python
if _onnx_session is not None:
    import numpy as np
    ort_inputs = {
        "input_ids": enc["input_ids"].numpy(),
        "attention_mask": enc["attention_mask"].numpy(),
    }
    logits_np = _onnx_session.run(None, ort_inputs)[0]
else:
    with torch.inference_mode():
        logits = _model(**enc).logits
    logits_np = logits.numpy()

probs_np = np.exp(logits_np) / np.expand_dims(np.sum(np.exp(logits_np), axis=-1), axis=-1)
labels_np = np.argmax(logits_np, axis=-1)
```

### 验收
- 启动日志显示「ONNX 模型加载成功」
- 抽样 20 条，抽取结果和改造前一致
- 压测吞吐应该比纯 PyTorch 快 2–3×

---

## 任务 3：Tokenizer 缓存 —— 已作废（2026-10-17 实测否定）

> **结论：不要做。** 收益上限 0.6%，且真实场景命中率≈0。
>
> **实测（单条耗时分解）**：tokenize 1.2ms + `_ntokens` 0.9ms + **模型前向 358.8ms**（完整 `_ner` 344.3ms）。tokenizer 相关合计 ≈2.1ms，只占单条耗时的 **0.6%**——即使全部缓存掉，上限收益也只有 0.6%。
>
> 另外 `lru_cache` 是按「相同文本」命中的，而真实批处理中每份病历文本互不相同，**命中率≈0**，连这 0.6% 也拿不到。
>
> 真正的耗时几乎全在模型前向（≈100%），该路径已确认到头（见任务 1 附带结论）。

### 目标（原设想，已被实测证伪）
相同文本的 tokenizer 结果缓存起来，省掉重复编码时间。

### 改动文件
- `python-nlp/main.py`

### 具体步骤

```python
from functools import lru_cache

@lru_cache(maxsize=1000)
def _encode_cached(text: str):
    if _tokenizer is None:
        return None
    return _tokenizer(text, return_offsets_mapping=True, return_tensors="pt")
```

替换 `_ntokens()` 和 `_split_long_piece()` 里的 tokenizer 调用。

### 验收
- 连续发两次相同文本，第二次快 10–20%

---

## 任务 4：规则兜底增强 —— 已完成（2026-10-17，`56948a6`，两处加词已撤回 `97d5dd8` / `957b808`）

> **实施结果**：按本节做了 2 处，**否决 2 处**（文档给的代码块照抄会造成回归），**撤回 1 处**（加错了类别）。
>
> | 文档提议 | 处理 | 依据 |
> |----------|------|------|
> | 舌象形态锚扩展 | **部分采纳**：只加 `有芒刺/剥苔/无苔/少苔` | `薄苔/厚苔/苔腻/苔滑` 首字是「苔」，`RULE_TONGUE` 已覆盖，重复登记是死分支 |
> | `边有` → `边有齿痕` | **否决** | 收窄后 `边有瘀斑` 一类整段丢掉；演示数据恰好只写「边有齿痕」，损失看不出来（隐蔽回归） |
> | `RULE_PULSE_POSITION` 加脉质字白名单 | **否决（回归）** | 「左尺无力」「左关尤甚」后缀不在那 16 字内，实测脉象 144 → 120 条次（**-16.7%**） |
> | 病因词表 +6 词 | **撤回** | 见下 |
> | 治法补 `法宜/当予` | **撤回** | `treatments.json` 的 1186 条里没有以这两个词为 `standardTerm`/`aliases` 的条目；`当予` 另有「应当予以复查」的误报路径；演示数据 0 命中，无数据可验 |
>
> **两处加词为什么都撤回**：判据同一条 —— **在词典里找不到落点就不要加**。病因那 6 词是「属于别的类」（证候/病名），治法这 2 词是「不属于任何类」；两者都是凭空扩表。
>
> **病因词表 +6 词撤回依据**：那 6 个词在词典里的身份不是「病因」——`肝郁` 是 `patterns.json` 里 `肝气郁结` 的别名、`食积` 是 `diseases.json` 的病名、`脾虚/肾虚/火热/水饮` 只作为 `patterns.json` 证候名的组成部分出现。它们该由**证候/病名**两路归一收走（那两条有词典），再当病因抽一遍是**跨类重复计数**；而 `cause` 在 `EntityTypes.java:79` 是 `dict=false`（无词典、不归一），本就不是给这类词准备的。
> 判据（已写进代码）：加词前先 grep `java-backend/src/main/resources/dictionaries/`，**若该词已是某条目的 `standardTerm` 或 `aliases`，就说明它属于那个类，不要往病因表加**。对照数字：原有 17 词在全部 8 本词典里**精确命中 0/17**（只作为更长词条的片段出现），而新增 6 词里 `肝郁`/`食积` 是 **2/6** 精确命中 —— 这个差异就是分界线。
>
> **实测（三层）**：① `run_tests.py` 2/2、`mvn -o test` 557/0/0；② 模块级——13 个常量里只有 `RULE_TONGUE_MORPH` 与任务 4 之前不同（恰好那 4 个锚），`_rules`/`_is_pulse_tongue`/`_reroute_pulse_tongue`/`_append_unique_by_content` 四个函数体源码逐字相同，`RULE_CAUSE_WORDS` 17 词逐项相同；③ 端到端——500 行演示数据 vs 任务 4 之前（`5a4b5e9`），四字段条次 + 内容 + 逐行元组 **500/500 行完全一致，零差异**。
>
> **⚠️ 本节验收标准不成立**：「抽取数量应该多 20–30%」做不到 —— 撤回两处加词后，任务 4 的全部改动只剩舌象 4 个锚，而它们在这份数据上**零命中**。原因是这批数据的苔质写成 `苔薄白`/`苔白腻` 这类以「苔」起头的段（已被 `RULE_TONGUE` 覆盖），脉位后缀是 `无力`/`尤甚`，治法压根不存在。舌象 4 个锚的价值在于 `tongues.json` 里**已有归一目标**（`有芒刺`→`舌芒刺`、`无苔`/`少苔`→`剥落苔`/`苔无`/`苔少`、`剥苔`→`花剥苔`/`光剥苔`），锚让这些段能被抽出后归一；要真验需要一批含 `剥苔/无苔/少苔/有芒刺` 写法的数据。

### 目标
扩展舌象/脉象/病因/治法的规则覆盖，减少模型漏抽时的空结果。

### 改动文件
- `python-nlp/nlp_rules.py`（2026-10-17 更正：拆分后规则已不在 `main.py`）
- `python-nlp/tests/test_batch20_rules.py`（新增 15 条用例）

### 具体步骤

```python
# 舌象形态锚扩展
RULE_TONGUE_MORPH = re.compile(
    r"(?:或)?(?:边有齿痕|有裂纹|有瘀点|有瘀斑|有芒刺|剥苔|无苔|少苔|薄苔|厚苔|苔腻|苔滑)[^，。、；;\s]{0,6}"
)

# 脉象规则扩展
RULE_PULSE_POSITION = re.compile(
    r"(?:左|右)(?:寸|关|尺)(?:浮|沉|迟|数|虚|实|滑|涩|洪|细|弦|紧|缓|弱|微|濡)[^，。、；;\s]{0,4}"
)

# 病因词表扩展
RULE_CAUSE_WORDS = [
    "风寒", "风热", "暑湿", "风湿", "湿热", "寒湿", "气虚", "血虚", "阴虚", "阳虚",
    "情志", "饮食", "劳倦", "外伤", "痰", "瘀", "外感",
    "肝郁", "脾虚", "肾虚", "火热", "水饮", "食积",
]

# 治法规则扩展
RULE_TREATMENT = re.compile(r"(?:治[以法]?|法宜|当予)[^，。；;\s]{1,10}")
```

### 验收
- 拿 100 条病历测试，舌象/脉象/病因抽取数量应该多 20–30%
- 抽样检查无误报

---

## 执行顺序

1. ~~**任务 1（批量推理）** → 先做，收益最大~~ —— **已作废**（实测 0.36~0.60x 负优化）
2. **任务 2（ONNX）** → 一起改推理路径
3. ~~**任务 3（Tokenizer 缓存）** → 顺手做~~ —— **已作废**（收益上限 0.6%）
4. **任务 4（规则增强）** → **已完成**（2026-10-17，`56948a6`；其中 2 处提议被否决、两处加词已撤回 `97d5dd8`/`957b808`，最终只保留舌象 4 个锚，见该节）

---

## 验收指标

- [x] ~~批量吞吐：> 50 条/s（当前 ~10 条/s）~~ —— **作废**：实测长病历并发 8 为 7.0 条/s，攒批后反而降到 0.36~0.60x
- [ ] 单条延迟：< 200ms（当前 ~500ms）
- [ ] 抽取结果和改造前一致（抽样对比）
