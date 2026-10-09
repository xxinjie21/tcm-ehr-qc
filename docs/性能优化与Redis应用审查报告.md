# 性能优化与 Redis 应用审查报告

审查对象：`java-backend`（Spring Boot 4.1 + MyBatis-Plus + Redis/Redisson + Elasticsearch）、`python-nlp`（FastAPI + RoBERTa）
审查方式：**全仓只读**。代码结论附文件与行号；部署结论附实测命令与返回值。
数据规模基准：目标数据集 **40,000 条病历（以下按 4 万条口径）**，`structured_data` 约 **1.13 KB/条**（合计约 45 MB）。
审查基线：当前工作区（含未提交 WIP）。行号对应工作区文件。
实测环境：`windows-redis`（redis:7.4）、`windows-mysql`（mysql:9）、`windows-es`（7.17.22）。3.6 节为连真实实例查询所得。

---

## 一、总体结论

性能改造**已经做得相当扎实**：列投影（避免 21 个 TEXT 列进堆）、keyset 游标替代 offset 分页、词频统计下推 SQL 聚合、清洗链路惰性分页、扣分聚合两段式扫描、术语未归一分段扣分、ES `_msearch` 批量召回、**按需写入（清洗/导出路径已做到；批解析路径仍逐条 UPDATE，见 AI-9）**。

**剩余问题集中在六类**，均为「重复计算 / N+1 / 全量加载 / 索引失效 / 缓存双写时机 / Redis 部署缺陷」：

| 类别 | 问题数 | 最高单项收益 |
|---|---|---|
| A 重复计算与冗余解析 | 4 | 报告页每请求多解析约 28 万次 JSON（4 万条口径） |
| B 循环内查库（N+1） | 2（B1 已修复） | 成员列表每页 N+1 |
| C 全量加载与内存聚合 | 3 | 报告页全行加载约 45 MB 堆 |
| D SQL 与索引 | 3 | LIKE 前缀通配无法走索引 |
| **W 缓存双写一致性**（3.5） | **3** | **事务内失效缓存，脏值最长留存 60 秒** |
| **O Redis 部署与可靠性**（3.6） | **3** | **实例被共享 + 无内存上限，可致全站写不可用** |

Redis 目前只在 4 个业务点（令牌版本、登录失败计数、看板词频、分布式锁）。现有 4 点里有 **2 个实际缺陷**（`KEYS` 阻塞、TTL 无抖动）、**1 个双写时机错误**（3 处事务内失效），另有 **9 个高价值场景未用 Redis**（R5–R12、R14），且**完全没有缓存命中率埋点**。

---

## 二、算法与数据处理流程优化

> **状态图例**：【做】建议本期纳入实施（P0–P1，零/低精度风险、改动可控）；【待定】尚未决策——需评估/实测/产品与运维确认（P2、预留项、⚠️ 项）；【不做】当前不做——储备、暂缓或已被现状/其它方案解决（P3、明确不采纳）。最终以《未完成事项与验证缺口》落点为准。

### A 类 · 重复计算与冗余解析（收益最大）

#### A1 标准化报告：同一条病历 `structured_data` 解析 5 次、`qc_results` 解析 2 次

- **位置**：`StandardizationReportServiceImpl.java`（`recordsInDomain` `:155`；`structured`/`parseJson` `:252`）；七次独立遍历见 `report()` `:67` 后各方法（`byMonth:109`、`coverage:90`、`unmatched:95`、`normalizableRate:161`、`datasetShape:116`、`scoreDistribution:101`、`qcCoverage:163`）
- **问题**：同一条 1.13 KB JSON 反序列化 7 次（5 次是同一份 `structured_data`）。4 万条即约 28 万次解析，实际只要约 8 万次。
- **优化思路**：`report()` 入口一次遍历一次解析，抽扁平中间结构（每条只需 `visitTime/score/grade/manuallyEdited/symptoms{content,sourceText,normLevel}/qc.score`）；或用 `Map<recordId, Map<String,Object>>` 做请求内解析缓存。
- **预期收益**：解析量降到 **1/7**，约省 **4–10 秒**（4 万条规模）。**未实测。**

#### A2 标准化报告：`symptomTerms()` 在同一请求内调用两次

- **位置**：`StandardizationReportServiceImpl.java:161-162`（`unmatched`/`normalizableRate` 各调 `new HashSet<>(symptomTerms())`）
- **优化思路**：提局部变量复用。改动 2 行，零风险。

#### A3 术语建议：排序比较器内重复计算相似度

