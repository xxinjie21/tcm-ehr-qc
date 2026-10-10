# AI 链路性能审查报告

> **审查范围**：AI 消费端（解读 / 问答 / 复核预检 / 术语补词建议）、LLM 调用与配置、NLP 单条抽取、NLP 批处理、Python 抽取服务、前端全部 AI 触点。全仓只读，逐文件取证。
> **结论标注**：共 21 项，**全部纳入实施**（P1×8、P2×13）。数据规模口径 4 万条。
> **跨报告关系**：与《性能优化与 Redis 应用审查报告》《性能与算法审计报告》重叠的条目只登记一次，以「跨报告」指向；本报告独占 AI 链路侧结论。

---

## 一、总体结论

AI 链路的**降级设计是完备的**（规则兜底、失败返回 null、不阻塞主流程）。性能问题集中在四处：

1. **LLM 配置解析每请求查 2 次库且缓存设计失效**（AI-1）——缓存键命中前就已执行 DB 查询。
2. **NLP 批处理的写路径逐条 autocommit**（AI-8/AI-9/AI-10）——4 万条即 4 万次 UPDATE + 深翻页，量级最大。
3. **两处「长响应」仍同步阻塞或占关键路径**（AI-18/AI-19）——NLP 单条抽取未异步化；复核详情把 AI 生成 `await` 在页面 loading 之内。
4. **Python 推理是绝对瓶颈且无并发保护**（AI-12/AI-13）——FP32 CPU 单条数百毫秒，无量化、无批接口、无并发上限。

---

## 二、问题清单

> **实施批次**：P1 = 第一批（零/低风险、改动可控）；P2 = 第二批（需调整设计或引入新机制）。**本报告 21 项无保留项，全部落地。**

### 2.1 Java · LLM 调用链路

#### AI-1（P1）配置解析每请求查 2 次库，`VersionedCache` 未省掉 DB 往返

- **位置**：`LlmClient.java:259`（`getFor`）、`:264`（`versionFor`）；`LlmConfigStore.java:73-79`、`:128-133`（`:77`/`:132` 各一次 `selectById`）
- **问题**：一次 `resolve()` 查两次库；`getFor` 里的 `selectById` 在 `perUser.get(...)`（`:78`）**之前无条件执行**——缓存只省 `merge()`，没省 DB 往返。放大点：`LlmClient.java:126` 降级分支再调一次 `getFor`；技术拒答分支 `isAvailable()`（`:107` → `resolve()` 又 2 次）。
- **优化**：`LlmConfigStore` 增加「一次查询同时返回配置与版本」的方法；缓存命中判断提前到 DB 查询之前。
- **收益**：每次 AI 请求省 1~3 次 `user_llm_config` 查询。

#### AI-2（P1）`AiAsyncTasks` 线程池队列无界，提交不设上限

- **位置**：`AiAsyncTasks.java:69`（`newFixedThreadPool(4,...)`，内部 `LinkedBlockingQueue` 无界）
- **问题**：`submit` 永不拒绝；登记表上限 `MAX_TASKS` 只约束表，队列本身无界。LLM 单次 20s 超时、池 4 线程时，并发提交 100 个任务有 96 个堆在队列；前端 150s 放弃后后端仍会跑完。
- **优化**：显式 `ThreadPoolExecutor` + 有界队列 + 拒绝策略（拒绝时回 503「AI 繁忙」）。
- **附带**：`prune()`（`:148`）每次 `submit` 全表 `removeIf` + 排序，n≤2000 可接受。

#### AI-28（P2）异步任务表在进程内，多实例下轮询 404

- **位置**：`AiAsyncTasks.java`（登记表 `ConcurrentHashMap`，实例内）；`AiController.java:48`/`:93`（`/api/ai/async/{kind}`、`/{taskId}`）
- **问题**：A 实例提交、B 实例轮询查不到 → 404，前端看到「生成失败」而非「稍后重试」。这是本报告唯一让 AI 功能**整体不可用**的项。
- **优化**：任务状态落 Redis。与《性能优化与 Redis 应用审查报告》**R14** 同条结论，合并处置。

#### AI-3（P2）`failedKeys` 无界增长（慢速内存泄漏）

- **位置**：`LlmClient.java:96`、`:283`；`LlmConfigStore` 版本每次 `saveFor` 自增
- **问题**：`failedKeys` 为 `ConcurrentHashMap.newKeySet()`，key=`userId|版本`；用户每保存一次配置且装配失败一次就留下永不清理的 key。
- **优化**：改为**每个 userId 只保留最新失败版本**（`Map<userId, version>`，容量天然等于用户数）。不用有界 LRU——淘汰后旧版本会重新触发装配失败，产生重试风暴。

