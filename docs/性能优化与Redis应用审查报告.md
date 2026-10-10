# 性能优化与 Redis 应用审查报告

> **审查对象**：`java-backend`（Spring Boot 4.1 + MyBatis-Plus + Redis/Redisson + Elasticsearch）、`python-nlp`（FastAPI + RoBERTa）。全仓只读，代码结论附文件与行号。
> **数据规模基准**：目标数据集 **40,000 条病历**，`structured_data` 约 **1.13 KB/条**（合计约 45 MB）。行号对应工作区文件（含未提交 WIP）。
> **实测环境**：`windows-redis`（redis:7.4）、`windows-mysql`（mysql:9）、`windows-es`（7.17.22）；3.6 节为连真实实例查询所得。
> **跨报告关系**：与《AI链路性能审查报告》《性能与算法审计报告》重叠的条目只登记一次，以「跨报告」指向。

---

## 一、总体结论

性能改造**已经做得相当扎实**：列投影（避免 21 个 TEXT 列进堆）、keyset 游标替代 offset 分页、词频统计下推 SQL 聚合、清洗链路惰性分页、扣分聚合两段式扫描、术语未归一分段扣分、ES `_msearch` 批量召回、按需写入。

**剩余问题集中在五类**，均为「重复计算 / N+1 / 全量加载 / 索引失效 / 缓存双写时机」：

| 类别 | 问题数 | 最高单项收益 |
|---|---|---|
| A 重复计算与冗余解析 | 4 | 报告页每请求多解析约 28 万次 JSON（4 万条口径） |
| B 循环内查库（N+1） | 2（B1 已修复） | 成员列表每页 N+1 |
| C 全量加载与内存聚合 | 3 | 报告页全行加载约 45 MB 堆 |
| D SQL 与索引 | 1 | 报告指标可整体下推 SQL |
| **W 缓存双写一致性**（3.5） | **3** | **事务内失效缓存，脏值最长留存 60 秒** |

Redis 目前只在 4 个业务点（令牌版本、登录失败计数、看板词频、分布式锁）。现有 4 点里有 **2 个实际缺陷**（`KEYS` 阻塞、TTL 无抖动）、**1 个双写时机错误**（3 处事务内失效），另有 **9 个高价值场景未用 Redis**（R5–R12、R14），且**完全没有缓存命中率埋点**。部署侧见 3.6。

---

## 二、算法与数据处理流程优化

> **实施批次**：P0 = 第一批（零风险、收益确定）；P1 = 第二批（收益大、需回归）；P2 = 第三批（需调整设计）；P3 = 第四批（新增能力，收益累积）。**本报告全部条目均纳入实施，无保留项。**

### A 类 · 重复计算与冗余解析（收益最大）

#### A1 标准化报告：同一条病历 `structured_data` 解析 5 次、`qc_results` 解析 2 次

- **位置**：`StandardizationReportServiceImpl.java`（`recordsInDomain` `:155`；`structured`/`parseJson` `:252`）；七次独立遍历见 `report()` `:67` 后各方法（`byMonth:109`、`coverage:90`、`unmatched:95`、`normalizableRate:161`、`datasetShape:116`、`scoreDistribution:101`、`qcCoverage:163`）
- **问题**：同一条 1.13 KB JSON 反序列化 7 次（5 次是同一份 `structured_data`）。4 万条即约 28 万次解析，实际只要约 8 万次。
- **优化**：`report()` 入口一次遍历一次解析，抽扁平中间结构（每条只需 `visitTime/score/grade/manuallyEdited/symptoms{content,sourceText,normLevel}/qc.score`）——**不引入** `Map<recordId, Map<String,Object>>` 请求内解析缓存，那只是把同一份解析结果换个容器多存一遍。
- **收益**：解析量降到 **1/7**，约省 **4–10 秒**（4 万条规模，估算值，实测口径见 §六）。
- **跨报告**：同《性能与算法审计报告》**T2**。

#### A2 标准化报告：`symptomTerms()` 在同一请求内调用两次

- **位置**：`StandardizationReportServiceImpl.java:160-161`（`unmatched`/`normalizableRate` 各调 `new HashSet<>(symptomTerms())`）
- **优化**：提局部变量复用。改动 2 行，零风险。

#### A3 术语建议：排序比较器内重复计算相似度