- **位置**：`AiServiceImpl.java:737-746`（`recallCandidates`）、`:748-757`（`overlap`）、`:758-772`（`commonChars`）
- **问题**：`:744` 排序比较器每次比较重算 2 次 `overlap`（内部逐别名 `commonChars`，O(|a|·|b|)）；外层每个输入词全扫，O(m × n log n × 词长²)。
- **优化思路**：`overlap` 分数预计算进数组，排序只比较预计算值；词表按类型缓存字符集。
- **预期收益**：排序阶段调用从约 `2·n·log n` 降到 `n`（n=3000 时约 7 万 → 3 千）。

#### A4 每个请求做两次 JWT 验签

- **位置**：`JwtInterceptor.java:74`（`isValid` 内部 parseToken 一次）与 `:78`（`parseToken` 又一次）；`JwtUtil.isValid` `:99`
- **问题**：`isValid` 内部已拿到 `Claims` 但只回 boolean，拦截器又完整解析一遍——HMAC+Base64+JSON 解析做两次，全站最热路径。
- **优化思路**：`JwtUtil` 增加 `parseAndVerify(token)` 一次返回 claims 与校验结果。
- **预期收益**：每请求省一次验签，全站线性收益。

---

### B 类 · 循环内查库（N+1）

> ⚠️ **B1 已修复（本次复核确认）**：`ReviewServiceImpl` 已改为「一次批量取本页 `recordId` 集合，未命中跳行」——原「每页 20 次 `selectById`」的 N+1 已消除（`:112` 注释「改为一次批量只取 id 集合」、`:113` `existingRecordIds`）。B 类现存待办仅 B2/B3。

#### B2 登录路径：逐条查组织

- **位置**：`AuthServiceImpl.java:216/282-286`（`isOrgStopped` 逐成员 `selectById`，`：280`）
- **问题**：登录时 `selectList` 取该用户全部成员行，再对每一行 `groupMapper.selectById(m.getOrgId())`。
- **优化思路**：`selectBatchIds` 一次取回或在成员查询 JOIN `organizations.status`。
- **预期收益**：查询数从 `1 + N` 降到 2。登录低频但延迟敏感。

#### B3 组织管理：循环内单条查

- **位置**：`OrgServiceImpl.java:376-377`（成员列表回填循环内 `userMapper.selectById`）；其余 `:97`（`myOrg` 单条）、`:119`（列表）非循环逐条
- **优化思路**：收集 `userId` → `selectBatchIds` 一次取回 → Map 回填。
- **预期收益**：成员列表 `1 + N` → 2。

---

### C 类 · 全量加载与内存聚合

#### C1 标准化报告：全表全行加载，无列投影

- **位置**：`StandardizationReportServiceImpl.java:358`（`recordsInDomain` → `selectList`）；调用点 `:155`
- **问题**：无投影 → 拉回 `records` 全部列（21 TEXT + 2 JSON + 标量），4 万条约 **45 MB 常驻堆**。对比：`RecordServiceImpl.searchRecords`（`:476`）与 `StatsServiceImpl.recordsFor`（`:191`）**都已做列投影**。
- **优化思路**：按实际消费投影（`structured_data/qc_results/visit_time/score/grade/chief_complaint/self_report/update_time/pattern/department`；`present_illness` 等大 TEXT 不需要）。⚠️ 改列必须同步维护 `RecordColumnNameGuardTest` 列名守卫。
- **预期收益**：堆占用 45 MB → 约 12 MB。

#### C2 看板扩展：全量加载 + Java 侧三遍聚合

- **位置**：`StatsServiceImpl.java:191`（`recordsFor`）、`:195/:221/:240`（`byMonth`/`byDept`/`dist`）
- **优化思路**：三块均标准 `GROUP BY`，可下推 SQL（`selectOverviewAll/Org`、`selectTermFreqMulti` 已有先例）。
- **预期收益**：回传行数从 4 万降到「月份+科室+5 桶」（约 30 行），网络与堆降 3 个数量级。

#### C3 清洗链路：offset 分页深翻页退化

- **位置**：`GovernanceServiceImpl.java:96`（`pagedRecords`）、`CLEAN_PAGE_SIZE=1000`
- **问题**：`LIMIT (N-1)*1000, 1000` 需重扫前 (N-1)*1000 行；4 万条累计扫描量约为实际行数的 **20 倍**。对照：`QcServiceImpl.deductionStats` 已用 keyset（实测 4.9s → 216ms，22×）。
- **优化思路**：复用 `RecordKeyset.anchorAfter(wrapper, cursorVt, cursorId)` 锚 `(visit_time, id)`。
- **预期收益**：扫描量 80 万 → 4 万行，约 **10–20 倍**。

---

### D 类 · SQL 与索引

#### D1 列表检索的前缀通配 LIKE