#### AI-4（P2）`interpret()` 重复计算核心缺失要素

- **位置**：`AiServiceImpl.java:102`（`coreMissing`）→ `:106`（`keyHints`）→ `:297`（**再算一次**）
- **优化**：把已算的 `core` 作为参数传入 `keyHints`。纯计算、零风险。

#### AI-5（P2）`buildContext()` 重复查库与重复解析 JSON

- **位置**：`AiServiceImpl.java:356`（`:360-361` 拼接 `standardBlock`/`currentRecordBlock`）；`:384`/`:403` 两方法各 `load(recordId)`（`:391`/`:408`）、各 `structured(r)`（`:393`/`:416`）
- **问题**：一个问题同时命中两块 = 2 次查库 + 2 次 JSON 解析。
- **优化**：块内 `load` 一次、`structured` 一次，结果向下传。

#### AI-7（P2）术语建议：排序比较器内重复计算相似度

- **位置**：`AiServiceImpl.java:743`（比较器）、`:748`（`overlap`）、`:759`（`commonChars`）
- **问题**：`:743` 比较器每次比较重算 2 次 `overlap`（内部逐别名 `commonChars`，O(|a|·|b|)）；外层每个输入词全扫，O(m × n log n × 词长²)。
- **优化**：预计算分数数组，排序只比较预计算值；词表按类型缓存字符集。
- **跨报告**：同《性能优化与 Redis 应用审查报告》**A3**；算法级替代（倒排索引）见《性能与算法审计报告》**S1**。

#### AI-26（P2）术语建议未按 50 条分批，批量导入页超过 50 条必 400

- **位置**：`DictionaryImport.vue`（`askSuggest`）；约束 `AiQueryDTO.java:50`（`@Size(max = 50)`）
- **问题**：`importTerms` 为整份上传文件解析出的全部词条；>50 个词点时「AI 建议」必 400，界面既不截断也不分批。50 个词 = 50 次全词表扫描召回（见 AI-7）。
- **优化**：前端按 50 分批串行调用并合并；后端 `/api/ai/term-suggest` 接口形态不变。

### 2.2 Java · NLP 抽取与术语索引链路

#### AI-18（P1）`NlpController.extract` 同步阻塞，未享受 AI 侧的异步化

- **位置**：`NlpController.java:71`（`nlpClient.extract`）、`:89`（`entityNormalizer.normalize`）；前端 `api/nlp.js:5` 超时放宽到 60s
- **问题**：该端点同步完成「转发 Python（`nlp.timeout=30000ms`）+ ES 归一」才返回，Tomcat 请求线程被占住；并发若干条或 Python 变慢即可吃满线程。
- **对照**：`AiController` 已「提交拿任务号 + 轮询」（`AiController.java:48`），NLP 单条未跟进——同一项目两套口径。
- **优化**：复用 `AiAsyncTasks` 把单条抽取改异步提交 + 轮询。

#### AI-21（P2）归位判定按「另一种类型」查词典，不在预取缓存键内

- **位置**：`EntityNormalizer.java:234`（`hitsDictionary`，`moveMisplacedPulseTongue` 对症状实体依次按 symptom→pulse→tongue 查）；预取 `:336`（`prefetchRecall`）；缓存键 `EsTermIndexServiceImpl.java:191-195`
- **问题**：`prefetchRecall` 只按实体自身类型预取；归位判定用的 `pulse|org|raw`、`tongue|org|raw` 不在预取结果里 → 每个未命中词典的症状实体多 1~2 次 ES 往返。`RECALL_CACHE` 上限 4096，4 万条不同原文会挤爆，摊销有限。
- **优化**：把归位判定可能用到的类型一并预取（一次 `_msearch`，复用既有 `EntityNormalizer.prefetchRecall`）；不新增批量查询接口。
- **跨报告**：《性能与算法审计报告》**T3**（清洗链路同类问题）互为补充。

#### AI-22（P2）`dice()` 每次调用新建两个 `HashSet<Character>`

