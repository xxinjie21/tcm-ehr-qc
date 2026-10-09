# AI 链路性能审查报告

> **审查范围**：AI 消费端（解读 / 问答 / 复核预检 / 术语补词建议）、LLM 调用与配置、NLP 单条抽取、NLP 批处理、Python 抽取服务、前端全部 AI 触点。逐文件读源码取证，给出文件与行号。
> **与既有报告的关系**：《性能优化与 Redis 应用审查报告》未审查 frontend 侧与 python-nlp 推理耗时，本报告补齐这两块及整条 AI 链路；其中 A3（术语建议排序）与 C3（offset 深翻页）为既有条目，本报告复核现状并扩展到 NLP 批处理。
> **结论标注**：共 28 项，P1×8、P2×13、P3×7。未修改任何源码。数据规模口径：4 万条。

---

## 一、总体结论

AI 链路的**降级设计是完备的**（规则兜底、失败返回 null、不阻塞主流程），性能问题集中在四处：

1. **LLM 配置解析每请求查 2 次库且缓存设计失效**（AI-1）——缓存键命中前就已执行 DB 查询。
2. **NLP 批处理的写路径是逐条 autocommit**（AI-8/AI-9/AI-10）——4 万条规模即 4 万次 UPDATE + 深翻页，量级最大。
3. **两处「长响应」仍同步阻塞或占关键路径**（AI-18/AI-19）——NLP 单条抽取未异步化；复核详情把 AI 生成 `await` 在页面 loading 之内。
4. **Python 推理是绝对瓶颈且无并发保护**（AI-12/AI-13）——FP32 CPU 单条数百毫秒，无量化、无批接口、无并发上限。

---

## 二、问题清单

> **状态图例**：【做】建议本期纳入实施（P1，零/低精度风险、改动可控）；【待定】尚未决策——需评估/实测/产品或技术确认（P2、⚠️ 项）；【不做】当前不做——储备、暂缓或已被现状/其它方案解决（P3）。最终以《未完成事项与验证缺口》落点为准。

### 2.1 Java · LLM 调用链路

#### AI-1（P1）配置解析每请求查 2 次库，`VersionedCache` 未省掉 DB 往返

- **位置**：`LlmClient.java:259`（`getFor`）、`:264`（`versionFor`）；`LlmConfigStore.java:73-79`、`:128-133`（`:77`/`:132` 各一次 `selectById`）
- **问题**：一次 `resolve()` 查两次库；且 `getFor` 里的 `selectById` 在 `perUser.get(...)`（`:78`）**之前无条件执行**——缓存只省 `merge()`，没省 DB 往返。放大点：`LlmClient.java:126` 降级分支再调一次 `getFor`；技术拒答分支 `isAvailable()`（`:107` → `resolve()` 又 2 次）。
- **优化思路**：`LlmConfigStore` 增加「一次查询同时返回配置与版本」的方法；缓存命中判断提前到 DB 查询之前。
- **预期收益**：每次 AI 请求省 1~3 次 `user_llm_config` 查询（表小，属纯冗余）。

#### AI-2（P1）`AiAsyncTasks` 线程池队列无界，提交不设上限

- **位置**：`AiAsyncTasks.java:69`（`newFixedThreadPool(4,...)`，内部 `LinkedBlockingQueue` 无界）
- **问题**：`submit` 永不拒绝；登记表上限 `MAX_TASKS` 只约束表，队列本身无界。LLM 单次 20s 超时、池 4 线程时，并发提交 100 个任务有 96 个堆在队列；前端 150s 放弃后后端仍会跑完。
- **优化思路**：显式 `ThreadPoolExecutor` + 有界队列 + 拒绝策略（拒绝时回 503「AI 繁忙」）。
- **附带**：`prune()`（`:82`，实现在 `:147-160`）每次 `submit` 全表 `removeIf` + 排序，n≤2000 可接受。

#### AI-28（P2）异步任务表在进程内，多实例下轮询 404

- **位置**：`AiAsyncTasks.java`（任务登记表 `ConcurrentHashMap`，实例内）；`AiController.java`（`GET /api/ai/async/{taskId}`）
- **问题**：A 实例提交、B 实例轮询查不到 → 404，前端看到「生成失败」而非「稍后重试」。这是本报告唯一让 AI 功能**整体不可用**的项。
- **现状**：与《性能优化与 Redis 应用审查报告》R14 同条结论（建议迁 Redis），此处从 AI 链路角度重述。
- **优化思路**：任务状态落 Redis（与 R14 合并），或粘性路由到同一实例。