- **位置**：`AiServiceImpl.java:743`（比较器）、`:748`（`overlap`）、`:759`（`commonChars`）
- **问题**：`:743` 排序比较器每次比较重算 2 次 `overlap`（内部逐别名 `commonChars`，O(|a|·|b|)）；外层每个输入词全扫，O(m × n log n × 词长²)。
- **优化**：`overlap` 分数预计算进数组，排序只比较预计算值；词表按类型缓存字符集。
- **收益**：排序阶段调用从约 `2·n·log n` 降到 `n`（n=3000 时约 7 万 → 3 千）。
- **跨报告**：同《AI链路性能审查报告》**AI-7**；算法级替代（倒排索引）见《性能与算法审计报告》**S1**。

#### A4 每个请求做两次 JWT 验签

- **位置**：`JwtInterceptor.java:74`（`isValid` 内部 parseToken 一次）与 `:78`（`parseToken` 又一次）；`JwtUtil.isValid` `:99`
- **问题**：`isValid` 内部已拿到 `Claims` 但只回 boolean，拦截器又完整解析一遍——HMAC+Base64+JSON 解析做两次，全站最热路径。
- **优化**：`JwtUtil` 增加 `parseAndVerify(token)` 一次返回 claims 与校验结果。**收益**：每请求省一次验签，全站线性收益。

---

### B 类 · 循环内查库（N+1）

> **B1 已修复**：`ReviewServiceImpl` 已改为「一次批量取本页 `recordId` 集合，未命中跳行」（`:112` 注释、`:113` `existingRecordIds`）——原「每页 20 次 `selectById`」的 N+1 已消除。B 类现存待办仅 B2/B3。

#### B2 登录路径：逐条查组织

- **位置**：`AuthServiceImpl.java:215`（`isOrgStopped` 调用点）、`:270`（定义）、`:280`（逐成员 `groupMapper.selectById`）
- **问题**：登录时 `selectList` 取该用户全部成员行，再对每一行 `selectById` 取组织。
- **优化**：`selectBatchIds` 一次取回成员行（**不改**成员查询的 JOIN 形态，避免动既有 SQL）。**收益**：查询数从 `1 + N` 降到 2。

#### B3 组织管理：循环内单条查

- **位置**：`OrgServiceImpl.java:492`（成员列表回填循环内 `userMapper.selectById(m.getUserId())`）；其余 `:106`（`myOrg` 单条）、`:131`（列表）非循环逐条
- **优化**：收集 `userId` → `selectBatchIds` 一次取回 → Map 回填。**收益**：成员列表 `1 + N` → 2。
- **跨报告**：同《性能与算法审计报告》**T4**。

---

### C 类 · 全量加载与内存聚合

#### C1 标准化报告：全表全行加载，无列投影

- **位置**：`StandardizationReportServiceImpl.java:358`（`recordsInDomain` → `selectList`）；调用点 `:155`
- **问题**：无投影 → 拉回 `records` 全部列（21 TEXT + 2 JSON + 标量），4 万条约 **45 MB 常驻堆**。对比：`RecordServiceImpl.searchRecords`（`:476`）与 `StatsServiceImpl.recordsFor`（`:191`）**都已做列投影**。
- **优化**：按实际消费投影（`structured_data/qc_results/visit_time/score/grade/chief_complaint/self_report/update_time/pattern/department`；`present_illness` 等大 TEXT 不需要）。⚠️ 改列必须同步维护 `RecordColumnNameGuardTest` 列名守卫。**收益**：堆占用 45 MB → 约 12 MB。

#### C2 看板扩展：全量加载 + Java 侧三遍聚合

- **位置**：`StatsServiceImpl.java:191`（`recordsFor`）、`:195/:221/:240`（`byMonth`/`byDept`/`dist`）
- **优化**：三块均标准 `GROUP BY`，可下推 SQL（`selectOverviewAll/Org`、`selectTermFreqMulti` 已有先例）。**收益**：回传行数从 4 万降到「月份+科室+5 桶」（约 30 行），网络与堆降 3 个数量级。
- **跨报告**：同《性能与算法审计报告》**S6**。

#### C3 清洗链路：offset 分页深翻页退化

- **位置**：`GovernanceServiceImpl.java:96`（`pagedRecords`）、`:221`（`CLEAN_PAGE_SIZE=1000`）、`:232`（`pagedRecords` 定义）
- **问题**：`LIMIT (N-1)*1000, 1000` 需重扫前 (N-1)*1000 行；4 万条累计扫描量约为实际行数的 **20 倍**。对照：`QcServiceImpl.deductionStats` 已用 keyset（实测 4.9s → 216ms，22×）。
- **优化**：复用 `RecordKeyset.anchorAfter(wrapper, cursorVt, cursorId)` 锚 `(visit_time, id)`。**收益**：扫描量 80 万 → 4 万行，约 **10–20 倍**。
- **跨报告**：同《AI链路性能审查报告》**AI-8**（AI-8 覆盖 NLP 批解析侧）。