- **位置**：`RecordFilter.java:62/65`（`like("registration_no"...)`/`like("outpatient_no"...)`）
- **问题**：`LIKE '%值%'` 前导通配 → 索引用不上，退化为全表扫描。当前 4 万行可接受，属「规模一涨就崩」。
- **优化思路**：登记号/门诊号是精确标识符，语义上该 `eq`；要模糊则前缀匹配走索引。

#### D2 证候检索在 JSON 列上做 LIKE

- **位置**：`RecordFilter.java:76-77`（`like("pattern",...)`/`like("structured_data",...)`）
- **问题**：JSON 列 LIKE 无法索引；源码注释已声明低频（实测比单列 LIKE 多约 60%）。
- **优化思路（规模增长后再做）**：MySQL 多值索引，或抽证候标准词冗余列 + 普通索引。
- **预期收益**：当前规模收益有限，预留项。

#### D3 报告指标可整体下推 SQL

- **位置**：同 C1/C2（`StandardizationReportServiceImpl`、`StatsServiceImpl`）
- **优化思路**：`coverage`/`scoreDistribution`/`datasetShape` 均可部分下推；「主诉去数字去重计数」用 `COUNT(DISTINCT REGEXP_REPLACE(...))` 一条 SQL。
- **预期收益**：与 C1/A1 叠加后报告页 Java 侧再降一档。

---

### F 类 · 微小项（顺手可做）

> 编号 F1–F5（本项目唯一 E 前缀条目归《性能与算法审计报告》T1–T20，避免混淆）。

| 编号 | 位置 | 问题 | 优化 |
|---|---|---|---|
| F1 | `EsTermIndexServiceImpl.java:405-415` | `hashId` 逐字节 `String.format("%02x")` 拼 MD5 | 改 `HexFormat.of().formatHex(digest)` |
| F2 | `DictionaryTermStoreImpl.java:56` | `readEffective` 每次重建 Map+List 两层合并 | 合并结果纳入 `VersionedCache` 或加 `readEffectiveAll()` |
| F3 | `StandardizationReportServiceImpl` | `dictQuality`/`crossTypeDuplicates`/`coverage` 各自 `readEffective` 全部词典类型 | 一次读全、三类复用 |
| F4 | `VersionedCache.java` | 逐出用 `while`+迭代器逐项删 | 覆写 `removeEldestEntry` |
| F5 | `AiServiceImpl.java:696` | `terms.contains(v)` 循环内 List 线性查找 | 换 `LinkedHashSet` |

---

## 三、Redis 应用审查

### 3.1 现状：Redis 只在 4 个业务点

| # | 位置 | Key | 用途 | 写入时机 | TTL |
|---|---|---|---|---|---|
| R1 | `JwtUtil.java` | `tcm:auth:ver:{userId}` | 令牌版本号，退出/改密使旧令牌失效 | `revokeAll` 时 `+1`（`:132`） | `expireHours+1` 小时 |
| R2 | `AuthServiceImpl.java:303` | `tcm:auth:fail:{username}` | 登录失败计数（跨实例防爆破） | 失败时 `INCR` | 首次失败设 15 分钟 |
| R3 | `StatsCache.java:36` | `cache:stats:{scope}:all:{md5(filters)}` | 看板 `/stats/all` 词频分布 | 未命中回填 | 固定 60 秒 |
| R4 | `DistLock.java:50` | `tcm:dict:rebuild:{type}:{org}` 等 | Redisson 分布式锁 | `runLocked` | 看门狗续期（默认 30s） |

另有 `tcm:startup:ping`（启动探活，TTL 10s，无业务含义）。

**未启用 Spring Cache**（无 `@EnableCaching`/`@Cacheable`），全是手写 `VersionedCache`（进程内 LRU）——缓存不跨实例共享。

---

### 3.2 现有 4 个点的三防分析与缺陷

#### R1 令牌版本号

- **穿透**：从末作废过的用户无 key → 每次 miss；每个已登录请求多一次 Redis 往返。对策：写占位值 `"0"`（TTL 与令牌同寿命），或本地 Caffeine 一级缓存。
- **雪崩**：`revokeAll` 写 key 的 TTL 固定 `expireHours+1`，批量登出让一批 key 同时过期；过期后 `versionMatches` 读到 `null` → `current == null → return true`（`JwtUtil.java:154-156`）——**旧令牌「复活」**。对策：TTL 加随机抖动，或干脆不设 TTL。
- **击穿**：活跃用户单键热点。对策：本地一级缓存 + 单飞。
- **⚠️ 降级方向**：`versionMatches` 在 Redis 异常时 `return true`（**:160-161 放行**），与 `DistLock` fail-closed 相反——刻意的「可用优先」，但 Redis 抖动期间旧令牌可用。建议此分支单独告警。

#### R2 登录失败计数