#### AI-3（P2）`failedKeys` 无界增长（慢速内存泄漏）

- **位置**：`LlmClient.java:96`、`:283`；`LlmConfigStore.java` 版本每次 `saveFor` 自增
- **问题**：`failedKeys` 为 `ConcurrentHashMap.newKeySet()`，key=`userId|版本`；用户每保存一次配置且装配失败一次就留下永不清理的 key。
- **优化思路**：改用有界 LRU（复用 `VersionedCache`），或只保留每个 userId 最新版本。
- **预期收益**：消除长期运行下的慢速累积。

#### AI-4（P2）`interpret()` 重复计算核心缺失要素

- **位置**：`AiServiceImpl.java:102`（`coreMissing`）→ `:106`（`keyHints`）→ `keyHints` 内部 `:297`（**再算一次** `coreMissing`）
- **优化思路**：把已算的 `core` 作为参数传入 `keyHints`。
- **预期收益**：每次解读省一次要素判空（纯计算、零风险）。

#### AI-5（P2）`buildContext()` 重复查库与重复解析 JSON

- **位置**：`AiServiceImpl.java:356`（`buildContext`，`:360-361` 拼接 `standardBlock`/`currentRecordBlock`）；`:384`/`:403` 两方法各 `load(recordId)`（`:391`/`:408`）、各 `structured(r)`（`:393`/`:416`）
- **问题**：一个问题同时命中两块 = 2 次查库 + 2 次 JSON 解析。
- **优化思路**：块内 `load` 一次、`structured` 一次，结果向下传。
- **预期收益**：问答路径省 1 次 DB + 1 次 JSON 解析。

#### AI-6（P3）`TECH_KEYWORDS` 每次问答重复 `toLowerCase()`

- **位置**：`AiServiceImpl.java:327-328`
- **优化思路**：预置已小写的静态常量表。
- **预期收益**：极小，顺手项。

#### AI-7（P2）术语建议：排序比较器内重复计算相似度（既有 A3，代码仍未改）

- **位置**：`AiServiceImpl.java:737-746`（`recallCandidates`）、`:748-757`（`overlap`）、`:758-772`（`commonChars`）
- **问题**：`:744` 比较器每次比较调 2 次 `overlap`，`overlap`→`commonChars` 用 `indexOf`+`deleteCharAt` 逐字符（O(|a|·|b|)）；叠加每个输入词全量扫描，整体 O(m × n log n × 词长²)。
- **现状**：《性能优化与 Redis 应用审查报告》§二 A3 已记录（P2），代码中该写法仍在。
- **优化思路**：预计算分数数组，排序只比较预计算值；词表按类型缓存字符集。
- **预期收益**：`overlap` 调用从约 `2·n·log n` 降到 `n`（n=3000 时约 7 万 → 3 千）。

#### AI-26（P2）术语建议未按 50 条分批，批量导入页超过 50 条必 400

- **位置**：`DictionaryImport.vue`（`askSuggest`）；约束在 `AiQueryDTO.java`（`@Size(max = 50)`）
- **问题**：`importTerms` 为整个上传文件解析出的全部词条；>50 个词条点时「AI 建议」必 400，界面既不截断也不分批。顺带：`suggestTerms` 对每个词都做一次全词表扫描召回（见 AI-7），50 个词 = 50 次全表扫描。
- **优化思路**：前端按 50 分批串行调用并合并；或后端支持分批。
- **预期收益**：消除必然失败路径，单次请求词数受控。

### 2.2 Java · NLP 抽取与术语索引链路

#### AI-18（P1）`NlpController.extract` 同步阻塞，未享受 AI 侧的异步化

- **位置**：`NlpController.java:71`（`nlpClient.extract`）、`:89`（`entityNormalizer.normalize`）；前端 `api/nlp.js:5` 超时放宽到 60s
- **问题**：该端点同步完成「转发 Python（`nlp.timeout=30000ms`）+ ES 归一」才返回，Tomcat 请求线程被占住；并发若干条或 Python 变慢即可吃满线程。
- **对照**：`AiController` 已「提交拿任务号 + 轮询」（`AiController.java:40`），NLP 单条未跟进——同一项目两套口径。
- **优化思路**：复用 `AiAsyncTasks` 把单条抽取改异步提交 + 轮询。
- **预期收益**：长响应不再占请求线程，与 AI 侧口径统一。