---

### D 类 · SQL 与索引

#### D3 报告指标可整体下推 SQL

- **位置**：同 C1/C2（`StandardizationReportServiceImpl`、`StatsServiceImpl`）
- **优化**：`coverage`/`scoreDistribution`/`datasetShape` 均可部分下推；「主诉去数字去重计数」用 `COUNT(DISTINCT REGEXP_REPLACE(...))` 一条 SQL。**收益**：与 C1/A1 叠加后报告页 Java 侧再降一档。

---

## 三、Redis 应用审查

### 3.1 现状：Redis 只在 4 个业务点

| # | 位置 | Key | 用途 | 写入时机 | TTL |
|---|---|---|---|---|---|
| R1 | `JwtUtil.java` | `tcm:auth:ver:{userId}` | 令牌版本号，退出/改密使旧令牌失效 | `revokeAll` 时 `+1`（`:132`） | `expireHours+1` 小时 |
| R2 | `AuthServiceImpl.java:310` | `tcm:auth:fail:{username}` | 登录失败计数（跨实例防爆破） | 失败时 `INCR` | 首次失败设 15 分钟 |
| R3 | `StatsCache.java:36` | `cache:stats:{scope}:all:{md5(filters)}` | 看板 `/stats/all` 词频分布 | 未命中回填 | 固定 60 秒 |
| R4 | `DistLock.java:50` | `tcm:dict:rebuild:{type}:{org}` 等 | Redisson 分布式锁 | `runLocked` | 看门狗续期（默认 30s） |

另有 `tcm:startup:ping`（启动探活，TTL 10s，无业务含义）。**未启用 Spring Cache**（无 `@EnableCaching`/`@Cacheable`），全是手写 `VersionedCache`（进程内 LRU）——缓存不跨实例共享。

---

### 3.2 现有 4 个点的三防分析与缺陷

#### R1 令牌版本号

- **穿透**：从未作废过的用户无 key → 每次 miss；每个已登录请求多一次 Redis 往返。对策：写占位值 `"0"`（TTL 与令牌同寿命）。
- **雪崩**：`revokeAll` 写 key 的 TTL 固定 `expireHours+1`，批量登出让一批 key 同时过期；过期后 `versionMatches` 读到 `null` → `current == null → return true`（`JwtUtil.java:154-156`）——**旧令牌「复活」**。对策：**TTL 加随机抖动**（`expireHours+1` 小时 ± 0–10 分钟），并保持「TTL > 令牌寿命」不变式——过期时令牌本身已失效，故不构成复活。**不设 TTL 的方案不采用**：该实例 `maxmemory=0`（实测，见 3.6），无 TTL 的 key 会无限累积。
- **击穿**：活跃用户单键热点。对策：本地一级缓存 + 单飞。
- **⚠️ 降级方向**：`versionMatches` 在 Redis 异常时 `return true`（**:160-161 放行**），与 `DistLock` fail-closed 相反——刻意的「可用优先」，但 Redis 抖动期间旧令牌可用。该分支须单独打告警计数器（随 9.1 的 Micrometer 一并落）。

#### R2 登录失败计数

- **雪崩**：所有失败设固定 15 分钟 TTL，同窗口集中过期，攻击者可在过期瞬间续爆破。对策：TTL 抖动（15 ± 1–3 分钟）。
- **击穿**：单键（被保护对象本身），`INCR` 原子性已保证计数不丢。
- **⚠️ 原子性缺陷**：`increment`（`:310`）与 `expire` 是两条命令；`INCR` 成功 `EXPIRE` 失败则计数永久累积 → 账号无限期锁死。当前靠 `lockRemainingSeconds`（`:194`）「`getExpire` 返 -1 按窗口结束」兜底，属事后补救。对策：**Lua 脚本保证 `INCR` + `EXPIRE` 原子**（与 R12 限流共用同一套 Lua 工具），兜底逻辑保留不删。

#### R3 看板词频缓存（问题最集中）

- **穿透（最实际风险）**：key=`md5(filters)`，`FiltersDTO` 组合空间不受限 → 可构造无限 key，每个 miss 都触发一次全表 JSON 展开。后果：命中率趋零 + Redis 内存被无效 key 撑爆。对策：① key 空间白名单化（时间归一天、科室限枚举）；② 空值缓存（TTL 10s）；③ 接口限流。
- **雪崩（两重）**：①全部固定 60s TTL 同秒过期；②`invalidateForWrite()`（`:65`）按 scope 通配删除全部 key，一次写入后下一请求全量现算。对策：TTL 抖动（60±10s）；失效改**版本号前缀**（写入只 `INCR`，旧 key 自然过期）而非通配删除。
- **击穿**：管理员「看全部」热点 key，失效瞬间并发各自现算。对策：单飞（复用 `DistLock`，`SETNX lock:stats:{key}`）；不用逻辑过期——看板数据容忍秒级延迟，单飞实现更短。
- **🔴 严重缺陷：使用了 `KEYS`**（`:72` `redis.keys(KEY_PREFIX+scope+":*")`）——O(N) 全库扫描 + 单线程阻塞，期间所有其它请求（含令牌校验、分布式锁）排队。生产禁用级。对策：①改版本号前缀（推荐）；②`SCAN` 分批 DEL；③严禁继续用 `KEYS`。