- **雪崩**：所有失败设固定 15 分钟 TTL，同窗口集中过期，攻击者可在过期瞬间续爆破。对策：TTL 抖动（15 ± 1–3 分钟）。
- **击穿**：单键（被保护对象本身），`INCR` 原子性已保证计数不丢。
- **⚠️ 原子性缺陷**：`increment` 与 `expire`（`AuthServiceImpl.java:303` 区）是两条命令；`INCR` 成功 `EXPIRE` 失败则计数永久累积 → 账号无限期锁死。当前靠 `lockRemainingSeconds`（`:194`）「`getExpire` 返 -1 按窗口结束」兜底，属事后补救。对策：Lua 脚本保证原子，或 `SET key 1 EX 900 NX` + `INCR`。

#### R3 看板词频缓存（问题最集中）

- **穿透（最实际风险）**：key=`md5(filters)`，`FiltersDTO` 组合空间不受限 → 可构造无限 key，每个 miss 都触发一次全表 JSON 展开。后果：命中率趋零 + Redis 内存被无效 key 撑爆。对策：① key 空间白名单化（时间归一天、科室限枚举）；② 空值缓存（TTL 10s）；③ 接口限流。
- **雪崩（两重）**：①全部固定 60s TTL 同秒过期；②`invalidateForWrite()`（`:65`）按 scope 通配删除全部 key，一次写入后下一请求全量现算。对策：TTL 抖动（60±10s）；失效改**版本号前缀**（写入只 `INCR`，旧 key 自然过期）而非通配删除。
- **击穿**：管理员「看全部」热点 key，失效瞬间并发各自现算。对策：单飞（`SETNX lock:stats:{key}`）或逻辑过期。
- **🔴 严重缺陷：使用了 `KEYS`**（**:72** `redis.keys(KEY_PREFIX+scope+":*")`）——O(N) 全库扫描 + 单线程阻塞，期间所有其它请求（含令牌校验、分布式锁）排队。生产禁用级。对策：①改版本号前缀（推荐）；②`SCAN` 分批 DEL；③严禁继续用 `KEYS`。

#### R4 Redisson 分布式锁

- **雪崩**：所有锁走同一实例；Redis 不可用时 `DistLock` **fail-closed** 抛 `ServiceNotReadyException`（`:87`，503）——不误放行（对的），但**全站写不可用**。对策：哨兵/集群；非关键锁可本地降级但**不能降级为放行**。
- **⚠️ 可用性**：`tryLock(WAIT_SECONDS=3s)`（`:38` 常量、`:52` 调用）固定等 3 秒，高并发大量线程阻塞 3s 才失败。对明显冲突场景建议改 `tryLock(0)` 快速失败 + 立即提示。

---

### 3.3 建议新增的 Redis 场景

| 状态 | 编号 | 场景 | Key 设计 | 一句话结论 |
|---|---|---|---|---|
| **待定** | R5 | 组织解析结果缓存 | `cache:org:primary:{userId}` | ⚠️需产品确认：每请求省 1 次 DB；会把「移出成员立即生效」变「迟一个 TTL」，TTL≤30s 且写时删 key；严格需立即生效则改不做 |
| **待定** | R6 | 术语归一召回跨实例共享 | `cache:term:recall:{type}:{org}:{md5}` + 整类版本号 | ⚠️需评估：解决多实例命中率摊薄 + `rebuild` 只清本实例（`EsTermIndexServiceImpl.java:79`）；归一在热路径，建议「本地 LRU 一级 + Redis 二级」，价值是**跨实例一致性**不是提速 |
| **待定** | R7 | 词典词条缓存跨实例共享 | `cache:dict:terms:{org}:{type}:{version}` | 跨实例一致性从「最多迟 5 秒」变「主动失效即刻一致」 |
| **待定** | R8 | 标准化报告结果缓存 | `cache:std-report:{org}:{start}:{end}:{dictVersion}` | **必须带词典版本**；配合 A1/C1，报告页「秒级 → 首次秒级、后续毫秒级」 |
| **待定** | R9 | 质控规则 / LLM 配置共享 | 每次访问回源读行比对版本（`QcRuleStore`/`LlmConfigStore`） | 正确性关键是失效不是 TTL，甚至可不用 TTL 纯写路径删 key |
| **待定** | R10 | 幂等键提前短路 | `SETNX idem:{org}:{key}` | ⚠️ **DB 唯一键必须保留**，Redis 只是加速层，否则丢数据会重复执行 |
| **待定** | R11 | 批任务进度高频轮询 | `task:progress:{taskId}`（Hash） | 轮询读 Redis，DB 只最终落库；任务完成主动删 key |
| **待定** | R12 | 接口限流 / 防刷 | 登录 / 导入 / **AI 建议**（每次调 LLM 有成本） | `INCR`+`EXPIRE` 固定窗口或 Lua 滑动窗口 |