#### AI-21（P2）归位判定按「另一种类型」查词典，不在预取缓存键内

- **位置**：`EntityNormalizer.java:234`（`hitsDictionary`，`moveMisplacedPulseTongue` 对症状实体依次按 symptom→pulse→tongue 查）；预取 `:336`（`prefetchRecall`）；缓存键 `EsTermIndexServiceImpl.java:191-195`
- **问题**：`prefetchRecall` 只按实体自身类型预取；归位判定用的 `pulse|org|raw`、`tongue|org|raw` 不在预取结果里 → 每个未命中词典的症状实体多 1~2 次 ES 往返。`RECALL_CACHE` 上限 4096，4 万条不同原文会挤爆，摊销有限。
- **优化思路**：把归位判定可能用到的类型一并预取，或单独批量查询。

#### AI-22（P2）`dice()` 每次调用新建两个 `HashSet<Character>`

- **位置**：`EsTermNormalizer.java:277-285`（`dice`，`:282`/`:284` 每次新建 Set）；调用点 `:248`/`:251`
- **问题**：三级模糊判定遍历最多 `RECALL_SIZE=50` 个候选 × 每个别名逐个 `dice`，未命中术语（数据里占多数）走满整轮。
- **优化思路**：先按长度差剪枝；用 `int[]` 计数或复用 buffer。
- **预期收益**：减少短命对象、降低 GC 压力（批处理下被放大）。

#### AI-27（P3）词典重建把所有词条塞进单个 `BulkRequest`，且每条新建 MD5 实例

- **位置**：`EsTermIndexServiceImpl.java:167-180`（bulk 一把梭）、`:405-415`（`hashId` 每条 `MessageDigest.getInstance` + `String.format`)
- **范围**：只在启动对账与词典导入重建时发生，非请求热路径 → P3。
- **优化思路**：bulk 分片提交；`hashId` 复用摘要器或改 `HexFormat`。
- **预期收益**：缩短启动/重建耗时，削内存峰值。

### 2.3 Java · NLP 批处理链路

#### AI-8（P1）`runByFilter` 使用 offset 分页，深翻页退化

- **位置**：`NlpBatchServiceImpl.java:685`（`selectPage(new Page<>(pageNo, PAGE_SIZE))`）、`:72`（`PAGE_SIZE=200`）
- **问题**：4 万条 = 200 页，末页 `LIMIT 39800, 200` 需扫过前 39800 行。
- **现状**：与《性能优化与 Redis 应用审查报告》C3 同源，但 C3 只覆盖清洗链路，批解析未覆盖。
- **优化思路**：复用 `RecordKeyset` 锚 `(visit_time DESC, id ASC)` 替代游标。
- **预期收益**：参照 C3 约 10–20×；需回归两个 NlpBatch 测试。

#### AI-9（P1）逐条 `UPDATE records`，无批处理

- **位置**：`NlpBatchServiceImpl.java:832`（`processOne` 调 `updateStructuredData`）；`RecordMapper.java` 单条 `@Update`
- **问题**：`runTask` 未标 `@Transactional`，每条各自 autocommit——4 万条 = 4 万次提交。
- **优化思路**：`rewriteBatchedStatements=true` + 批量更新；或按 N 条一事务。
- **预期收益**：写放大与 fsync 次数显著下降。

#### AI-10（P2）`submitIds` 循环单条 INSERT 明细

- **位置**：`NlpBatchServiceImpl.java:363-410`（循环 `nlpTaskItemMapper.insert`）
- **问题**：500 条导入即 500 次 INSERT（同事务）。
- **优化思路**：批量插入（`saveBatch` 或多值 INSERT）。
- **预期收益**：600 条往返降到个位数。

#### AI-11（P3）`claimNextTask` 空转轮询