#### R4 Redisson 分布式锁

- **雪崩**：所有锁走同一实例；Redis 不可用时 `DistLock` **fail-closed** 抛 `ServiceNotReadyException`（`:87`，503）——不误放行（对的），但**全站写不可用**。对策：哨兵/集群；非关键锁可本地降级但**不能降级为放行**。
- **⚠️ 可用性**：`tryLock(WAIT_SECONDS=3s)`（`:38` 常量、`:52` 调用）固定等 3 秒，高并发大量线程阻塞 3s 才失败。对策：`DistLock` 的等待时长改为**调用方传入**——批任务锁保留 3s（等不到即回「已有任务在跑」），在线写路径（病历导入 / 词典导入 / 规则保存）传 `0` 快速失败并回 409。

---

### 3.3 新增的 Redis 场景

| 批次 | 编号 | 场景 | Key 设计 | 落地口径 |
|---|---|---|---|---|
| 第四批（P3） | R5 | 组织解析结果缓存 | `cache:org:primary:{userId}` | 每请求省 1 次 DB；**成员增删 / 组织停用由写路径同步删 key**（语义仍为立即生效），TTL 30s 仅作兜底 |
| 第四批（P3） | R6 | 术语归一召回跨实例共享 | `cache:term:recall:{type}:{org}:{md5}` + 整类版本号 | 解决多实例命中率摊薄 + `rebuild` 只清本实例（`EsTermIndexServiceImpl.java:79`）；价值是**跨实例一致性** |
| 第四批（P3） | R7 | 词典词条缓存跨实例共享 | `cache:dict:terms:{org}:{type}:{version}` | 跨实例一致性从「最多迟 5 秒」变「主动失效即刻一致」 |
| 第四批（P3） | R8 | 标准化报告结果缓存 | `cache:std-report:{org}:{start}:{end}:{dictVersion}` | **必须带词典版本**；配合 A1/C1，报告页「秒级 → 首次秒级、后续毫秒级」；Value 设体积上限 |
| 第四批（P3） | R9 | 质控规则 / LLM 配置共享 | 每次访问回源读行比对版本（`QcRuleStore`/`LlmConfigStore`） | 正确性关键是失效不是 TTL，故**不设 TTL，纯写路径删 key** |
| 第四批（P3） | R10 | 幂等键提前短路 | `SETNX idem:{org}:{key}` | **DB 唯一键必须保留**，Redis 只是加速层 |
| 第四批（P3） | R11 | 批任务进度高频轮询 | `task:progress:{taskId}`（Hash） | 轮询读 Redis，DB 只最终落库；任务完成主动删 key |
| 第四批（P3） | R12 | 接口限流 / 防刷 | 登录 / 导入 / **AI 建议**（每次调 LLM 有成本） | Lua 滑动窗口（不用 `INCR`+`EXPIRE` 两条命令） |

**R14**：异步任务状态迁 Redis，**已决定实施**，详见 3.7.2。

---

### 3.4 缓存三防通用落地口径（本项目）

| 问题 | 首选策略 | 本项目适用点 |
|---|---|---|
| **穿透** | ① key 空间白名单化（治本）② 空值缓存 ③ 布隆过滤 | ①→R3、R8；②→R1、R5、R6、R7、R9；③→R6 |
| **雪崩** | ① TTL 随机抖动 ② 多级缓存 ③ 熔断降级 | ①→**R1、R2、R3 必须加**；②→R6、R7；③→R3、R1 已有降级需补监控 |
| **击穿** | ① 互斥重建（复用 `DistLock`）② 逻辑过期 ③ 单飞 | ①→R3、R5、R8；③→R6、R7 |

**两条硬规则**：
1. **禁用 `KEYS`**——`StatsCache.java:72` 必须改（版本号前缀优先，`SCAN` 次之）。
2. **降级方向逐点明确**——统计缓存读失败→现算（安全）；令牌版本读失败→放行（可用优先，需监控）；锁拿不到→503 不放行（安全）。新增点必须显式声明类别。