- **位置**：`EsTermNormalizer.java:277-285`（`:282`/`:284` 每次新建 Set）；调用点 `:248`/`:251`
- **问题**：三级模糊判定遍历最多 `RECALL_SIZE=50` 个候选 × 每个别名逐个 `dice`，未命中术语（数据里占多数）走满整轮。
- **优化**：与《性能与算法审计报告》**S3** 合并处置——直接落**字符集位图**（`Dice = 2×popcount(a&b)/(popcount(a)+popcount(b))`，候选位图随词典版本预计算），不单独实现 `int[]` 计数。位图结果与现实现逐位相同，零精度损失。

### 2.3 Java · NLP 批处理链路

#### AI-8（P1）`runByFilter` 使用 offset 分页，深翻页退化

- **位置**：`NlpBatchServiceImpl.java:687`（`selectPage(new Page<>(pageNo, PAGE_SIZE, false))`）、`:72`（`PAGE_SIZE=200`）
- **问题**：4 万条 = 200 页，末页 `LIMIT 39800, 200` 需扫过前 39800 行。
- **跨报告**：与《性能优化与 Redis 应用审查报告》**C3** 同源（C3 覆盖清洗链路，本条扩展到批解析）。
- **优化**：复用 `RecordKeyset` 锚 `(visit_time DESC, id ASC)` 替代游标；需回归两个 NlpBatch 测试。

#### AI-9（P1）逐条 `UPDATE records`，无批处理

- **位置**：`NlpBatchServiceImpl.java:834`（`processOne` 调 `updateStructuredData`）；`RecordMapper` 单条 `@Update`
- **问题**：`runTask` 未标 `@Transactional`，每条各自 autocommit——4 万条 = 4 万次提交。
- **优化**：`rewriteBatchedStatements=true` + 每 500 条一次批量更新（MyBatis-Plus `updateBatchById`），取消逐条 autocommit。

#### AI-10（P2）`submitIds` 循环单条 INSERT 明细

- **位置**：`NlpBatchServiceImpl.java:363-410`（循环 `nlpTaskItemMapper.insert`，`:409`）
- **问题**：500 条导入即 500 次 INSERT（同事务）。**优化**：`saveBatch` 批量插入（每 500 条一批）。
- **跨报告**：同《性能与算法审计报告》**T9**。

### 2.4 Python · 抽取服务

#### AI-12（P1）`extract` 为同步 `def`，推理无并发上限

- **位置**：`python-nlp/main.py:293-294`（`@app.post("/api/nlp/extract")` + `def extract`）
- **问题**：非 `async def` → FastAPI 投进 anyio 线程池（默认上限 40）；torch 推理是 CPU 密集，多实例或直压 8001 时 40 线程同时跑 → GIL 争用、内存翻倍。
- **优化**：`threading.Semaphore` 限并发；`torch.set_num_threads(...)` 避免 oversubscribe。

#### AI-13（P2）模型 FP32 CPU 推理，无量化 / 无 ONNX

- **位置**：`python-nlp/main.py:184-187`（`from_pretrained` 默认 FP32）
- **问题**：未指定 `torch_dtype`、未导出 ONNX、未量化；`_model.eval()` 有，无 `.to(device)`。单条推理数百毫秒，是批处理绝对瓶颈。
- **优化（按收益/成本排序）**：① `torch.inference_mode()`；② 动态量化（CPU 常见 2–4×）；③ ONNX Runtime；④ GPU + fp16。直接决定 4 万条批处理总时长。

#### AI-14（P2）无批量抽取接口

- **位置**：`python-nlp/main.py:293`（仅单条接口）；`PythonNlpClient.java`（逐条 HTTP）
- **优化**：新增 `/api/nlp/extract-batch`，tokenizer padding 后一次前向。往返由 N 降到 N/batch。

### 2.5 前端 · AI 触点

#### AI-16（P1）`runAiAsync` 固定 1s 轮询：慢任务请求过多、快任务白等 1s

- **位置**：`frontend/src/api/ai.js:32`（`POLL_INTERVAL_MS=1000`）、`:35`（`POLL_TIMEOUT_MS=150000`）、`:45`（`runAiAsync`）
- **问题**：固定间隔 → 一次 30s 生成即 30 次轮询；首次 poll 后必睡 1000ms，**LLM 默认关闭时后端毫秒级返回、前端却至少多等 ~1s**。
- **优化**：退避轮询（100ms→200ms→500ms→1s 封顶）。不引入 SSE / 长轮询——前端已统一走轮询，新增推送通道要同时改 `AiAsyncTasks` 与两处批量面板，收益不抵改动面。慢任务请求降 3–5×，快任务延迟回毫秒级。

#### AI-19（P1）复核详情把 AI 生成 await 在页面 loading 之内