- **位置**：`NlpBatchServiceImpl.java:80`（`POLL_INTERVAL_MS=1000`）
- **问题**：无待认领任务时每个 worker 每秒查一次库；并发 2 → 常态 2 QPS 空查，全天候。
- **优化思路**：空闲指数退避（1s→…→10s），提交时唤醒。
- **预期收益**：长期基线 DB 负载下降。

### 2.4 Python · 抽取服务

#### AI-12（P1）`extract` 为同步 `def`，推理无并发上限

- **位置**：`python-nlp/main.py:293-294`（`@app.post("/api/nlp/extract")` + `def extract`）
- **问题**：非 `async def` → FastAPI 投进 anyio 线程池（默认上限 40）；torch 推理是 CPU 密集，多实例或直压 8001 时 40 线程同时跑 → GIL 争用、内存翻倍。
- **优化思路**：`threading.Semaphore` 限并发；`torch.set_num_threads(...)` 避免 oversubscribe。
- **预期收益**：避免过载雪崩。

#### AI-13（P2）模型 FP32 CPU 推理，无量化 / 无 ONNX

- **位置**：`python-nlp/main.py:185`（`from_pretrained` 默认 FP32）、`184-187` 加载块
- **问题**：未指定 `torch_dtype`、未导出 ONNX、未量化；`_model.eval()` 有，无 `.to(device)`。单条推理数百毫秒，是批处理绝对瓶颈。
- **优化思路**（按收益/成本排序）：① `torch.inference_mode()`；② 动态量化（CPU 常见 2–4×）；③ ONNX Runtime；④ GPU + fp16。
- **预期收益**：单条推理成倍下降，直接决定 4 万条批处理总时长。

#### AI-14（P2）无批量抽取接口

- **位置**：`python-nlp/main.py:293`（单条接口）；`PythonNlpClient.java`（逐条 HTTP）
- **优化思路**：新增 `/api/nlp/extract-batch`，tokenizer padding 后一次前向。
- **预期收益**：往返由 N 降到 N/batch。

#### AI-15（P3）`HttpClient` 无连接池参数

- **位置**：`PythonNlpClient.java:48`（`HttpClient.newBuilder()` 只设版本与连接超时）
- **优化思路**：显式配置连接池与 Executor。
- **预期收益**：微小；当前仅 2 worker，暂无排队风险。

### 2.5 前端 · AI 触点

#### AI-16（P1）`runAiAsync` 固定 1s 轮询：慢任务请求过多、快任务白等 1s

- **位置**：`frontend/src/api/ai.js:32`（`POLL_INTERVAL_MS=1000`）、`:35`（`POLL_TIMEOUT_MS=150000`）、`:45`（`runAiAsync`）
- **问题**：固定间隔 → 一次 30s 生成即 30 次轮询；首次 poll 后必睡 1000ms，**LLM 默认关闭时后端毫秒级返回、前端却至少多等 ~1s**。
- **优化思路**：退避轮询（100ms→200ms→500ms→1s 封顶）；或 SSE / 长轮询。
- **预期收益**：慢任务请求降 3–5×；快任务延迟回毫秒级。

#### AI-19（P1）复核详情把 AI 生成 await 在页面 loading 之内

- **位置**：`ReviewDetailPanel.vue:2`（`v-loading`）、`:392`（`detailLoading=true`）、`:413`（`await runAiAsync('review',...)`）、`:427`（finally 置 false）
- **问题**：`detailLoading=true` → 拉详情 → `await runAiAsync('review', ...)` → finally 才置 false；loading 覆盖整个面板且文案是「正在读取复核详情…」。LLM 慢时复核员等最长 150s 才能看到扣分明细。
- **优化思路**：AI 建议与详情加载解耦，AI 结果异步补入（独立小 loading）。
- **预期收益**：首屏从「AI 生成耗时」回到「一次查库耗时」。

#### AI-20（P2）`NlpBatchPanel` 每 10s 同时拉「进度 + 整张任务列表」

- **位置**：`NlpBatchPanel.vue:205`（定时器）、`:136`（`poll`）、`:33`（`loadBatchList`）
- **问题**：每 tick 两个请求，任务列表全量重取最多 50 条。
- **已做对**：页面不可见暂停、切走页签停轮询。
- **优化思路**：任务列表降频（60s）或状态变化时再刷。
- **预期收益**：轮询请求量约减半。

#### AI-23（P2）报告页重跑轮询 3s × 最长 10 分钟 ≈ 200 次请求