**R13 / R14**：R13（分页缓存）评估后**不采纳**，R14（异步任务状态迁 Redis）**建议采纳**，详见 3.7.2。

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
- **成因时间线**：清洗事务逐页 UPDATE（未提交）→ `:216` 清空缓存而改动不可见 → 并发读 miss → 按旧数据现算回填 → 事务提交 → 缓存持旧值最长 60s。竞态窗口 = 清缓存到提交之间的毫秒级，非确定性。
- **澄清**：**事务回滚不是问题**（回滚后下次读按已提交数据重算，结果正确）；缺陷纯粹来自「清缓存发生在提交前」。
- **修复**（一行）：`:216` 改 `DistLock.afterCommit(StatsCacheInvalidator::invalidateStats)`。无活动事务时立即执行，单测直调路径不受影响。

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

### 3.6 Redis 部署与可靠性（O 类）

> 连真实实例查询所得（`windows-redis` redis:7.4），命令可复核。

#### O1 实例被其它应用共享

- **实测**：`DBSIZE` 14049；采样 6000 key，**5989 个前缀 `{flash_sale`**（秒杀/限流哈希标签）；本项目 key 仅 `tcm:auth:ver:*` 3 个、`cache:stats:*` 0 个。`grep "flash_sale|seckill|ratelimit" java-backend/src` 零命中——确认同实例跑着无关应用。
- **风险**：本项目 `DistLock` fail-closed——别的应用 key 爆炸或大命令拖垮实例时，本项目**全站写不可用**，根因不在本项目，排查易误判。
- **对策**：①独立实例/容器（最彻底）；②不同 `database` + 严格前缀 + 单独 `maxmemory`；③无论如何**不要在共享实例上跑 `KEYS`**。

#### O2 无内存上限 + `noeviction`

- **实测**：`maxmemory 0`、`maxmemory-policy noeviction`、`used_memory 3.72M`（峰 6.36M）、`databases 16`、`timeout 0`。
- **风险**：`maxmemory=0` 一直涨到容器 OOM；进程被杀 → `DistLock` fail-closed → 全站写 503。`noeviction` 对锁对、对缓存错（缓存被淘汰天经地义），两类 key 共用一套策略矛盾。
- **对策**：①设 `maxmemory`（如 512MB）并让缓存类可淘汰；②至少在共享实例设上限并监控 `used_memory`/`evicted_keys`；③新增缓存点（R5–R12）**必须全部带 TTL**——当前 `DBSIZE=14049` 而 `expires=3`，只有 3 个 key 带过期，本身就是「内存无限增长」的信号。

#### O3 未启用 Lettuce 连接池

- **实测**：`mvn dependency:tree` 无 `commons-pool2`；`application.yml` redis 段无 `lettuce.pool.*` → 走 Lettuce 默认单连接多路复用（Redisson 有自己的池）。
- **风险**：单连接约束并发上限；出现慢命令（正是 `StatsCache:72` 的 `KEYS`）排队传染所有 Redis 调用。同时缺少可调旋钮。
- **对策**：引入 `commons-pool2` + 显式 `lettuce.pool` 配置。**注意**：连接多无法替代 `KEYS` 修复——`KEYS` 阻塞 Redis 服务端单线程，连接再多也排队。

#### 附带观察：持久化与「缓存定位」不符（非缺陷，需与运维确认）

- **实测**：`appendfsync everysec`、`save 3600 1 300 100 60 10000`。
- **说明**：实例主要承担**缓存 + 分布式锁**时 AOF 意义有限（缓存可重算、锁有 TTL 兜底）却带来 fsync 开销；唯一例外是令牌版本号 `tcm:auth:ver:*`（丢了旧令牌复活）。建议二选一：令牌版本迁 MySQL，或确保该实例持久化可靠。**本条属「需确认」，不是缺陷。**

---

### 3.7 编码规范符合性核对

> 以「Redis 程序层面问题清单」逐条核对。结论分四档：✅ 已符合 / ⚠️ 部分 / ❌ 违反 / ➖ 不适用。

#### 3.7.1 开发层面 30 条