**第四条：写路径失效必须落在事务提交之后**（见 3.5）。三防解决读路径，双写一致性解决写路径，缺一不可。

---

### 3.5 缓存与数据库的双写一致性（W 类）

#### 3.5.1 本项目的一致性口径

所有 Redis 缓存走 **Cache-Aside**：读 miss → 查库 → 回填；写 → 改库 → 失效（`StatsCacheInvalidator.invalidateStats()`）。正确需同时满足三条：

| # | 规则 | 违反后果 |
|---|---|---|
| ① | 先改库，后失效缓存 | 先删缓存 → 并发读按旧库回填 → 改库后缓存仍是旧值 |
| ② | 失效必须在事务提交之后（`afterCommit`） | 事务未提交就清缓存，并发读按**未提交旧数据**回填 |
| ③ | 失效失败要有兜底 | 缓存永久脏 |

项目对 ①③ 都对了；**唯独 ② 有 3 处违反**。且项目已有正确范式：`DistLock.afterCommit(Runnable)`（`DistLock.java:114`）、`DictionaryTermStoreImpl.invalidateCachesAfterCommit`（`:147`）——**同库词典缓存已按正确姿势做，后加的统计缓存门面漏了**。

#### W1 数据清洗：`GovernanceServiceImpl.clean()` 在事务内失效缓存

- **位置**：`GovernanceServiceImpl.java:87`（`@Transactional`）、`:216`（`invalidateStats()`）
- **成因**：清洗事务逐页 UPDATE（未提交）→ `:216` 清空缓存而改动不可见 → 并发读 miss → 按旧数据现算回填 → 事务提交 → 缓存持旧值最长 60s。竞态窗口 = 清缓存到提交之间的毫秒级，非确定性。
- **澄清**：**事务回滚不是问题**；缺陷纯粹来自「清缓存发生在提交前」。
- **修复（一行）**：`:216` 改 `DistLock.afterCommit(StatsCacheInvalidator::invalidateStats)`。无活动事务时立即执行，单测直调路径不受影响。

#### W2 删除病历：`RecordServiceImpl` 两个删除入口在事务内失效缓存

- **位置**：`deleteRecords`（`:421` `@Transactional`、`:428` `invalidateStats()`）；`deleteByFilter`（`:452` `@Transactional(timeout=60)`、`:460` `invalidateStats()`）
- **后果**：并发读窗口内按「删除前旧数据」回填 → 提交后缓存统计着**已删病历**最长 60s。删除低频，但用户对「删了 4 万条图表数字没变」敏感。
- **修复**：同上，改用 `DistLock.afterCommit(...)`。
- **顺带（已正确无需改）**：`importRecords`（`:213`）、`createRecord`、`updateRecord`（`:411`）、`ReviewServiceImpl.review`、`QcBatchServiceImpl`（`:433`）、`NlpBatchServiceImpl`（`:570`）这 6 处调用都不在活动事务内，不构成问题。需要改的只有 W1/W2 3 个点。

#### W3 失效与「读-回填」的竞态（旧值写回，跨所有缓存点）

- **性质**：Cache-Aside 固有竞态，修好 W1/W2 后仍存在：读 R miss 现算 V1（未回填）→ 写 W 提交后按 afterCommit 清缓存 → R 把 V1 写回 → 缓存持旧值至 TTL。`StatsCache.wordFreq`（`:43`）正是此结构，loader 是 4 万行 JSON 展开（慢），窗口放大到秒级。当前唯一兜底是 60s TTL。
- **缓解（按推荐度）**：
  1. **版本号前缀（推荐，与 R3 的 `KEYS` 修复同源）**：key 改 `cache:stats:{scope}:v{n}:all:{md5}`，失效由「KEYS+DEL」变「INCR 版本号」，旧值回填落在旧版本 key 下不被读——**竞态自动消失，同时消掉 `KEYS` 阻塞与 key 空间失控**。
  2. **延迟双删**：写后失效 → 等 500ms–1s > 一次读回填耗时 → 再失效一次。简单但靠「等」，治标。
  3. **保留 TTL 兜底**（60s）：把「永久不一致」压成「最多 60 秒」，最后防线，务必保留。

---

### 3.6 Redis 部署处置

> 连真实实例查询所得（`windows-redis` redis:7.4），命令可复核。

**实测事实**