- **位置**：`StandardizationReport.vue:283-284`（`POLL_MS=3000`、`POLL_TIMEOUT_MS=600000`）、`:389`（定时器）、`:861`（不可见暂停）
- **优化思路**：退避轮询；长任务降到 5–10s。
- **预期收益**：重跑期间请求量降 2–3×。

#### AI-17（P3）每轮重发最近 6 条消息全文，prompt 线性膨胀

- **位置**：`AiAssistant.vue:250-254`（`historyText`）
- **已做对**：后端 `AiQueryDTO` 有 `@Size(max = 8000)` 兜底。
- **优化思路**：每条历史截断（如 200 字）或只带最近 2–3 轮。
- **预期收益**：降 token 成本与首字延迟。

#### AI-24（P3）`NlpExtractDTO` 无长度上限，超长文本走完全程才被拒

- **位置**：`NlpExtractDTO.java`（仅 `@NotBlank`）；对照 `python-nlp/main.py:28` `MAX_TEXT_CHARS=20000`
- **问题**：超长文本被完整读入内存、序列化、发到 Python 才被 422 拒绝，最后降级为空 9 类。
- **优化思路**：`@Size(max = 20000)` 与 Python 对齐，提前 400。

#### AI-25（P3）`NlpController.extract` 与前端超时口径不一致

- **位置**：`api/nlp.js:5`（前端 60s）vs `application.yml`（后端 `nlp.timeout` 30s）
- **优化思路**：前端超时按「后端超时 + 余量」推导并注明来源。
- **预期收益**：口径一致，故障反馈准确。

---

## 三、优先级与预期收益汇总

| 状态 | 优先级 | 编号 | 问题 | 改动量 | 预期收益 |
|---|---|---|---|---|---|
| **做** | **P1** | AI-1 | LLM 配置解析每请求查 2 次库，缓存未生效 | 小 | 每请求省 1~3 次 DB 往返 |
| **做** | **P1** | AI-2 | 异步线程池队列无界，提交不设上限 | 小 | 消除任务堆积与延迟失控 |
| **做** | **P1** | AI-8 | 批解析 offset 深翻页（4 万条 200 页） | 中 | 参照 C3 约 10–20× |
| **做** | **P1** | AI-9 | 批解析逐条 UPDATE，无批处理 | 中 | 写放大与 fsync 大幅下降 |
| **做** | **P1** | AI-12 | Python 推理无并发上限 | 小 | 避免过载雪崩 |
| **做** | **P1** | AI-16 | AI 轮询固定 1s：慢任务请求多、快任务白等 | 小 | 请求降 3–5×，快任务回毫秒级 |
| **做** | **P1** | AI-18 | NLP 单条抽取同步阻塞，未异步化 | 中 | 长响应不再占请求线程 |
| **做** | **P1** | AI-19 | 复核详情把 AI 生成 await 在 loading 内 | 小 | 首屏从「AI 耗时」回到「一次查库」 |
| **待定** | **P2** | AI-28 | 异步任务表在进程内（多实例 404） | 小 | 多实例下 AI 异步可用（需评估 Redis 方案） |
| **待定** | **P2** | AI-3 | `failedKeys` 无界增长 | 小 | 消除慢速内存泄漏 |
| **待定** | **P2** | AI-4 | 解读重复计算核心缺失 | 极小 | 省一次要素判空 |
| **待定** | **P2** | AI-5 | 问答重复查库 + 重复解析 JSON | 小 | 省 1 次 DB + 1 次 JSON |
| **待定** | **P2** | AI-7 | 术语建议排序重复算相似度 | 中 | 排序阶段调用降约 20× |
| **待定** | **P2** | AI-26 | 术语建议未按 50 条分批，必然 400 | 小 | 消除必然失败路径 |
| **待定** | **P2** | AI-10 | `submitIds` 循环单条 INSERT | 小 | 500 条从 500 次往返降到个位数 |
| **待定** | **P2** | AI-13 | 模型 FP32 CPU，无量化 / ONNX | 中 | 单条推理成倍下降（需评测） |
| **待定** | **P2** | AI-14 | 无批量抽取接口 | 大 | 往返由 N 降到 N/batch（需产品确认） |
| **待定** | **P2** | AI-20 | 批解析面板每 10s 重取整张任务列表 | 小 | 轮询请求量约减半 |
| **待定** | **P2** | AI-21 | 归位判定按另一类型查词典，不在预取键内 | 小 | 省大量零散 ES 往返 |
| **待定** | **P2** | AI-22 | `dice()` 每次新建两个 HashSet | 小 | 降低 GC 压力 |
| **待定** | **P2** | AI-23 | 报告页重跑轮询 3s × 10 分钟 | 小 | 请求量降 2–3× |
| **不做** | **P3** | AI-6 | 关键词表重复 `toLowerCase` | 极小 | 微小（顺手可改，非本期） |
| **不做** | **P3** | AI-11 | 批解析空闲轮询 2 QPS | 小 | 基线 DB 负载下降（储备） |
| **不做** | **P3** | AI-15 | `HttpClient` 无连接池参数 | 小 | 补可调旋钮（储备） |
| **不做** | **P3** | AI-17 | 历史全文进 prompt | 小 | 降 token 成本与首字延迟 |
| **不做** | **P3** | AI-24 | 抽取入参无长度上限 | 极小 | 省无谓内存复制与往返 |
| **不做** | **P3** | AI-25 | 抽取超时前后端口径不一致 | 极小 | 故障反馈准确 |
| **不做** | **P3** | AI-27 | 词典重建单 bulk + 每条新建 MD5 | 小 | 缩短启动/重建耗时（储备） |