| # | 检查项 | 结论 |
|---|---|---|
| 1.1 | 穿透：空值缓存 / 布隆 | ❌ 无 |
| 1.2 | 击穿：互斥 / 逻辑过期 | ❌ 无 |
| 1.3 | 雪崩：TTL 抖动 / 熔断 | ⚠️ 降级有、抖动无 |
| 1.4 | 一致性：先改库再删缓存 | ✅ |
| 1.5 | 一致性：失效在事务提交后 | ❌ 3 处（见 3.5） |
| 1.6 | 失效失败重试 | ❌ 仅告警，靠 TTL |
| 1.7 | 脏窗口可容忍性评估 | ✅ |
| 2.1 | 禁超大 Value | ✅ |
| 2.2 | 大对象拆分 / 压缩 | ➖ 暂无 |
| 2.3 | Key 前缀统一 | ⚠️ 5 处 `tcm:`、1 处 `cache:` |
| 2.4 | 禁 `KEYS`，用 `SCAN` | ❌ 用了 `KEYS` |
| 3.1 | 禁 JDK 序列化，用 Jackson | ✅ |
| 3.2 | 序列化体积 / 中文转义 | ✅ |
| 3.3 | 序列化配置统一 | ✅ |
| 4.1 | 用 Redisson 非手写 SETNX | ✅ |
| 4.2 | 锁续期 / 释放校验 | ✅ |
| 4.3 | 锁粒度 | ⚠️ 批任务锁为全局单键 |
| 4.4 | 多命令原子性用 Lua | ❌ `INCR`+`EXPIRE` 非原子 |
| 5.1 | 变更触发类不设 TTL | ⚠️ 词典留 5s TTL |
| 5.2 | 自动过期类设 TTL | ✅ |
| 5.3 | 禁硬编码 TTL、统一配置 | ⚠️ 仅登录参数外置 |
| 6.1 | 连接异常 / 超时捕获 | ✅ |
| 6.2 | 降级不抛 500 | ⚠️ 读符合，锁刻意 fail-closed |
| 6.3 | 重试机制 | ❌ 无 |
| 7.1 | 连接池配置 | ❌ 未启用（见 O3） |
| 7.2 | 连接复用 | ✅ |
| 7.3 | Pipeline 批量 | ➖ 调用少，收益低 |
| 7.4 | 命令超时配置 | ✅ 5000ms |
| 8.x | 数据类型选型 | ✅ |
| 9.1 | 命中率埋点与指标上报 | ❌ 完全没有 |

**统计**：✅ 13 / ⚠️ 6 / ❌ 9 / ➖ 2。

**做得好的**：
- 序列化选型最干净：全仓只用 `StringRedisTemplate`，无 `RedisTemplate<String,Object>`、无 JDK 序列化、无 `@EnableCaching`。
- 分布式锁规范：无手写 `SETNX`；`tryLock(3,SECONDS)` 不传 leaseTime 走看门狗；`unlockQuietly` 先 `isHeldByCurrentThread()`。
- 读路径降级齐全：`StatsCache`（读失败现算）、`AuthServiceImpl`（计数不可用不锁定）、`JwtUtil:160-161`（版本读不到放行），均 try-catch 不抛 500。

**需要留意的 4 条**：
- **2.3 Key 前缀不统一**——5 处 `tcm:*`，唯独 `StatsCache.KEY_PREFIX="cache:stats:"` 无 `tcm:` 前缀；「例外」恰好是 key 空间失控 + `KEYS` 那处。建议统一 `tcm:ehr:`。
- **4.3 锁粒度与自述不一致**——`DistLock` 注释「不用全局单键」，`dictRebuildLock` 也带 `{type}:{org}`；但两个批任务提交锁是**全局单键**（`tcm:qc_batch:submit`、`tcm:nlp_batch:submit`）。临界区短，实际影响有限；提交逻辑变重时应按 org 分键。
- **6.2 与清单刻意冲突（非缺陷）**——`DistLock:87` 刻意 fail-closed 抛 503。锁不可用时降级放行 = 没有互斥；清单建议只适用缓存读。
- **9.1 命中率埋点缺失**——`StatsCache` 只有 `log.debug`；`pom.xml` 无 actuator/micrometer/prometheus。建议补 Actuator + Micrometer，在 `wordFreq` 命中/未命中各打计数器。

#### 3.7.2 可优化场景 7 项

| # | 清单场景 | 报告对应 | 结论 |
|---|---|---|---|
| 1 | 热点基础字典缓存 | R7 | 已建议 |
| 2 | 报表统计结果缓存 | R8 | 已建议 |
| 3 | 分页查询缓存（只缓单页） | 新增 R13 | **评估后不采纳** |
| 4 | 分布式锁 | R4 | 已落地 |
| 5 | 计数器 / 接口限流 | R12 | 已建议 |
| 6 | 异步任务队列（Stream/List） | 无 | **不采纳**（项目已自行否决） |
| 7 | 临时业务状态缓存 | 新增 R14 | **建议采纳** |

**R13 分页缓存——建议不做**：①列表受权限数据域过滤，key 漏维度=**越权读到别人的病历**（安全事故）；②列表已列投影+索引，单页不慢；③列表是编辑工作台，缓存的收益低于「看到旧值」的代价。**真正贵的是聚合统计，资源应投 R3/R8。**