- **位置**：`components/ReviewDetailPanel.vue:2`（`v-loading`）、`:392`（`detailLoading=true`）、`:413`（`await runAiAsync('review',...)`）、`:427`（finally 置 false）
- **问题**：loading 覆盖整个面板且文案是「正在读取复核详情…」。LLM 慢时复核员等最长 150s 才能看到扣分明细。
- **优化**：AI 建议与详情加载解耦，AI 结果异步补入（独立小 loading）。首屏从「AI 生成耗时」回到「一次查库耗时」。

#### AI-20（P2）`NlpBatchPanel` 每 10s 同时拉「进度 + 整张任务列表」

- **位置**：`components/NlpBatchPanel.vue:205`（`setInterval`，间隔 10000ms）、`:164`（`loadBatchList`）
- **问题**：每 tick 两个请求，任务列表全量重取最多 50 条。**已做对**：页面不可见暂停、切走页签停轮询。
- **优化**：任务列表轮询降到 60s 一次。轮询请求量约减半。

#### AI-23（P2）报告页重跑轮询 3s × 最长 10 分钟 ≈ 200 次请求

- **位置**：`views/StandardizationReport.vue:283-284`（`POLL_MS=3000`、`POLL_TIMEOUT_MS=600000`）、`:393`（定时器）、`:861`（不可见暂停）
- **优化**：退避轮询；长任务降到 5–10s。请求量降 2–3×。

---

## 三、优先级与预期收益汇总

| 批次 | 编号 | 问题 | 改动量 | 预期收益 |
|---|---|---|---|---|
| 第一批（P1） | AI-1 | LLM 配置解析每请求查 2 次库，缓存未生效 | 小 | 每请求省 1~3 次 DB 往返 |
| 第一批（P1） | AI-2 | 异步线程池队列无界，提交不设上限 | 小 | 消除任务堆积与延迟失控 |
| 第一批（P1） | AI-8 | 批解析 offset 深翻页（4 万条 200 页） | 中 | 参照 C3 约 10–20× |
| 第一批（P1） | AI-9 | 批解析逐条 UPDATE，无批处理 | 中 | 写放大与 fsync 大幅下降 |
| 第一批（P1） | AI-12 | Python 推理无并发上限 | 小 | 避免过载雪崩 |
| 第一批（P1） | AI-16 | AI 轮询固定 1s：慢任务请求多、快任务白等 | 小 | 请求降 3–5×，快任务回毫秒级 |
| 第一批（P1） | AI-18 | NLP 单条抽取同步阻塞，未异步化 | 中 | 长响应不再占请求线程 |
| 第一批（P1） | AI-19 | 复核详情把 AI 生成 await 在 loading 内 | 小 | 首屏从「AI 耗时」回到「一次查库」 |
| 第二批（P2） | AI-3 | `failedKeys` 无界增长 | 小 | 消除慢速内存泄漏 |
| 第二批（P2） | AI-4 | 解读重复计算核心缺失 | 极小 | 省一次要素判空 |
| 第二批（P2） | AI-5 | 问答重复查库 + 重复解析 JSON | 小 | 省 1 次 DB + 1 次 JSON |
| 第二批（P2） | AI-7 | 术语建议排序重复算相似度 | 中 | 排序阶段调用降约 20×（同 A3/S1） |
| 第二批（P2） | AI-10 | `submitIds` 循环单条 INSERT | 小 | 500 条从 500 次往返降到个位数（同 T9） |
| 第二批（P2） | AI-13 | 模型 FP32 CPU，无量化 / ONNX | 中 | 单条推理成倍下降（按 §五 口径实测） |
| 第二批（P2） | AI-14 | 无批量抽取接口 | 大 | 往返由 N 降到 N/batch |
| 第二批（P2） | AI-20 | 批解析面板每 10s 重取整张任务列表 | 小 | 轮询请求量约减半 |
| 第二批（P2） | AI-21 | 归位判定按另一类型查词典，不在预取键内 | 小 | 省大量零散 ES 往返 |
| 第二批（P2） | AI-22 | `dice()` 每次新建两个 HashSet | 小 | 降低 GC 压力（同 S3） |
| 第二批（P2） | AI-23 | 报告页重跑轮询 3s × 10 分钟 | 小 | 请求量降 2–3× |
| 第二批（P2） | AI-26 | 术语建议未按 50 条分批，必然 400 | 小 | 消除必然失败路径 |
| 第二批（P2） | AI-28 | 异步任务表在进程内（多实例 404） | 小 | 与 R14 合并（Redis 方案） |