---

## 四、已经做对的部分（无需再优化）

- **输入有界**：`AiQueryDTO` 对 question / history / terms 分设 2000 / 8000 / 50 上限；`NlpBatchDTO.limit` `@Max(40000)`。
- **LLM 客户端按用户有界缓存**（`CLIENT_CACHE_MAX=20`，`LlmClient.java:92-93`），坏配置短路不反复建连。
- **统一不重试**：`NO_RETRY` / `maxRetries(0)`（`LlmClient.java:69`、`:352`），避免探测拖 80s。
- **长响应异步化**：提交拿任务号 + 轮询；捕获请求上下文（H4），修掉跨线程读到「未知」。
- **ES 召回有全局 LRU**（`RECALL_CACHE` 4096 条，`:82`）+ `_msearch` 批量预取（`:213`）。
- **批解析词典元数据按批只取一次**（`DictMeta` 惰性），4 万条由十几万次 SQL 降到 1 次。
- **NLP 批处理并发固定为 2**，不压垮 Python。
- **探测与抽取超时分离**：`nlp.probe-timeout` 2s / `nlp.timeout` 30s。
- **NLP 健康探测模块级单例 + 并发去重**（`useNlpStatus.js:16`）。
- **两处批量轮询都做「页面不可见即暂停」**（`NlpBatchPanel.vue`、`StandardizationReport.vue:861`）。
- **AI 解读卡片已去掉凭空加的 1s 假骨架延时**（`AiInterpretCard.vue:119-121` 注释记录）。
- **词典启动重建走分布式锁 + 版本落后才重建**（`DataInitializationListener.java`）。

---

## 五、未验证项

1. 所有「预期收益」均代码层推算，未在 4 万条真实数据上跑基准——尤其 AI-8/AI-9 批处理总耗时。
2. **AI-13 量化 / ONNX 收益未实测**（取决于模型结构 + CPU 指令集）。
3. **AI-2 队列堆积未在并发下复现**（结论来自 `newFixedThreadPool` 无界队列的实现事实）。
4. **AI-12 Python 并发退化未压测**。
5. **AI-16 轮询总量未统计**（取决于并发用户数与单次生成时长）。
6. **AI-19 实际等待时长未实测**（LLM 关闭时不显现）。
7. **AI-21 额外 ES 往返次数未统计**。
8. 本次未采集运行期指标（无缓存命中率埋点、无 AI 接口耗时分布）。
9. **AI-27 启动/重建耗时未实测**。
10. **AI-26 实际触发频率未统计**。

---

## 六、本次审查未触碰的部分

- 未修改任何源码（Java / Python / Vue / yml 只读）；未改动配置取值；未重启服务、未调用 AI 接口。
- 所有建议仅写在报告里；AI-13（量化 / ONNX）与 AI-14（批量接口）涉及模型与接口形态变更，需产品与技术确认。