| 项 | 实测值 |
|---|---|
| `INFO keyspace` | `db0:keys=14049,expires=3`；**db1–db15 全空** |
| key 前缀构成 | 采样 6000 key，**5989 个前缀 `{flash_sale`**（秒杀/限流哈希标签）；本项目 key 仅 `tcm:auth:ver:*` 3 个、`cache:stats:*` 0 个 |
| 归属确认 | `grep "flash_sale\|seckill\|ratelimit" java-backend/src` 零命中 → 同实例跑着无关应用 |
| `maxmemory` / policy | `0` / `noeviction` |
| 持久化 | `appendfsync everysec`、`save 3600 1 300 100 60 10000` |
| `databases` / `timeout` | `16` / `0` |

**处置**

1. **换逻辑库隔离**：`application.yml:33` 的 `spring.data.redis.database` 由 `0` 改为 **`1`**（实测 db1–db15 全空，取最小可用号）。`StringRedisTemplate` 与 `RedissonClient` 共用这一份配置（`RedissonConfig.java:37` 的 `@Value("${spring.data.redis.database:0}")` → `:41` `setDatabase`），**一处改动即同时迁移缓存与分布式锁**。
2. **key 前缀统一 `tcm:ehr:`**：把 `StatsCache.java:36` 的 `KEY_PREFIX="cache:stats:"` 一并改掉（见 3.7.1 的 2.3），使本项目 key 在共享实例上具备可识别的独立命名空间。
3. **不新建容器 / 独立实例**，**不调 `maxmemory`、不改 `maxmemory-policy`**，**不要求运维侧改持久化**。
4. **禁止在共享实例上跑 `KEYS`**——`StatsCache.java:72` 必须改版本号前缀（见 R3-③）。

**残余风险（部署侧接受）**：同实例其它应用 key 爆炸或执行大命令时，仍可能拖垮实例 → 本项目 `DistLock` fail-closed → 全站写 503，根因不在本项目、排查易误判。换库只隔离 key 空间，不隔离资源；此项由运维侧接受，不列入本项目改造范围。

#### 附带观察：持久化与「缓存定位」不符

- **判断**：实例主要承担**缓存 + 分布式锁**时 AOF 意义有限（缓存可重算、锁有 TTL 兜底）却带来 fsync 开销；唯一「丢了会出事」的是令牌版本号 `tcm:auth:ver:*`（丢失后旧令牌复活）。
- **处置（已定）**：**令牌版本号迁 MySQL**（随用户行存 `token_version` 列，登录/登出/改密时更新并与 `JwtUtil` 校验比对），Redis 侧只保留缓存 + 锁，不再要求持久化可靠性。该实例与无关应用共享（见本节实测），无法保证其持久化，故不选「确保实例可靠」这一路。**本条不是缺陷，是配置与用途的匹配问题。**

---

### 3.7 编码规范符合性核对

> 以「Redis 程序层面问题清单」逐条核对。结论分四档：✅ 已符合 / ⚠️ 部分 / ❌ 违反 / ➖ 不适用。

#### 3.7.1 开发层面 30 条

**统计**：✅ 13 / ⚠️ 6 / ❌ 9 / ➖ 2。下表只列 ⚠️ 与 ❌（需处理的 15 条），✅/➖ 已符合，不逐条列出。

| # | 检查项 | 结论 |
|---|---|---|
| 1.1 | 穿透：空值缓存 / 布隆 | ❌ 无 |
| 1.2 | 击穿：互斥 / 逻辑过期 | ❌ 无 |
| 1.3 | 雪崩：TTL 抖动 / 熔断 | ⚠️ 降级有、抖动无 |
| 1.5 | 一致性：失效在事务提交后 | ❌ 3 处（见 3.5） |
| 1.6 | 失效失败重试 | ❌ 仅告警，靠 TTL |
| 2.3 | Key 前缀统一 | ⚠️ 5 处 `tcm:`、1 处 `cache:` |
| 2.4 | 禁 `KEYS`，用 `SCAN` | ❌ 用了 `KEYS` |
| 4.3 | 锁粒度 | ⚠️ 批任务锁为全局单键 |
| 4.4 | 多命令原子性用 Lua | ❌ `INCR`+`EXPIRE` 非原子 |
| 5.1 | 变更触发类不设 TTL | ⚠️ 词典留 5s TTL |
| 5.3 | 禁硬编码 TTL、统一配置 | ⚠️ 仅登录参数外置 |
| 6.2 | 降级不抛 500 | ⚠️ 读符合，锁刻意 fail-closed |
| 6.3 | 重试机制 | ❌ 无 |
| 7.1 | 连接池配置 | ❌ 未启用（无 `commons-pool2`，走 Lettuce 默认单连接多路复用） |
| 9.1 | 命中率埋点与指标上报 | ❌ 完全没有 |