---

## 四、已经做对的部分（无需再优化）

- **输入有界**：`AiQueryDTO` 对 question / history / terms 分设 2000 / 8000 / 50 上限；`NlpBatchDTO.limit` `@Max(40000)`。
- **LLM 客户端按用户有界缓存**（`CLIENT_CACHE_MAX=20`，`LlmClient.java:92-93`），坏配置短路不反复建连。
- **统一不重试**：`NO_RETRY` / `maxRetries(0)`（`LlmClient.java:69`、`:352`），避免探测拖 80s。
- **长响应异步化**：提交拿任务号 + 轮询；`AiAsyncTasks.submit` 已改为捕获**值**（userId/orgId/role/orgRole）而非请求上下文对象，修掉跨线程读到「未知」。
- **ES 召回有全局 LRU**（`RECALL_CACHE` 4096 条，`EsTermIndexServiceImpl.java:82`）+ `_msearch` 批量预取（`:250`）。
- **批解析词典元数据按批只取一次**（`NlpBatchServiceImpl.DictMeta`，`:848`），3.5 万条由十几万次 SQL 降到 1 次。
- **NLP 批处理并发固定为 2**，不压垮 Python；探测与抽取超时分离（`nlp.probe-timeout` 2s、`nlp.timeout` 30s；前者为 `PythonNlpClient` 的 `@Value` 代码默认值，`application.yml` 未显式配置）。
- **NLP 健康探测模块级单例 + 并发去重**（`useNlpStatus.js:18`）。
- **两处批量轮询都做「页面不可见即暂停」**（`NlpBatchPanel.vue:206`、`StandardizationReport.vue:861`）。
- **AI 解读卡片已去掉凭空加的 1s 假骨架延时**（`AiInterpretCard.vue:119` 注释记录）。
- **词典启动重建走分布式锁 + 版本落后才重建**（`DataInitializationListener.java`）。

---

## 五、验证口径

**改造后需采集的度量（论文数据来源）**

| 编号 | 采集项 | 方法 |
|---|---|---|
| AI-1 / AI-3 / AI-5 | `user_llm_config` 查询次数 | 改造前后各发 100 次 AI 请求，比对 mapper 调用计数 |
| AI-2 / AI-16 / AI-20 / AI-23 | 前端轮询请求总数、任务端到端可见耗时 | 浏览器 Network 计数 + 任务提交与返回的时间戳差 |
| AI-8 / AI-9 | 4 万条批解析总耗时、SQL 条数 | 开启 `rewriteBatchedStatements` 前后各跑一次 |
| AI-12 / AI-13 / AI-14 | 单条与批量抽取 P50/P95、Python 进程 RSS 峰值 | 直压 8001 端口 + 调 `/extract/batch` |
| AI-18 | Tomcat 请求线程占用时长 | 改造前后各 50 并发单条抽取 |
| AI-19 | 复核详情首屏耗时 | 解耦前后各打开 20 次 |
| AI-21 | ES 检索请求计数 | 复用已有打点 `EsTermIndexServiceImpl:213` 的 `SEARCH_REQUESTS` |
| AI-26 | 「AI 建议」400 触发次数 | 上传 60 / 100 / 200 词条各一次 |

**必须闭环、不得跳过的三项**

1. **AI-13 量化 / ONNX 收益**（取决于模型结构与 CPU 指令集）：先落 `torch.inference_mode()` + 动态量化，再按 §2.4 顺序做 ONNX；在目标机型实测单条 P50/P95。
2. **AI-2 队列堆积**：用并发压测复现（现状结论来自 `newFixedThreadPool` 无界队列的实现事实）。
3. **AI-28 多实例行为**：起两个实例验证轮询不再 404。

**随改造一并补的观测能力**：当前运行期指标为零 —— 随 AI-1 / AI-3 补 Micrometer 计数器（AI 接口耗时分布 + LLM 客户端缓存命中率），否则改造收益无数据可写。

---

## 六、审查边界

- 审查期间未修改任何源码（Java / Python / Vue / yml 只读）；未改动配置取值；未重启服务、未调用 AI 接口。
- AI-13（量化 / ONNX）与 AI-14（批量接口）涉及模型与接口形态变更，**已决定实施**：AI-13 先落 `torch.inference_mode()` + 动态量化（零接口变更），随后做 ONNX（同批次内完成，顺序在后）；AI-14 新增 `/api/nlp/extract-batch`，**原单条接口保持不动**。