**清单第 6 条「异步任务队列」——项目已否决，维持**：`QcBatchServiceImpl` 写明否决理由（原 `tcm:task:batch` 全局单键 + TTL 900s 中途过期并发双跑）；现状任务/进度写表，幂等靠 DB 唯一键——**具备事务性、可重启恢复、可查询**。Redis 的正确定位是**进度缓存**（R11），不是队列本体。

**R14 临时业务状态缓存——`AiAsyncTasks` 多实例隐患**：
- **位置**：`AiAsyncTasks.java`（登记表 `ConcurrentHashMap` 进程内，`MAX_TASKS=2000`、`RESULT_TTL_SECONDS=600`）。
- **问题**：单实例 OK；多实例下 A 实例提交、B 实例轮询 → 查不到任务（「任务不存在」而非「还在跑」）。这是清单第 7 条典型对象（值小、TTL 短、丢了可重跑）。
- **结论**：**建议迁 Redis**（`task:ai:{taskId}` Hash，TTL 600s；与 AI链路 AI-28 同结论）。

#### 3.7.3 禁止场景 3 条（反向核对）

| # | 禁止项 | 结论 |
|---|---|---|
| 1 | 强事务 / 多表关联复杂查询 | 未违反——全在 MySQL 事务内 |
| 2 | 不可丢失的核心数据 | ⚠️ 一处：令牌版本号 `tcm:auth:ver:*` |
| 3 | 超大单条文本 | 未违反；**约束**：R8 落地时 Value 必须设体积上限，避免 45 MB 塞进单 key |

#### 3.7.4 小结

清单 40 项核对完毕：开发 **✅13/⚠️6/❌9/➖2**；场景 **3 建议、1 落地、2 新增（1 采纳 1 不采纳）、1 维持**；禁止 **2 未违反、1 一处例外**。

最优先三条：**2.3 Key 前缀统一**（顺手做，是 2.4 与 O1 的根）、**9.1 命中率埋点**（否则缓存调优没有观测依据）、**R14 异步任务状态迁 Redis**（唯一「多实例下功能直接出错」）。

---

## 四、优先级与预期收益汇总

| 状态 | 优先级 | 编号 | 问题 | 改动量 | 预期收益 |
|---|---|---|---|---|---|
| **做** | **P0** | A1 | 报告页 JSON 重复解析 7 次/条 | 中 | 解析量降到 1/7，省 4–10s |
| **做** | **P0** | C1 | 报告页全表全行加载（约 45 MB） | 小 | 堆占用降约 70% |
| **做** | **P0** | R3-③ | `StatsCache` 使用 `KEYS` 阻塞命令 | 小 | 消除实例级阻塞风险 |
| **做** | **P0** | W1 | 清洗在事务内失效缓存 | 1 行 | 消除最长 60s 脏读，零风险 |
| **做** | **P0** | W2 | 删除在事务内失效缓存 | 1 行 | 消除最长 60s 脏读，零风险 |
| **做** | **P1** | A2 | `symptomTerms()` 重复调用 | 2 行 | 省一次词表读取 |
| **做** | **P1** | A4 | 每请求双重 JWT 验签 | 小 | 全站每请求省一次验签 |
| ~~B1~~ | — | 复核列表 N+1 | — | **本次复核确认已修复**（批量取 id 集合，见 §二 B1 注） |
| **做** | **P1** | B2 | 登录路径逐条查组织 | 小 | 查询 1+N → 2 |
| **做** | **P1** | C3 | 清洗链路 offset 深翻页 | 小 | 参照既有实测约 10–20× |
| **做** | **P1** | R3-①② | 统计缓存 TTL 无抖动 + key 空间失控 | 中 | 消除雪崩 + 穿透 |
| **做** | **P1** | W3 | Cache-Aside 旧值写回竞态 | 中 | 与 R3 修复同源，一处改动消三问题 |
| **做** | **P1** | O1 | Redis 实例被其它应用共享 | 部署 | 避免他因导致全站写 503 |
| **做** | **P1** | O2 | `maxmemory=0` + `noeviction` | 部署 | 防止 OOM 拖垮实例 |
| **做** | **P1** | 9.1 | 无缓存命中率埋点 | 小 | 缓存调优从「凭感觉」变「有数据」 |
| **待定** | **P2** | A3 | 术语建议排序重复算相似度 | 中 | 排序阶段调用降约 20× |
| **待定** | **P2** | B3 | 组织成员列表 N+1 | 小 | `1+N`→2 |
| **待定** | **P2** | C2 | 看板扩展 Java 侧三遍聚合 | 中 | 回传行数降 3 个数量级 |
| **待定** | **P2** | R1-①② | 令牌版本无 TTL 抖动 + 无 key 穿透 | 小 | 消除「旧令牌复活」+ 每请求往返 |
| **待定** | **P2** | R2 | 登录计数非原子 + TTL 无抖动 | 小 | 消除「账号无限期锁死」 |
| **待定** | **P2** | R14 | 异步任务状态在进程内（多实例失效） | 小 | 多实例下轮询不再「任务不存在」（与 AI-28 同结论） |
| **待定** | **P2** | 2.3 | Key 前缀不统一（`cache:stats:` 无 `tcm:`） | 极小 | 前缀隔离，共享实例防撞 |
| **待定** | **P3** | R5–R12 | 新增 8 个 Redis 场景 | 大 | 见各条（需产品/运维确认） |
| **不做** | **P3** | D1/D2 | LIKE 前缀通配 / JSON 列 LIKE | 小–中 | 当前规模收益有限，不做（预留待规模增长） |
| **不做** | **P3** | O3 | 未启用 Lettuce 连接池 | 小 | 补可调旋钮（储备） |
| **不做** | **P3** | F1–F5 | 微小项 | 很小 | 累积收益（储备，顺手随改） |
| **不做** | — | R13 | 分页结果缓存 | — | **明确不采纳**（越权风险 + 已优化 + 编辑台） |
| **不做** | — | 清单#6 | Redis Stream/List 异步任务队列 | — | **明确不采纳**（失去事务性与可恢复性） |