**做得好的**：全仓只用 `StringRedisTemplate`，无 JDK 序列化、无 `@EnableCaching`；分布式锁规范（无手写 `SETNX`、看门狗续期、`isHeldByCurrentThread()` 校验）；读路径降级齐全（`StatsCache`/`AuthServiceImpl`/`JwtUtil:160-161` 均 try-catch 不抛 500）。

**需要留意的 4 条**：
- **2.3 Key 前缀不统一**——5 处 `tcm:*`，唯独 `StatsCache.KEY_PREFIX="cache:stats:"` 无 `tcm:` 前缀；「例外」恰好是 key 空间失控 + `KEYS` 那处。统一为 `tcm:ehr:`。
- **4.3 锁粒度与自述不一致**——`DistLock` 注释「不用全局单键」，`dictRebuildLock` 也带 `{type}:{org}`；但两个批任务提交锁是**全局单键**（`tcm:qc_batch:submit`、`tcm:nlp_batch:submit`）。临界区短，影响有限；提交逻辑变重时应按 org 分键。
- **6.2 与清单刻意冲突（非缺陷）**——`DistLock:87` 刻意 fail-closed 抛 503。锁不可用时降级放行 = 没有互斥；清单建议只适用缓存读。
- **9.1 命中率埋点缺失**——`StatsCache` 只有 `log.debug`；`pom.xml` 无 actuator/micrometer/prometheus。补 Actuator + Micrometer，在 `wordFreq` 命中/未命中各打计数器。

#### 3.7.2 可优化场景核对

| # | 清单场景 | 报告对应 | 结论 |
|---|---|---|---|
| 1 | 热点基础字典缓存 | R7 | 纳入实施 |
| 2 | 报表统计结果缓存 | R8 | 纳入实施 |
| 4 | 分布式锁 | R4 | 已落地 |
| 5 | 计数器 / 接口限流 | R12 | 纳入实施 |
| 7 | 临时业务状态缓存 | 新增 R14 | **纳入实施** |

**R14 临时业务状态缓存——`AiAsyncTasks` 多实例隐患**：
- **位置**：`AiAsyncTasks.java`（登记表 `ConcurrentHashMap` 进程内，`MAX_TASKS=2000`、`RESULT_TTL_SECONDS=600`）。
- **问题**：单实例 OK；多实例下 A 实例提交、B 实例轮询 → 查不到任务（「任务不存在」而非「还在跑」）。
- **落地**：**迁 Redis**（`task:ai:{taskId}` Hash，TTL 600s），并保留进程内表作一级缓存。同《AI链路性能审查报告》**AI-28**。

#### 3.7.3 禁止场景 3 条（反向核对）

| # | 禁止项 | 结论 |
|---|---|---|
| 1 | 强事务 / 多表关联复杂查询 | 未违反——全在 MySQL 事务内 |
| 2 | 不可丢失的核心数据 | ⚠️ 一处：令牌版本号 `tcm:auth:ver:*` |
| 3 | 超大单条文本 | 未违反；**约束**：R8 落地时 Value 必须设体积上限，避免 45 MB 塞进单 key |

#### 3.7.4 小结

清单核对完毕：开发 **✅13/⚠️6/❌9/➖2**；场景 **4 纳入实施（R7/R8/R12/R14）、1 已落地（R4）**；禁止 **2 未违反、1 一处例外**。

最优先三条：**2.3 Key 前缀统一**（顺手做，是 2.4 的根，且与 3.6 换库一并落地）、**9.1 命中率埋点**（否则缓存调优没有观测依据）、**R14 异步任务状态迁 Redis**（唯一「多实例下功能直接出错」）。

---

## 四、优先级与预期收益汇总

| 批次 | 编号 | 问题 | 改动量 | 预期收益 |
|---|---|---|---|---|
| 第一批（P0） | A1 | 报告页 JSON 重复解析 7 次/条（同 T2） | 中 | 解析量降到 1/7，省 4–10s |
| 第一批（P0） | C1 | 报告页全表全行加载（约 45 MB） | 小 | 堆占用降约 70% |
| 第一批（P0） | R3-③ | `StatsCache` 使用 `KEYS` 阻塞命令 | 小 | 消除实例级阻塞风险 |
| 第一批（P0） | W1 | 清洗在事务内失效缓存 | 1 行 | 消除最长 60s 脏读，零风险 |
| 第一批（P0） | W2 | 删除在事务内失效缓存 | 1 行 | 消除最长 60s 脏读，零风险 |
| 第二批（P1） | A2 | `symptomTerms()` 重复调用 | 2 行 | 省一次词表读取 |
| 第二批（P1） | A4 | 每请求双重 JWT 验签 | 小 | 全站每请求省一次验签 |
| 第二批（P1） | B2 | 登录路径逐条查组织 | 小 | 查询 1+N → 2 |
| 第二批（P1） | C3 | 清洗链路 offset 深翻页（同 AI-8） | 小 | 参照既有实测约 10–20× |
| 第二批（P1） | R3-①② | 统计缓存 TTL 无抖动 + key 空间失控 | 中 | 消除雪崩 + 穿透 |
| 第二批（P1） | W3 | Cache-Aside 旧值写回竞态 | 中 | 与 R3 修复同源，一处改动消三问题 |
| 第二批（P1） | 9.1 | 无缓存命中率埋点 | 小 | 缓存调优从「凭感觉」变「有数据」 |
| 第三批（P2） | A3 | 术语建议排序重复算相似度（同 AI-7/S1） | 中 | 排序阶段调用降约 20× |
| 第三批（P2） | B3 | 组织成员列表 N+1（同 T4） | 小 | `1+N`→2 |
| 第三批（P2） | C2 | 看板扩展 Java 侧三遍聚合（同 S6） | 中 | 回传行数降 3 个数量级 |
| 第三批（P2） | R1-①② | 令牌版本无 TTL 抖动 + 无 key 穿透 | 小 | 消除「旧令牌复活」+ 每请求往返 |
| 第三批（P2） | R2 | 登录计数非原子 + TTL 无抖动 | 小 | 消除「账号无限期锁死」 |
| 第三批（P2） | R14 | 异步任务状态在进程内（同 AI-28） | 小 | 多实例下轮询不再「任务不存在」 |
| 第三批（P2） | 2.3 | Key 前缀不统一（`cache:stats:` 无 `tcm:`） | 极小 | 前缀隔离，共享实例防撞 |
| 第四批（P3） | R5–R12 | 新增 8 个 Redis 场景 | 大 | 见 3.3 各条 |

---

## 五、实施顺序

1. **第一批（P0，零风险）**：**W1+W2**（各 1 行，`DistLock.afterCommit`，语义不变）；A1+C1 一起改（都动 `StandardizationReportServiceImpl`）；R3-③ 单独改 `StatsCache`。
2. **第二批（P1）**：2.3 前缀统一（与 3.6 的换库一并改，`application.yml` 一次动完）→ 9.1 埋点（后续调优的前提）→ A2/A4/B2 → R3-①② 与 W3（版本号前缀一处解决）。
3. **第三批（P2）**：A3、B3、C2、R1-①②、R2、R14。
4. **第四批（P3）**：按 3.3 的 Key 设计与落地口径依次加 R5–R12；**R8/R9 必须先有失效通道再开缓存**。
5. **回归依托**：C2/C3 的 SQL 下推与 keyset 改造收益大但需回归，项目已有 **75 个测试类**可依托。

---

## 六、验证口径

1. **预期收益数字为估算**——须在 4 万条真实数据上以 A1/C1 改造前后各计时一次报告接口，用实测值替换。
2. **换库生效核对 + 本项目自有 key 增长曲线**——`database` 改 `1` 后确认 `INFO keyspace` 的 db1 只含 `tcm:ehr:` 前缀 key、db0 中本项目 key 归零；此后按周采集 db1 的 key 数与 `used_memory`，验证「新增缓存点带 TTL」是否成立。
3. **`KEYS` 实际阻塞时长**——14049 键下影响小；改版本号前缀后补一次 10 万键量级的对比计时。
4. **W1/W2/W3 竞态**——结论来自代码时序分析；修后补「清洗中并发轮询看板」用例复现并确认已消除。
5. **ES 召回缓存跨实例不一致**——代码推导；R6 落地后起双实例验证「`rebuild` 后另一实例立即读到新结果」。
6. **前端性能与 python-nlp 推理耗时**——不在本次范围（见《AI链路性能审查报告》）；3.7 符合性核对基于代码阅读，改造落地后按同一张表复评一次。

---

## 七、本次审查未触碰的部分

- **未修改任何源码**：全程只读。工作区存在一批未提交 WIP（`StatsServiceImpl`/`RecordMapper`/`RecordFilter`/`RecordServiceImpl`/`GovernanceServiceImpl`/`Record`/`TermInput.vue` 等），是既有改动，本次没碰。
- **未改动业务逻辑与计算口径**：唯一涉及**语义变更**的是 R5——组织解析缓存加 TTL 30s，但成员增删 / 组织停用由写路径同步删 key，故「立即生效」语义不变，不依赖 TTL 到期。
- **未改动运行环境与构建配置**：3.6 节全部为只读查询（`CONFIG GET`/`INFO`/`--scan`）；3.7 节涉及 `pom.xml`/`application.yml` 的结论只写报告，未改文件。