---

## 五、实施建议顺序

1. **零风险小改动**：**W1+W2**（各 1 行，`DistLock.afterCommit`，语义不变）——性价比最高。随后 **2.3**（前缀统一）、**9.1**（埋点，后续调优前提）、A2、A4、B1/B2/B3、F1–F5。
2. **P0 其余**：A1+C1 一起改（都动 `StandardizationReportServiceImpl`）；R3-③ 单独改 `StatsCache`。
3. **统一收口缓存策略**：R3 的 key 规范化 + TTL 抖动 + 单飞 + **版本号前缀（一并解决 W3）**，再按模板推广 R5–R8、R14。
4. **C2/C3 的 SQL 下推与 keyset 改造**——收益大但需回归（项目已有 60+ 测试类可依托）。
5. **部署侧（O 类）可并行**：独立 Redis / `maxmemory` / Lettuce 连接池，不依赖代码。O1/O2 风险等级高于改动成本，**建议尽早排期**。

---

## 六、未验证项

1. 所有「预期收益」时间数字为**估算**，未在 4 万条真实数据上跑基准。建议以 A1/C1 改造前后各计时报告接口验证。
2. Redis key 数/内存已采集（`DBSIZE=14049`、`used_memory=3.72M`、`expires=3`）；**未采集本项目自有 key 增长曲线**（采样时自有仅 3 个 `tcm:auth:ver:*`，`cache:stats:*` 为 0）。
3. **`KEYS` 实际阻塞时长未实测**（14049 键下影响小，增长后需重测；可用 `SLOWLOG`/`LATENCY DOCTOR`）。
4. **W1/W2/W3 竞态未并发压测复现**——结论来自代码时序分析，逻辑成立。建议修后补「清洗中并发轮询看板」用例。
5. **O3 「无连接池」为依赖树查询所得**，未实测单连接高并发排队表现。
6. **ES 召回缓存跨实例不一致**为代码推导，未多实例复现。
7. **`recallCandidates` 实际调用频次未统计**。
8. 未审查 frontend 侧性能（本次范围限定后端算法与 Redis）。
9. 未审查 python-nlp 推理耗时（模型固定加载；510 token 截断与批处理并发未测）。
10. **3.7 符合性核对基于代码阅读**，未做运行时验证（「命中率」本身无法采集正是 9.1 所指；`AiAsyncTasks` 多实例未复现）。

---

## 七、本次审查未触碰的部分

- **未修改任何源码**：全程只读。工作区存在一批未提交 WIP（`StatsServiceImpl`/`RecordMapper`/`RecordFilter`/`RecordServiceImpl`/`GovernanceServiceImpl`/`Record`/`TermInput.vue` 等），是既有改动，本次没碰。
- **未改动业务逻辑与计算口径**：唯一涉及**语义变更**的是 R5（组织解析缓存把「立即生效」变「最多迟一个 TTL」），需产品确认。
- **未改动运行环境**：3.6 节全部为只读查询（`CONFIG GET`/`INFO`/`--scan`），无写命令、未重启。
- **未改动构建与配置**：3.7 节涉及 `pom.xml`/`application.yml` 的建议只写报告，未改文件。