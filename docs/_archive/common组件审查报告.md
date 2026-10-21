# common 包组件审查报告（只读）

> **范围**：`java-backend/src/main/java/com/tcm/ehr/common`，**63 个文件 / 8584 行**（`find . -name '*.java' | wc -l` / `cat` 累计行数实测）。
> **问题**：有哪些可换成权威组件、可合并、不需要、可优化。
> **约束**：**本报告的 §一~§八 全部为只读结论**（不提交、不推送、不启服务；启服务会写 Redis 并重建 5 个 ES 索引）。**执行记录见 §九** —— 2026-10-17 起按用户裁定「分批执行」，批次 1（`00f6e9d`）、批次 2（`fb0c0c9`）已落地。
> **取证方式**：4 个只读子代理分包通读 → 逐条回读原文件复核 → 否定性结论一律用 `grep` 计数证明 → 库 API 用 `javap` 对**项目实际版本**验证。
> **工具链**：Spring Boot 4.1.1 / Spring AI 2.0.1 / MyBatis-Plus 3.5.17 / Redisson 3.52 / Jackson 3（`tools.jackson`）/ POI 5.2.5 / spring-security-crypto 7.1.1 / Caffeine 3.1.8。
> **依赖树实测**：`pom.xml` **无** commons-lang3 / guava / hutool / commons-codec / commons-io / spring-boot-starter-cache / spring-boot-starter-security / Resilience4j / Spring Retry / Actuator。
> **与既有文档的关系**：`docs/自研组件替换计划.md`（353 行）已覆盖 utils 里 #1~#12 的「自研→权威组件」分析；本报告**不重复其结论**，只做标注 + 补充它未涉及的**合并 / 死代码 / 优化**三个维度。

---

## 零、结论速览

| 类目 | 条数 | 其中「建议动手」 | 其中「存疑/需权衡」 |
|------|------|------------------|--------------------|
| 一、可换权威组件 | 8 | 3 | 5 |
| 二、可合并 | 11 | 7 | 4 |
| 三、不需要（死代码） | 13 | 8 | 5 |
| 四、可优化 | 20 | 13 | 7 |
| **合计** | **52** | **31** | **21** |

> **2026-10-17 第三轮修订**：执行批次 2 时按「简洁优先 / 不引入复杂模式 / 不改可见行为」重新权衡，**§2.1、§2.4（全量部分）、§2.8（`joinFieldErrors` 部分）判定不做**，已在各条末尾与 §九 批次 2 注明理由。故「二、可合并」实际落地 7 条中的 6 条（§2.4 只做了一半、§2.1 未做）。
> **执行批次 3 时**再定：**§1.1 判定不做**（与批次 2 的 §2.6 冲突，见 §1.1 末条），§1.2 / §1.3 / §1.5 已做且**§1.3 的两条原建议经实测需要修正**（见 §1.3 末条）。三项替换均以**临时用例实测后删除**验证，用例总数仍为 556。
> **执行批次 4 时**再定：**§4.4 判定不做**（低优先，改数据结构会动并发语义）；**§4.6 形式与原建议不同**（抽方法返回新对象，而不是缓存为可变常量 —— 理由见该条末条）；§三.3 / §三.4 / §4.3 / §4.7 / §4.8 / §4.9（部分）已做。三个缓存类改动同样以**临时用例实测后删除**验证，用例总数仍为 556。
> **2026-10-17 批次 5 —— §七 存疑清单已全部裁定**（用户授权由我决定，约束是「不影响现在能跑的系统」）：**只做 6 项零行为改动**（注释 / 日志文案 / 判空口径收敛 / 一处 NPE 补漏），**12 项保留现状**。逐条裁定与理由见 §九「存疑项裁定」表。**至此 §一~§四 与 §五、§七 的可动项已全部处置完毕。**

**最高价值的 5 条**（低风险、收益明确）：
1. `Result.ok()` 与 `Result.error(String)` —— 全仓 0 引用，纯删。**✅ 已做（`00f6e9d`，批次 1）**
2. `AsyncConfig.namedDaemon` → `setThreadNamePrefix` + `setDaemon`（Spring 原生，javap 实证）。**✅ 已做（`b0a3aa6`，批次 3）**
3. `EntityNormalizer` 三处 `backfill*` 逐字同构 → 抽一个泛型方法。（**⚠️ 2026-10-17 第三轮修订：评估后判定不做**，抽象需 7~9 参数 + 多个函数式接口，属过度封装 —— 见 §2.1 与 §九）
4. `QcRuleDescriber` 的 `el`/`fmt` 与 `QcRuleSet` 的 `el`/`format` 逐字重复 + 年龄正则硬编码两份。**✅ 已做（`fb0c0c9`，批次 2）**
5. `EntityNormalizer:403-405` 注释与代码顺序**互相矛盾**（注释说「之后」，代码在「之前」）。**✅ 已做（`00f6e9d`，批次 1）**

---

## 一、可换权威组件

### 1.1 `RecordUtil.md5Hex` → Spring `DigestUtils.md5DigestAsHex`（建议）

- **位置**：`common/utils/RecordUtil.java:120-129`
- **证据**：`javap -cp spring-core-7.0.7.jar org.springframework.util.DigestUtils` 实测含 `public static java.lang.String md5DigestAsHex(byte[])`；`spring-core` 已在依赖树。
- **现状**：手写 `MessageDigest.getInstance("MD5")` + `HexFormat.of().formatHex` + `NoSuchAlgorithmException → IllegalStateException`。
- **建议**：方法体改为 `return DigestUtils.md5DigestAsHex(s.getBytes(StandardCharsets.UTF_8));`。
- **注意**：`javap` 同时确认 Spring 的 `DigestUtils` **只有 MD5 系列、无 SHA-256**，所以它**不能**覆盖 `ReviewTaskUtil.fingerprint`（见 2.6）。
- **与计划的关系**：计划 §2.12 只提「把 `StatsCache.keyOf`/`EsTermIndexServiceImpl.hashId` 收敛到 `RecordUtil.md5Hex`」，**未提** `RecordUtil.md5Hex` 自身可换 Spring —— 本条是新增。
- **⚠️ 2026-10-17 判定：放弃本项。** 批次 2 已把 `md5Hex` 与 `fingerprint` 收敛到 `DigestUtil.hex(algo,text)`（§2.6）。若本项再让 `md5Hex` 走 Spring，`DigestUtil` 就只剩 `fingerprint` 一个调用方；而 Spring `DigestUtils` 无 SHA-256，无法把两者统一到 Spring —— 两条各做一半比现状更差。保留 `DigestUtil` 两用（其实现只是 3 行 JDK API，不构成「自研组件」）。见 §九 批次 3。

### 1.2 `AsyncConfig.namedDaemon` → Spring 原生线程命名/守护（建议）

- **位置**：`common/config/AsyncConfig.java:34-41`（工厂）+ `:63`、`:92`（两处 `setThreadFactory`）
- **证据**：`javap -cp spring-core-7.0.7.jar org.springframework.util.CustomizableThreadCreator` 实测含 `public void setThreadNamePrefix(String)` 与 `public void setDaemon(boolean)`；`ThreadPoolTaskExecutor` 经 `ExecutorConfigurationSupport` 继承该基类。
- **建议**：删掉 `namedDaemon`（8 行），两个池改用 `e.setThreadNamePrefix(prefix); e.setDaemon(true);`。
- **收益**：净减约 8 行 + 去掉手写 `AtomicInteger` 序号。
- **✅ 2026-10-17 已做（提交 `b0a3aa6`）**。**javap 修正了本条原假设**：`setDaemon` **不在** `ExecutorConfigurationSupport` 上（该类只声明 `setThreadFactory` / `setThreadNamePrefix`），而在其祖先 `CustomizableThreadCreator` —— 链是 `ExecutorConfigurationSupport extends CustomizableThreadFactory extends CustomizableThreadCreator`，故 `e.setDaemon(true)` 仍可继承调用、且 Spring 未显式设工厂时以自身为工厂会读这个标志。实测 5 个池线程名为 `ai-task-1` / `nlp-task-1` / `qc-batch-worker-1` / `nlp-batch-worker-1` / `nlp-record-worker-1`、`daemon` 全 `true`，与原先逐字一致。见 §九 批次 3。

### 1.3 `QcRuleStore.rowCache` 手写 TTL Map → Caffeine（建议）

- **位置**：`common/config/QcRuleStore.java:45`（字段）、`:94-103`（`expiresAtMs` 判定 + 惰性清理）
- **证据**：Caffeine 3.1.8 已在依赖（`pom.xml:143`，本轮 `9eda480` 引入）。
- **建议**：换 `Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.SECONDS).maximumSize(N).build()`，其 `invalidate(key)` 天然覆盖现有的「写路径主动删 key」。
- **与计划的关系**：计划 §2.4 只把 Caffeine 用于 `VersionedCache`/`RegexCache`，**未提** `QcRuleStore` 这个手写 5s TTL Map —— 本条是新增。
- **✅ 2026-10-17 已做（提交 `b0a3aa6`）**，但**本条原建议有两处必须改**：① 值要用 `Optional` 包一层 —— Caffeine **不接受 null 值**，而「该组织没有行」在原实现里是有意缓存的稳定事实，直接存 `QcRule` 会让无行组织退化成每次 `selectById`（批量质控 4 万条 = 4 万次查询）；② **不要**加 `maximumSize` —— 原实现无上界、键空间 = 组织数，加上界会引入当前没有的逐出。实测：无行组织连续 3 次 `getFor` 只回源 1 次。见 §九 批次 3。

### 1.4 `TextUtil.isBlank` → Spring `StringUtils.hasText`（低价值，可选）

- **位置**：`common/utils/TextUtil.java:28-30`
- **证据**：方法体 `s == null || s.isBlank()`，等价于 `!org.springframework.util.StringUtils.hasText(s)`（spring-core）。
- **说明**：这是有意的收敛点（全仓 `isBlank` 的唯一权威）。**建议保留外观**，只把方法体委托给 Spring —— 收益低，可不做。

### 1.5 `DataInitializationListener` Redis 探活用「写 key」→ `RedisConnection.ping()`（建议）

- **位置**：`common/config/DataInitializationListener.java:59-63`（`opsForValue().set(...)` 在 `:61-63`）
- **现状**：探活方式是 `SET ...:startup:ping` 一个 10s TTL 的 key。
- **建议**：改用 `redisTemplate.execute((RedisCallback<String>) RedisConnection::ping)`，消除副作用写入。
- **✅ 2026-10-17 已做（提交 `b0a3aa6`）**。`RedisConnection.ping()` 的声明位置经 javap 确认在 `RedisConnectionCommands`（`RedisConnection` 经 `RedisCommands` 继承而来），返回 `String`。实测 `run()` 只发生一次 Redis 交互（即 PING），`opsForValue()` 从未被调用。见 §九 批次 3。

### 1.6 `GlobalExceptionHandler` ≈ 手写版 `ResponseEntityExceptionHandler`（评估后**不建议**）

- **位置**：`common/exception/GlobalExceptionHandler.java:217/229/241/265/279/292/304/322/174/205` 等 ≥10 个 handler
- **证据**：`javap -cp spring-webmvc-7.0.9.jar org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler` 实测已含这些 `handle*`。
- **为何不建议**：① 项目契约要求 `Result{code,msg,data}` 包装，仍须覆写 `handleExceptionInternal`；② 默认状态码有差异（如 `MaxUploadSizeExceededException` Spring 默认 413，本项目 `:242` 用 400）；③ `Result.code` 仍须逐类映射。**净收益有限，属有意的重复实现。**

### 1.7 `StatsCacheInvalidator` 静态 volatile → 事件机制（存疑）

- **位置**：`common/cache/StatsCacheInvalidator.java:19-24`
- **现状**：`static volatile StatsCache` 静态门面，类注释说明是「为不改 11 个测试文件的构造链」的有意取舍。
- **风险**：单 JVM 多 `ApplicationContext` 下会互相覆盖。**存疑**，需权衡测试改动面。

### 1.8 已由计划覆盖的替换项（**不再展开**）

`RateLimiter`→Redisson（#1）· `LlmSecretCipher`→`Encryptors.delux`（#2，**本轮已落地** `b122bef`）· `PythonNlpClient`→`RestClient`（#3，**已落地**）· `RegexCache`→Caffeine（#4，**已落地** `9eda480`）· `VersionedCache`→Caffeine（#4，**实测 W-TinyLFU 非严格 LRU，已还原保留**）· `ExcelRawStreamReader`→EasyExcel（#6，**POI 官方 handler 探针证伪，保持自研**）· `BatchInsert`→`SqlHelper.executeBatch`（#11，**已落地** `234be8c`）· `StatsCache`→Spring Cache（#9，语义缺口，部分替换）· `LlmClient` 引擎层→Spring AI 装配（#8，高风险，仅外围）。

---

## 二、可合并

### 2.1 `EntityNormalizer` 三处 `backfill*` 逐字同构（建议）

- **位置**：`common/utils/EntityNormalizer.java:552-581`（`backfillHerbs`）、`:584-613`（`backfillDiseases`）、`:625-654`（`backfillPatterns`）
- **证据**：三个方法均为同一五步序列 —— ① 原料空白即返 false ② `allNormalized` 异常触发 ③ `scan` 取 hits ④ `removeIf(isProperSubstringOfAny)` 剔截断项 ⑤ append 命中词；**仅字段访问器（`Herb::getName` / `Entity::getContent`）与 type key（`herb`/`disease`/`pattern`）不同**。
- **建议**：抽 `private <T> boolean backfill(List<T> list, String type, String raw, Function<T,String> getter, BiConsumer<List<T>,String> adder, Consumer<List<T>> setter, ...)`，或抽一个小的「列表适配器」接口。
- **⚠️ 2026-10-17 第三轮修订：评估后判定不做。** 抽象需 7~9 个参数 + 3~4 个函数式接口（字段访问器、元素构造器、列表存取器、type key 全不同），属过度封装；三个原方法各约 30 行、直观可读。理由见 §九 批次 2。

### 2.2 `QcRuleDescriber` 与 `QcRuleSet` 工厂方法重复（建议）

- **位置**：`common/config/QcRuleDescriber.java:158-165`（`el`）↔ `QcRuleSet.java:204-211`（`el`）**逐字相同**；`QcRuleDescriber.java:167-186`（`fmt` 两个重载）↔ `QcRuleSet.java:213-223`（`format`）**同构**。
- **另有硬编码重复**：`QcRuleDescriber.java:146-147` 的年龄正则 `^(\d+(\.\d+)?(岁|个月|月|天)?)+$` 与 hint「须为数字，可带 岁/个月/月/天 单位（可组合，如 32岁6月）」与 `QcRuleSet.java:151-153` **逐字相同**（性别已共享 `GENDER_VALUES`，年龄漏了）。
- **建议**：年龄正则/hint 提为 `QcRuleSet.AGE_PATTERN` / `AGE_HINT` 常量；`el`/`fmt` 合并进 `QcRuleSet` 的静态工厂，`QcRuleDescriber` 改调。

### 2.3 `StructuredDataMeta` 三处 meta 构建重复（建议）

- **位置**：`common/utils/StructuredDataMeta.java:47`（`stamp(ObjectMapper,String,...)`）、`:83`（`stamp(ObjectMapper,Map,...)`）、`:122`（`stampManual`）
- **证据**：三处的「建 meta Map / put `_meta` 键」结构逐字重复（键常量 `META_KEY`/`KEY_MANUAL`/`KEY_EDITED_BY`/`KEY_EDITED_AT` 已集中，但装配逻辑没有）。
- **建议**：抽私有 `metaOf(dictVersion, termCount)` / `metaOfManual(editedBy)` 复用。

### 2.4 `RecordFilter` 两条 build 路径条件组装重复（建议）

- **位置**：`common/utils/RecordFilter.java:54-139`（`build(String, SearchDTO)`）与 `:406-450`（`build(String, FiltersDTO, boolean, boolean)`）
- **证据**：两处各自实现 `department/pattern/grade/dateRange/importRange/onlyUnparsed` 的拼装，口径几乎逐字重复。
- **建议**：`SearchDTO` 先归一成 `FiltersDTO`，再共用同一段组装。
- **附带**：`notBlank`（`:452-454`）与 `TextUtil.isBlank` 重复；`text()`（`:322-329`）的「字面 null/空白 → 归 null」与 `RequestUtils.attr`（`:187-188`）重复。
- **⚠️ 2026-10-17 第三轮修订：全量归一的前提不成立。** `SearchDTO` 有 `registrationNo` / `outpatientNo` / `gender` / `doctorId` / `status` 五个 `FiltersDTO` **没有**的字段（反向 `FiltersDTO.ids` 也是 `SearchDTO` 没有的），归一必须先给 `FiltersDTO` 补字段 —— 而它是 stats / 导出契约的一部分。**只做了安全的一半**：抽 `applyRange` 把 4 处时间区间块合一。见 §九 批次 2。

### 2.5 `RequestUtils.safeCurrentRole/safeCurrentOrgRole` 与其非 safe 版等价（建议）

- **位置**：`common/utils/RequestUtils.java:72-78`（`safeCurrentRole`）、`:81-87`（`safeCurrentOrgRole`）↔ `:67`（`currentRole`）、`:101`（`currentOrgRole`）
- **证据**：两者转调的 `attr()`（`:178-193`）**内部已整体 try/catch 并恒返回非 null**（无上下文返回 `"unknown"`）→ safe 版的 `catch` **永不触发**，返回值恒等。且 `safeCurrentRole` 的 catch 返回 `""`，与其 javadoc 说的 `"unknown"` 不符。
- **调用点**：`AsyncTaskRegistry.java:154-155`（改调 `currentRole()/currentOrgRole()` 行为不变）。
- **建议**：删两个 safe 方法 + 改 2 处调用。

### 2.6 摘要 → hex 两处同模式（建议）

- **位置**：`common/utils/RecordUtil.java:120-129`（MD5）与 `common/utils/ReviewTaskUtil.java:45-57`（SHA-256）
- **证据**：两处均为 `MessageDigest + HexFormat.of().formatHex + NoSuchAlgorithmException → IllegalStateException`。
- **建议**：抽 `common/utils/DigestUtil.hex(String algo, byte[] data)` 一处实现。
- **注意**：**不能**用 Spring `DigestUtils` 统一（javap 实证它只有 MD5 系列，无 SHA-256）—— 与计划 §2.12 的证伪结论一致。

### 2.7 401/403 写响应 3 处重复（建议）

- **位置**：`common/interceptors/JwtInterceptor.java:105-110`、`OrgRoleInterceptor.java:82-87`、`RoleInterceptor.java:72-78`
- **建议**：抽一个 helper（如 `ResponseWriter.writeJson(response, status, code, msg)`）收敛三处 `setStatus + setContentType + getWriter().write(objectMapper.writeValueAsString(Result.error(...)))`。
- **与计划的关系**：计划 §三 已识别，本条确认行号未漂移。

### 2.8 `GlobalExceptionHandler` 内部结构重复（建议）

- **位置**：11 个 handler 均为 `ResponseEntity.status(X).body(Result.error(code, e.getMessage()))`（`:47`、`:58`、`:94`、`:150`、`:161`、`:386` …）→ 抽 `error(HttpStatus, int, String)` helper。
- **另有**：`handleValidation`（`:174-189`，`Collectors.joining` 在 `:188`）与 `handleBind`（`:350-356`，joining 在 `:353`）都是「遍历 `fieldErrors` 用 `；` 拼接」→ 抽 `joinFieldErrors(BindingResult)`。
- **⚠️ 2026-10-17 第三轮修订：`joinFieldErrors` 判定不合并。** 两者格式本就不同 —— `handleValidation` 用「`字段：消息`」且 `.distinct()`，`handleBind` 用「`字段 消息`」且不去重；合并必然改掉其中一个的可见文案（`handleBind` 罕见但可达）。**只抽了 `error(...)` 外壳**。见 §九 批次 2。

### 2.9 7 个空壳异常类可并入基类（**可合并但需权衡，不建议盲改**）

- **位置**：`exception/BadCredentialsException.java`(14 行)、`UnauthorizedException.java`(15)、`ForbiddenException.java`(12)、`RateLimitedException.java`(19)、`ConcurrentOperationException.java`(20)、`LlmProbeException.java`(15)、`ServiceNotReadyException.java`(19)
- **证据**：7 个类体逐字同构 `class X extends RuntimeException { X(String msg){ super(msg); } }`，唯一作用是让 `GlobalExceptionHandler` 按类型映射状态码。
- **代价**：收敛后 `GlobalExceptionHandler` 的 11 个 handler 可减到 1~3 个，**但**会失去编译期语义区分，且多处测试按具体类型 `assertThrows` 要改。**列为「可合并但需权衡」。**
- **预埋风险**：`BadCredentialsException` 与 `org.springframework.security.authentication.BadCredentialsException` **同名** —— 当前只依赖 `spring-security-crypto`（不含 authentication 包）故无冲突，**日后引入完整 Security 会撞名**。

### 2.10 `WebMvcConfig` 白名单三处重复（轻微）

- **位置**：`common/config/WebMvcConfig.java:33`、`:36`、`:40` —— `excludePathPatterns("/api/auth/login", "/api/auth/register")` 三处逐字重复。
- **建议**：提为常量 `WHITELIST`。

### 2.11 `EntityNormalizer` 命中层级判据 3 次重复（建议）

- **位置**：`common/utils/EntityNormalizer.java:327-328`、`:439`、`:474` —— 均为 `r.level() >= LEVEL_EXACT && r.level() <= LEVEL_FUZZY`。
- **建议**：抽 `EsTermNormalizer.isHit(int level)` 一处。

---

## 三、不需要（死代码，均已 grep 证明）

> 判据：**生产代码（`src/main`）零引用**；仅测试引用的一并标注。

| # | 位置 | 证据（命令 → 结果） | 建议 |
|---|------|--------------------|------|
| 1 | `common/domain/Result.java:54-56` `ok()` 与 `:79-81` `error(String)` | `grep -rn 'Result\.ok()\|Result\.error("' src/` → **0** | **删除** |
| 2 | `common/utils/LlmClient.java:112-114` `isEnabled()` | 全仓 `isEnabled` 命中均为 `PythonNlpClient`（`NlpBatchServiceImpl:334/445`）与 `LlmProperties`（`LlmConfig:30`）/`QcRuleDescriber:81`；`LlmClient.isEnabled()` 主代码 0 调用 | **删除** |
| 3 | `common/utils/LlmClient.java:122-124` `provider()`、`:133-135` `chat(String,String)`、`:178-180` `chat(String)` | 仅 `LlmClientTest:85/86/96/108/117/129` 调用；主代码 3 处均走 3 参 `chat(AiFeature,…)`（`AiServiceImpl:88/318/514`） | 删方法 + 测试改走 3 参。**✅ 已做（`c1d5b2f`，批次 4）** |
| 4 | `common/utils/JwtUtil.java:138-140` `isValid(String)` | 仅 `JwtRevokeTest` 调用；生产入口 `JwtInterceptor:74` 走 `parseAndVerify` | 删或迁到测试侧。**✅ 已做（`c1d5b2f`，批次 4）**：直接删，12 处测试改走 `parseAndVerify` |
| 5 | `common/utils/RequestUtils.java:49` `ORG_ROLE_MEMBER` | 主代码仅自身定义；生产判组内角色用 `OrganizationMember.ROLE_MEMBER`；仅 4 个测试用 | 留（测试用）但标「测试专用」 |
| 6 | `common/utils/StructuredDataMeta.java:151-166` `isManuallyEdited` | 主代码 0 调用（仅 `Record.java:68` javadoc 引用 + `StructuredDataMetaManualTest`）；生产已改读 DB 生成列 | 加 `@Deprecated` 注明「语义参考实现」 |
| 7 | `common/config/AiFeature.java:25` `label` 字段 + `:37-40` `label()` | `grep -rn '\.label()' src/` 排除 `EntityTypes` 后**无** `AiFeature.label()` 命中 | 删除（或接入前端标签） |
| 8 | `common/config/AsyncConfig.java:30` `@EnableAsync` | `grep -rn '@Async' src/` → **0**；执行器是手工 `@Qualifier` 注入后 `execute()` | 删除（或注明预留） |
| 9 | `common/utils/EsTermNormalizer.java:227-233` | `out` 是 `LinkedHashSet`（`:207`），且 `std` 必已在 `:225` 加入 → 该块**幂等空转**，永不产生新元素 | 删除该块 |
| 10 | `common/exception/GlobalExceptionHandler.java:350-356` `handleBind` | `javap` 实证 `MethodArgumentNotValidException extends BindException`（更具体的 `:174` 接走）；`grep -rn '@ModelAttribute' src/` → **0** | **存疑**，需运行期确认后再删 |
| 11 | `common/interceptors/RoleInterceptor.java:53-55`（类级分支）、`:57`（空 roles 分支） | `grep -rn '@RequireRole' controller/` 6 处**全方法级**；`grep -rn 'roles *= *{}'` → **0** | 防御性预留，存疑 |
| 12 | `common/annotation/RequireRole.java:23` `@Target(TYPE)` | 同 11，类级无触发 | 存疑（保留扩展性） |
| 13 | 可降可见性：`LlmConfigStore.java:221-223` `version()`、`QcRuleStore.java:187-189` `get()`、`StatsCache.java:50` `KEY_PREFIX` | `grep -rn` 三者均**无外部调用**（`configStore.version()`/`ruleStore.get()`/`StatsCache.KEY_PREFIX` 全 0） | 降 `private` |

**明确非死代码**（勿删）：`EntityNormalizer.drainNegatedSkip:57-59`（测试用）· `RedisLua.incrWithTtl`（`JwtUtil:233`）· `RequestUtils.currentOrgId():45-47`（类内 `canAccess`/`domainOrgId` 用）· `RecordKeyset`（5 个批处理调用方）· `PageSizeGuard`（4 个 Controller）· `DictMeta`（`NlpBatchServiceImpl:817/901`、`GovernanceServiceImpl:122`）。

---

## 四、可优化

### 4.1 `EntityNormalizer:403-405` 注释与代码顺序**互相矛盾**（中）

- **位置**：`common/utils/EntityNormalizer.java:403-405`
- **证据**：注释 `:403-404` 称预取放在「归位与丢弃**之后**、逐条归一**之前**」；但代码 `prefetchRecall`（`:405`）在 `moveMisplacedPulseTongue`（`:411`）与 `dropSingleCharFragments`（`:416`）**之前**。
- **影响**：① 预取集合含随后会被丢弃的单字残词；② `moveMisplacedPulseTongue` 要查的 `pulse`/`tongue` 键**未被预取**，仍回源 ES → 多一次往返。
- **建议**：改代码顺序（把 `prefetchRecall` 移到 `:416` 之后）**或**改注释。二者必须对齐。

### 4.2 `QcRuleDescriber.describe:36-41` 权重被循环覆盖（中，潜在）

- **位置**：`common/config/QcRuleDescriber.java:36-41`
- **证据**：`int full = 0, partial = 0;` 后在 `for` 内**逐次覆盖**（`full = e.getWeightFull()`），循环结束后只剩**最后一个**要素的权重用于整体文案。
- **影响**：当前各要素同权重故无表现差异；**一旦按要素差异化权重，文案会错**。
- **建议**：取 `elements.get(0)` 或断言「各要素权重一致」。

### 4.3 `ExcelRawStreamReader` 两处（低）

- `:271-272` `styles.getStyleAt(styleIdx)` 连续调用两次（`getDataFormat()` / `getDataFormatString()`）→ 存局部变量。
- `:101-104` `finally { Files.deleteIfExists(tmp); }` 若抛 `IOException` 会**覆盖 try 中真实的读取异常** → catch 后仅记日志或 `addSuppressed`。
- **✅ 已做（`c1d5b2f`，批次 4）**：两处都改。① 存 `CellStyle st` 局部变量；**顺带堵了一个潜在 NPE** —— `StylesTable.getStyleAt(idx)` 对越界索引返回 **null**（不抛），原写法 `.getDataFormat()` 会 NPE，现在按「非日期格式」处理。② `finally` 里的删除包 try/catch 吞掉：本类**没有 logger**，为一行删除失败引静态 logger 不划算，`addSuppressed` 要拆主异常结构，故取最简形式 + 注释。**副作用**：「读取成功但删临时文件失败」原先会让整个导入抛 IOException，现在会成功 —— 属修好方向。

### 4.4 `AsyncTaskRegistry.prune:302-313` 每次提交全表扫描（低）

- **证据**：`submit` 时 `removeIf` 全表扫 + `size >= 2000` 时 `stream().sorted()` 全排序 → O(n log n)/提交。
- **建议**：改按 `createdAtMs` 的 `TreeMap`/环形结构。2000 项尚小，低优先。
- **⚠️ 2026-10-17 判定：不做（批次 4）。** 改动要把 `ConcurrentHashMap` 换成 `TreeMap`/环形结构，会动 `submit` 的并发语义；而当前量级是 `MAX_TASKS=2000`、单次排序约 2×10⁴ 次比较，属可忽略量级。**不为这点开销引入新的并发风险。**

### 4.5 `LlmConfigStore.versionOf:177-189` / `QcRuleStore.getFor:69`（版本串拼装在 `:80`）版本号放大（存疑）

- **证据**：两者都把**进程内全局版本**拼进每个 key 的版本串；`saveFor` 每次自增全局版本（`LlmConfigStore:154`）→ **任一用户保存都会让所有用户的缓存重算**；`QcRuleStore` 同形（任一组织写入波及所有组织）。
- **建议**：仅「无自有配置」分支参与基线版本；按 key 维度维护版本。**存疑**（需确认跨实例意图）。

### 4.6 `QcRuleSet.defaults()` 反复整体构造（低）

- **位置**：`common/config/QcRuleSet.java:137-202`；调用方 `QcRuleStore.normalize:257`（只为取 `getCompleteness()`）、`:282`（`getStandardization()`）、`QcScorer:36/144`
- **建议**：把「默认要素」「默认标准化」缓存为常量。
- **✅ 已做（`c1d5b2f`，批次 4），但形式与原建议不同**：改成在 `QcRuleSet` 里抽**包级** `defaultCompleteness()` / `defaultStandardization()`（每次返回**新对象**），`defaults()` 与 `QcRuleStore.normalize:265/290` 共用。
  - **为什么不做「缓存为常量」**：`Element` / `Standardization` 都是可变 `@Data`，而 `QcRuleStore.normalize` 会把取到的子对象**直接 set 进生效规则集**（`current.completeness = …`）—— 共享常量等于把一个可变全局对象挂进业务状态，日后谁 `getElements().add(…)` 就是全局污染。`EntityTypes` 自己就写着「构建完包成不可变后才赋给 static」这条约定，不该在这里反着来。
  - **收益并未打折**：报告要消的是「为取一个子对象把 14 条一致性规则 + 2 条格式规则全构造一遍再丢掉」，抽方法后这条浪费消失了。
  - **实测**：两次 `defaults()` 的 `Completeness`/`Standardization` 不是同一实例；6 要素、`elementTypes` 顺序与内容、`weightEach=1`、`cap=5`、`enabled=true` 逐项等于原值。

### 4.7 `EntityTypes.all()` / `dictKeys()` 每次新建集合（低）

- **位置**：`common/config/EntityTypes.java:109-111`（`all()`）、`:114-126`（`dictKeys()`）
- **证据**：`all()` 在 `StandardizationReportServiceImpl` 单次报表被调 4 次（`:476/507/564/582`）；`dictKeys()` 在 `DictionaryTermStoreImpl` 循环内（`:492/525`）。
- **建议**：缓存 `List.copyOf` / `Set.copyOf` 不可变视图。
- **✅ 已做（`c1d5b2f`，批次 4），但 `dictKeys()` 不能用 `Set.copyOf`**：在原有 static 块里构建 `ALL_VIEW = List.copyOf(byKey.values())` 与 `DICT_KEYS = unmodifiableSet(LinkedHashSet)`。
  - **`Set.copyOf` 是错的**：它返回的 `ImmutableCollections.SetN` **迭代顺序未定义**（按每次 JVM 启动的 SALT 打乱），会破坏本条 javadoc 明写的「顺序稳定」契约 —— `QcRuleSet.defaults()` 的 `elementTypes` 顺序、`EntityTypesDictionaryRegistrationTest:103`（`assertEquals(expected, List.copyOf(EntityTypes.dictKeys()))`）都依赖它。故必须用 `LinkedHashSet` + `unmodifiableSet` 保序。
  - **实测**：两次调用 `assertSame`；`dictKeys()` 顺序 == `all()` 过滤结果；两者 `add` 均抛 `UnsupportedOperationException`。
  - **调用方全部只读**（`for-each` / `contains` / `new ArrayList<>(…)` / `List.copyOf(…)`），改为不可变视图无兼容问题。

### 4.8 `JwtUtil.getKey()` 每请求重建 `SecretKeySpec`（低）

- **位置**：`common/utils/JwtUtil.java:62-64`
- **证据**：每次解析/签发都 `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` 并做长度校验，而 `secret` 注入后不变；验签是每请求热路径。
- **建议**：首次调用缓存为 `volatile Key` 或 `@PostConstruct` 初始化。
- **✅ 已做（`c1d5b2f`，批次 4）**：取 `volatile Key cachedKey` 懒初始化（不用 `@PostConstruct`，省掉「注入顺序 vs 首次使用」的额外约定）。
  - **与「配置以 DB 为准 + 版本号失效」这条跨批硬约束不冲突**：JWT 密钥来自 `@Value("${jwt.secret}")`，不在 DB、也没有热更新路径，注入后是常量。
  - **派生失败不缓存**：`Keys.hmacShaKeyFor` 对短密钥抛 `WeakKeyException`，抛出时不写 `cachedKey`，下次仍抛同样异常 —— 与原行为一致。
  - **实测**：同一实例两次 `getKey()` 返回**同一 Key 对象**、`cachedKey` 字段被填上。

### 4.9 其余轻微项

- `StatsCache.invalidateForWrite:101-105` —— `scope()` 已为 `"ALL"` 时 `"ALL"` 被自增两次（幂等，多一次 Redis 调用）。**⚠️ 未做（批次 4）**：改动要动 `scope()` 语义，收益（省一次 Redis 调用）< 风险。
- `OperationLogger.groupLog:125-129` —— `objectType/objectId` 三元判空条件逐字写两遍 → 抽局部布尔。**✅ 已做（`c1d5b2f`，批次 4）**：抽 `boolean hasObject`，条件逐字未变（纯等价重写）。
- `Result.error(int,String):69` —— `r.setData(null)` 冗余（新对象本就是 null）。**✅ 已做（`00f6e9d`，批次 1）**。
- `AsyncConfig.boundedAsyncPool:58-66` —— 未设 `setWaitForTasksToCompleteOnShutdown`/`setAwaitTerminationSeconds`（默认 false），关停时在跑的 150s 长响应被直接丢弃。**存疑**（是否有意）。
- `ExcelRawStreamReader.columnIndex:291-309` —— 可换 POI `org.apache.poi.ss.util.CellReference.convertColStringToIndex`（POI 5.2.5 已在依赖），但两者边界不同（本项目接受 `"C7"` 混合串并返 -1）。**存疑**。
- `EsConfig.java:40-41` —— 超时 5000/30000 硬编码未走配置；`RestHighLevelClient` 自 ES 7.15 起弃用，长期应迁 `co.elastic.clients.ElasticsearchClient`。
- `DataInitializationListener.java:82` —— 日志硬编码 `"tcm_ehr@localhost:3306"`，与实际 `DataSource` 无关，易误导。
- `QcRuleStore.warnings:47/204-206` —— 是**全局**「最近一次 normalize」告警，多组织并发时 `QcServiceImpl:320` 展示的告警可能来自别的组织。**存疑/健壮性**。
- `EntityNormalizer` 3 处失效 javadoc `@link`：`:231`（`EsTermNormalizer.NormalizeResult#source()` 不存在）· `:178`（`QcScorer#countUnnormalized` 为 private）· `:516`（`EsTermNormalizer#judge` 为 private）。**✅ 已做（`c1d5b2f`，批次 4）**：`:231` 改 `{@link EsTermNormalizer.NormalizeResult#level()}` + `{@link EsTermNormalizer#isHit(int)}`（`NormalizeResult` 实为 `record (String standardTerm, int level)`，**没有** `source()` 分量）；另两处 `{@link}` → `{@code}`（被引成员不可见，链接无意义）。
- `EntityNormalizer.hitsDictionary:324-334` —— 对同一条症状最多连查 symptom/pulse/tongue 三次 ES。

### 4.10 `QcRuleStore` 两处悬空 javadoc（低，本轮新增）

- **位置**：`common/config/QcRuleStore.java:60` 与 `:191`
- **证据**：全包脚本扫描「单行 javadoc 紧贴下一个 `/**`」仅命中 2 处，均在本文件 —— `:60` 的 `/** 当前生效规则 */` 悬在 `getFor` 的 javadoc（`:61-68`）之上；`:191` 的 `/** 最近一次加载/保存的告警（供前端提示） */` 悬在 `bump()` 的 javadoc（`:192`）之上。两处都是字段注释被搬走/改址后留下的残骸。
- **建议**：删除这两行悬空注释（`:191` 的那句描述的是 `:47` 的 `warnings` 字段，已在该处有注释）。

---

## 五、跨文件观察（不在单文件维度）

1. **归一主循环未收敛**：`EntityNormalizer.normEntities/normalize`（`:396-503`）与 `GovernanceServiceImpl.normalizeEntityList`（`:490-518`）/`normalizeHerbs`（`:522-546`）各实现一遍「逐实体 normalize → 命中判据 → 写 normLevel → 计数」；去重已收敛到 `dedupByTerm`，但**主循环未收敛**，且判据有差异（Governance 额外要求 `standardTerm != 原文`，且**无否定语境拦截**）。建议清洗链路复用 `EntityNormalizer.normalizeMap` 或抽公共内核。
2. **字段清单四处平行维护**：`RecordUtil.column`（19 case）、`RecordUtil.textHash`（21 字段）、`AiServiceImpl:194-214`（21 项中英映射）、`ExcelHeaderFields`（21 项表头）、`NlpTextComposer`（12 字段）。用途不同不宜硬合，但**新增字段极易漏改一处**（`RecordUtil` 注释已记录过一次「明细侧少两个 case」事故）。可考虑建单一字段注册表。
3. **空白判空三份**：`TextUtil.isBlank`（权威）、`RecordFilter.notBlank:452-454`、`LlmSecretCipher.isBlank:63-65`（后者与前者逐字相同）。
4. **`AiFeature.TERM_SUGGEST.key()` = `"termSuggest"`**（`AiFeature:22`）而 `AiController:77/82` 的 `kind` 白名单/分支用全小写 `"termsuggest"`（`case` 在 `:94`）—— 两者路径独立、当前不冲突，但**两份清单命名不一致**，易错。
5. **`VersionedCache` 注释与 `pom.xml` 注释不符**：`pom.xml:141` 称其为「Caffeine 的底层 LRU」，但 `VersionedCache` 实为 `LinkedHashMap(accessOrder=true)` 手写淘汰；真正用 Caffeine 的是 `RegexCache`。建议改 `pom.xml` 注释。

---

## 六、已做对、不要动

- **`DistLock`** —— Redisson `RLock` 薄封装（看门狗 + `TransactionSynchronization` 事务感知放锁 + fail-closed），非重复实现。
- **`RedisLua`** —— 2 段 `INCR(+PEXPIRE)` 原子脚本，标准做法，各有唯一生产调用方。
- **`JwtUtil.parseAndVerify`** —— 「验签 + 有效期 + 版本比对」并为一遍；`revokeAll` 用 `RedisLua.incrWithTtl` 原子自增、`anchorVersion` 用 `EXPIRE`（不写值）规避后写覆盖竞态 —— 有意且正确。
- **`RequestUtils` 的「组不进 JWT、每请求查一次」+ `sameOrgScope` 空对空放行口径** —— 与列表等值查询同口径，正确；`attr()` 把字面 `"null"` 也当未取到、`viewAllOrgs` 用 `rawAttr` 而非字符串化，规避了布尔标记静默失效。
- **`RecordFilter`** —— fail-closed sentinel、排序键白名单、次级键 `id` 保证分页全序、`patternUnion` 并集口径。
- **`LlmClient`** —— `NO_RETRY` 快速失败、按 `userId|版本` 缓存、装配在锁外（两段式）、`probe` 不吞异常、显式给同步+异步客户端（避免回退到已置 `none` 的自动装配）。
- **`EsTermNormalizer`** —— ES 唯一权威、失败抛 `TermIndexUnavailableException` 而非静默空、Dice 零分配原地比较、输入去重字符集一轮只算一次。
- **`AsyncTaskRegistry`** —— 显式捕获标量重建纯值上下文（不绑容器对象）、归属 org+user 校验、`RejectedExecution` 撤回登记并回 503。
- **`ExcelRawStreamReader`** —— 先落临时文件再 `OPCPackage.open(File)`、`<v>` 与内联 `<t>` 同收、`t="d"` 日期型单独识别、`disallow-doctype` 防 XXE；**手写 SAX 是为绕开 POI `DataFormatter` 把 14 位时间戳转成 `2.02212E+13` 的 bug，属刻意设计**。
- **`AsyncConfig` 的 `fixedPool` 无界队列** —— 与 `newFixedThreadPool` 语义一致，注释已说明「认领 worker 池」，有意设计。
- **`EntityTypes` 三套口径差异**（9 类目录 / 8 类有词典 / 质控计分 5 类）—— 类注释与 `QcScorer:224-230` 已逐条说明为**政策**，非缺陷。
- **`GlobalExceptionHandler`** —— `handleMediaTypeNotAcceptable` 返空 body（`:322-324`，再包 `Result` 会二次抛）、兜底分支只回追踪码（`:397-405`）、`TermIndexUnavailableException` handler 传 `e` 记栈（`:119-123`）、`MissingServletRequestPartException` 单列（`:264-268`，它 extends `ServletException` 而非 `MultipartException`）。
- **拦截器顺序**（Jwt 401 → Role 403 → OrgRole）+ OPTIONS 预检放行 —— 刻意设计。
- **`@RequireOrgRole.pathVar()` 不给默认值** —— 漏写即编译不过，根治「变量名不一致导致校验静默失效」，好设计。
- **`RedissonConfig`** —— `useSingleServer()` 二次调用不丢设置（javap 反编译确认复用同一实例），带密码分支正确。
- **`LlmConfigStore.Snapshot` 单条 volatile 引用一次性发布配置+版本**、**`saveFor` 显式回填原密文** —— 正确且优于常见写法。
- **`StatsCache` 的版本前缀失效 / TTL 抖动 / SETNX 单飞 / 空值哨兵 / fail-open** —— 领域语义，`@Cacheable` 覆盖不了（计划 #9 已论证）。
- **`EsConfig` 手工装配 ES 客户端** —— `unzip -l spring-boot-autoconfigure-4.1.1.jar | grep -i elasticsearch` 实测为空，Boot 4.1.1 **无** ES 自动装配，手工装配必要。

---

## 七、未检查 / 存疑项

**未检查**（说明理由）：
- **未跑编译/测试**：本轮是只读审查，未执行 `mvn test`；所有结论来自静态阅读 + `grep`/`javap`。
- **未做运行期验证**：按约定不为验接口另起后端（启动会写 Redis + 重建 5 个 ES 索引）。因此 **`GlobalExceptionHandler.handleBind` 是否为死 handler** 只能给静态证据，**需一次真实请求确认**。

**存疑清单**（需用户/团队拍板）：
1. 4.5 版本号放大（`LlmConfigStore`/`QcRuleStore`）—— 是否为跨实例失效的有意设计？
2. 4.9 `AsyncConfig.boundedAsyncPool` 关停丢弃在跑任务 —— 是否有意？
3. 4.9 `QcRuleStore.warnings` 全局告警的归属问题。
4. 2.9 七个空壳异常是否收敛 —— 收益 vs 编译期语义/测试改动。
5. 三、10/11/12 三处「防御性预留分支」是否保留。
6. `EntityNormalizer.normalize` 中 `vo.getHerbs()` 未判空（`:429`）—— 其余 8 类走 `normEntities` 有判空（`:456`）；若 `structured_data` JSON 显式含 `"herbs": null` 会 NPE。需确认输入来源是否可能产生显式 null。

---

## 八、第二轮复核记录（2026-10-17）

**方式**：把本文每条断言的 `文件:行号` **逐条回读原文件**复核（`sed -n` 逐段打印 + `grep -n` 定位 + 全包脚本扫描），修正行号漂移并补 1 条新发现。**本轮仍未改任何代码。**

**订正（7 处行号）**：

| 位置 | 原写 | 实测 |
|------|------|------|
| §1.5 | `DataInitializationListener:60-63` | `:59-63`（`set` 在 `:61-63`） |
| §2.8 | `handleValidation:185-188` / `handleBind:351-353` | `:174-189`（joining `:188`） / `:350-356`（joining `:353`） |
| §3.10 | `handleBind:349-356` | `:350-356` |
| §4.5 | `QcRuleStore.getFor:80` | `getFor:69`（版本串拼装在 `:80`） |
| §4.9 | `StatsCache.invalidateForWrite:102-105` | `:101-105` |
| §4.9 | `OperationLogger.groupLog:126-129` | `:125-129` |
| §5.4 | `AiController:77/83` | `:77/82`（`case` 在 `:94`） |

**新增（1 条）**：§4.10 `QcRuleStore:60` 与 `:191` 两处悬空 javadoc（全包脚本扫描仅此 2 处）。

**复核通过、未改的断言**（逐条回读确认）：

- `Result.ok():54-56` / `error(String):79-81` 全仓 0 命中；`Result.error(int,String):69` 的 `setData(null)` 冗余。
- `AsyncConfig.namedDaemon:34-41`（工厂）/`:63`/`:92`；`@EnableAsync:30`（全仓 `@Async`=0）。
- `EntityNormalizer`：三处 `backfill*`（`:552/584/625`）逐字同构；层级判据 3 处（`:327-328`/`:439`/`:474`）；`:403-405` 注释与代码顺序矛盾；`:429` `getHerbs()` 未判空（其余 8 类在 `:456` 判空）；`:324-334` `hitsDictionary`。
- `QcRuleDescriber.el:158-165` ↔ `QcRuleSet.el:204-211`；`QcRuleDescriber.fmt:167-186` ↔ `QcRuleSet.format:213-223`；年龄正则/hint `QcRuleDescriber:146-147` ↔ `QcRuleSet:151-153`；`describe:36-41` 权重被循环覆盖。
- `StructuredDataMeta` 三处（`:47/83/122`）；`isManuallyEdited:151-166` 主代码 0 调用；`ReviewTaskUtil.fingerprint:45-57`。
- 401/403 三处：`JwtInterceptor:106/108`、`OrgRoleInterceptor:83/85`、`RoleInterceptor:74/76`。
- `GlobalExceptionHandler` 全部 handler 行号（`:47/58/94/150/161/386` 等）与 `handleMediaTypeNotAcceptable:322-324`。
- `exception/` 11 个类行数 14/42/20/12/406/15/19/23/19/18/15 —— 与 §2.9 完全一致。
- `RoleInterceptor:53-55`/`:57`；`RequireRole:23`；`WebMvcConfig:33/36/40`；`EntityTypes.all():109-111` / `dictKeys():114-126`。
- `JwtUtil.getKey():62-64` / `isValid():138-140`；`LlmConfigStore.version():221-223` / `get():216-218` / `saveFor:153-154` / `versionOf:177-189`。
- `QcRuleStore.get():187-189` / `rowCache:45` / `rowFor:94-103`；`StatsCache.KEY_PREFIX:50`；`QcScorer` 默认集调用 `:36/144`。
- `TextUtil.isBlank:28-30`；`LlmSecretCipher.isBlank:63-65`；`AsyncTaskRegistry.prune:302-313` / `safeUserId:316-322`；`RequestUtils.safeCurrentRole:72-78` / `safeCurrentOrgRole:81-87` / `attr:178-193`。
- `NlpTextComposer` 12 字段；`AiServiceImpl.completeness:194-214` 21 字段；`ExcelHeaderFields` 位于 `service/impl/ExcelHeaderFields.java`。
- `StandardizationReportServiceImpl:476/507/564/582`；`DictionaryTermStoreImpl:492/525`；`GovernanceServiceImpl.normalizeEntityList:490` / `normalizeHerbs:522` / `prefetchPage:402`。
- `pom.xml:141`（Caffeine 注释与实现不符）；`EsConfig` 超时 `:39-41`；`EsTermNormalizer.scan:227-233`（`out` 为 `LinkedHashSet`，`:207`）。
- `RecordFilter.build(SearchDTO):54-139` / `build(FiltersDTO):406-450` / `notBlank:452-454` / `text:322-329`。

**仍未验证**（同 §七）：编译/测试与运行期行为未跑 —— 只读审查约束，故 `handleBind` 死否仍待一次真实请求确认。

---

## 九、执行记录

> 按用户 2026-10-17 裁定「分批执行」。每批独立提交、独立跑 `mvn -o test`。

### 批次 1 —— 已完成（提交 `00f6e9d`，10 文件 +21/-57，`mvn -o test` = 556 / 0 / 0 全绿）

**零行为改动**。已落地：

| 报告条目 | 落地内容 |
|----------|----------|
| §三.1 | 删 `Result.ok()`、`Result.error(String)`；`error(int,String)` 去掉冗余 `setData(null)` |
| §三.2 | 删 `LlmClient.isEnabled()` |
| §三.7 | 删 `AiFeature` 的 `label` 字段 + `label()`（`.label()` 命中全为 `EntityType`） |
| §三.8 | 删 `AsyncConfig.@EnableAsync` + 其 import（全仓 `@Async` = 0） |
| §三.9 | 删 `EsTermNormalizer.scan:227-233`（`containsAnyAlias` 是双向包含、为 `input.contains(a)` 的超集 → 该块对 `LinkedHashSet` 恒幂等空转） |
| §三.13 | `StatsCache.KEY_PREFIX` / `LlmConfigStore.version()` / `QcRuleStore.get()` → `private` |
| §4.10 | 删 `QcRuleStore:60` / `:191` 两处悬空 javadoc |
| §4.1 | `EntityNormalizer:403-405` **改注释**（与代码一致：预取在最前）。**未挪代码** —— 挪到 1.6 之后会让 1.6 的查询全部落回单条 ES，反而更慢 |
| §4.2 | `QcRuleDescriber.describe` 完整性扣分档位改为取首要素（原循环内逐次覆盖只留最后一个；默认各要素同权重 → 输出不变） |

### 批次 2 —— 已完成（提交 `fb0c0c9`，17 文件 +229/-192，`mvn -o test` = 556 / 0 / 0 全绿）

**零行为改动**。落地情况分「已做」与「评估后未做」两栏 —— 未做项是执行时按「简洁优先 / 不引入复杂模式 / 不改可见行为」三条判据重新权衡的结果，**不是遗漏**。

**已做**：

| 报告条目 | 落地内容 |
|----------|----------|
| §2.2 | `QcRuleSet` 提 `AGE_PATTERN` / `AGE_HINT` 常量（原 `defaults()` 与 `catalogFormats()` 各抄一份，有漂移风险）；`el` / `format` 工厂改**包级可见**并补 `hint` 参数，`QcRuleDescriber` 删掉自己那份 `el` / `fmt`（2 个重载）改调之 |
| §2.3 | `StructuredDataMeta` 抽 `metaOf(dictVersion,termCount)` / `metaOfManual(editedBy)`，三处 meta 装配合一 |
| §2.4（部分） | 抽 `applyRange(wrapper, column, range)`；两条 build 路径共 4 处「时间区间」块合一（`visit_time` / `create_time` 判据逐字相同，只是列名不同） |
| §2.5 | 删 `RequestUtils.safeCurrentRole` / `safeCurrentOrgRole`（`attr()` 内部已整体 try/catch 恒返回非 null → safe 版 catch 永不触发、返回值恒等）；`AsyncTaskRegistry:150-152` 改调非 safe 版 |
| §2.6 | 新增 `common/utils/DigestUtil.hex(algo, text)`；`RecordUtil.md5Hex` 与 `ReviewTaskUtil.fingerprint` 改调，两处不再各写一份 `MessageDigest → HexFormat → NoSuchAlgorithmException 兜底` |
| §2.7 | 新增**包内** `common/interceptors/JsonResponses.write(objectMapper, response, status, msg)`；`JwtInterceptor`(401) / `RoleInterceptor`(403) / `OrgRoleInterceptor`(403) 三处 `reject` 改调（各留一个一行私有包装，保住调用点可读性） |
| §2.8（部分） | `GlobalExceptionHandler` 抽 `error(HttpStatus,int,String)`，替换 20+ 处 `ResponseEntity.status(..).body(Result.error(..))` 样板（含 `badRequest()` 与 `status(e.getStatus())` 两种写法归一） |
| §2.10 | `WebMvcConfig` 白名单提 `private static final String[] WHITELIST`，三处 `excludePathPatterns` 复用 |
| §2.11 | `EsTermNormalizer` 新增 `isHit(int level)`；`EntityNormalizer` 3 处层级区间判据（`:327`、`:441`、`:476`，后两处取反）改调 |

**评估后未做（附理由）**：

| 报告条目 | 未做理由 |
|----------|----------|
| §2.1 `backfill*` 泛型化 | 三个方法虽逐字同构，但差异点是**字段访问器**（`Herb::getName` vs `Entity::getContent`）、**元素构造器**（要同时 set 名称与 sourceText）、**列表存取器**与 **type key**。抽象出来需要 7~9 个参数 + 3~4 个函数式接口，属「过度封装」——三个原方法各 ~30 行、读起来直观，抽象后反而难读。**保留现状** |
| §2.4 全量「`SearchDTO` 归一成 `FiltersDTO`」 | **前提不成立**：`SearchDTO` 有 `registrationNo` / `outpatientNo` / `gender` / `doctorId` / `status` 五个 `FiltersDTO` **没有**的字段（`FiltersDTO` 另有 `ids` 是 `SearchDTO` 没有的）。要归一必须先给 `FiltersDTO` 补 5 个字段 —— 而 `FiltersDTO` 是 stats / 导出契约的一部分，改它等于动契约，风险远大于去重收益。**只做了其中安全的一半（时间区间）** |
| §2.8 `joinFieldErrors` 合并 | `handleValidation`（`:185-188`）用「`字段：消息`」+ `.distinct()`，`handleBind`（`:351-353`）用「`字段 消息`」且不去重 —— **两者格式本就不同**，合并必然改掉其中一个的可见文案（`handleBind` 罕见触发但确实可达）。只合并了 `error(...)` 外壳 |
| §2.9 7 个空壳异常收敛 | 原就标注「可合并但需权衡」，留待用户拍板（见 §七） |

> **另记（本轮未动、建议纳入后续批次）**：`JwtInterceptor:93-94` 两次计算 `RequestUtils.ROLE_ADMIN.equals(role)`（可提一个局部 `isAdmin`）；`OrgRoleInterceptor.roleLabel:78-80` 硬编码字面量 `"owner"` 而非 `RequestUtils.ORG_ROLE_OWNER`。二者均不在 §二 清单内，属本轮新发现，**未擅自改动**。

### 批次 3 —— 已完成（提交 `b0a3aa6`，3 文件 +39/-32，`mvn -o test` = 556 / 0 / 0 全绿）

**零行为改动**。3 项已做、1 项（§1.1）判定不做。

**已做**：

| 报告条目 | 落地内容 | 实测验证方式 |
|----------|----------|--------------|
| §1.2 | 删自写 `namedDaemon`（`ThreadFactory` + `AtomicInteger`，8 行）；两个池改 `setThreadNamePrefix(prefix)` + `setDaemon(true)`。**javap 修正了报告原假设**：`setDaemon` 不在 `ExecutorConfigurationSupport` 上，而在其祖先 `CustomizableThreadCreator`（链为 `ExecutorConfigurationSupport extends CustomizableThreadFactory extends CustomizableThreadCreator`），故仍可继承调用 | 临时用例实测 5 个池的线程名与守护位：`ai-task-1` / `nlp-task-1` / `qc-batch-worker-1` / `nlp-batch-worker-1` / `nlp-record-worker-1`，`daemon` 全 `true` —— 与原先逐字一致 |
| §1.5 | Redis 探活由「写一个 10s TTL 的 `tcm:…:startup:ping`」改为 `redisTemplate.execute((RedisCallback<String>) RedisConnection::ping)`，消除启动期的无谓写入 | 临时用例实测 `run()` 只发生**一次** Redis 交互（即 PING），`opsForValue()` **从未被调用** |
| §1.3 | `QcRuleStore.rowCache` 手写 TTL Map（`RowCacheEntry` record + `ConcurrentHashMap` + 读时比对 `expiresAtMs`）→ `Caffeine.expireAfterWrite(5s)`；`rowCache.remove` → `invalidate` | 临时用例实测无行组织连续 3 次 `getFor` 只回源 **1** 次；`updateFor` 后读取重新回源 |

**§1.3 的两个关键取舍（执行时才发现，已写进代码注释）**：

1. **值必须用 `Optional` 包一层** —— Caffeine **不接受 null 值**（映射函数返回 null 时不写缓存）。而「该组织没有行」在原实现里是**有意缓存**的稳定事实；若直接存 `QcRule`，无行组织会退化成每次 `selectById`，批量质控 4 万条 = 4 万次查询 —— 正是 T14 要消掉的开销。这是本条**唯一的真实坑**，报告原建议里没提到。
2. **刻意不设 `maximumSize`** —— 报告原建议写了 `maximumSize(N)`，但原实现**无上界**，键空间 = 组织数（很小）。加上界会引入当前没有的逐出，让本可命中的查询多打一次库，与「零行为改动」冲突。故只留 `expireAfterWrite`。

**评估后未做**：

| 报告条目 | 未做理由 |
|----------|----------|
| §1.1 `RecordUtil.md5Hex` → Spring `DigestUtils.md5DigestAsHex` | **与批次 2 的 §2.6 冲突**：批次 2 已把 `md5Hex` 与 `fingerprint` 收敛到 `DigestUtil.hex(algo,text)`。若本项再让 `md5Hex` 走 Spring，`DigestUtil` 就只剩 `fingerprint` 一个调用方，而 Spring `DigestUtils` **只有 MD5 系列、无 SHA-256**（javap 实证），无法把两者统一到 Spring —— **两条各做一半比现状更差**。故放弃本项、保留 `DigestUtil` 两用（`DigestUtil` 的实现只是 3 行 JDK API，本身不构成「自研组件」） |

> **验证方式说明**：批次 3 的三项都是「实现替换、行为应等价」，故没有靠读代码下结论 —— 每项都写了**临时用例实测后删除**（保持用例总数仍为 556，便于与批次 1/2 的基线对比）。若日后要复验，断言就是上表「实测验证方式」列的内容。

### 批次 4 —— 已完成（提交 `c1d5b2f`，10 文件 +141/-105，`mvn -o test` = 556 / 0 / 0 全绿）

**主体零行为改动**，另含 **2 处「修好方向」的小改动**（§4.3 删临时文件不再覆盖真实异常、§4.8 Key 缓存），两处都在下面单独说明。

**已做**：

| 报告条目 | 落地内容 | 验证方式 |
|----------|----------|----------|
| §三.3 | 删 `LlmClient.provider()` / `chat(String,String)` / `chat(String)`（生产代码 0 调用）；javadoc 里 `{@link #chat(String, String)}` 已失效，改指 `{@link #chat(AiFeature, String, String)}`，`probe()` 的 `{@link #chat}` 同步写全签名 | `LlmClientTest` 6 处调用改走 3 参（1 参调用 → `chat(null, null, x)`、2 参 → `chat(null, sys, user)`），**断言语义逐条不变**；`provider()` 的断言删除（同一用例的 `isAvailable()` 已覆盖「ollama 通道装配成功」）。全量 556 全绿 |
| §三.4 | 删 `JwtUtil.isValid(String)`（生产入口 `JwtInterceptor` 走 `parseAndVerify`）；`:112` javadoc 的 `{@link #isValid}` 改写为「先验签再 `parseToken`」 | `JwtRevokeTest` 12 处 `u.isValid(x)` → `assertNotNull`/`assertNull(u.parseAndVerify(x))`（8 true / 4 false），断言语义逐条不变；顺带删掉因此不再使用的 `assertFalse` import |
| §4.8 | `JwtUtil.getKey()` 结果缓存到 `private volatile Key cachedKey`（懒初始化） | **临时用例实测**：同一实例两次 `getKey()` 返回**同一 Key 对象**、`cachedKey` 字段被填上 |
| §4.7 | `EntityTypes.all()` / `dictKeys()` 改返回缓存不可变视图（`ALL_VIEW` / `DICT_KEYS`，建在原有 static 块里） | **临时用例实测**：两次调用 `assertSame`；`dictKeys()` 顺序 == `all()` 过滤结果；两者 `add` 均抛 `UnsupportedOperationException`。既有 `EntityTypesDictionaryRegistrationTest`（含 25.b 防漂移断言）仍绿 |
| §4.6 | `QcRuleSet` 抽包级 `defaultCompleteness()` / `defaultStandardization()`；`defaults()` 与 `QcRuleStore.normalize:265/290` 共用 | **临时用例实测**：两次 `defaults()` 的 `Completeness`/`Standardization` **不是同一实例**；6 要素 / `elementTypes` 顺序与内容 / `weightEach=1` / `cap=5` / `enabled=true` 逐项等于原值 |
| §4.3 | ① 样式回表只取一次（存 `CellStyle st`）；② `finally` 里 `Files.deleteIfExists` 包 try/catch，删除失败不再覆盖真实读取异常 | 既有 `ExcelRawStreamReaderTest` 全绿（两条改动都在 `forEachXlsxRow` 主路径上） |
| §4.9 | `OperationLogger.groupLog` 把逐字写两遍的判空抽成局部 `boolean hasObject` | 纯等价重写（条件逐字未变，只是复用）；既有审计日志用例全绿 |
| §4.9 | `EntityNormalizer` 3 处失效 `@link` 修正 | 判据同报告：`:231` 的 `NormalizeResult#source()` 分量**不存在**（实为 `record (String standardTerm, int level)`），改指 `#level()` + `#isHit(int)`；`:178` `QcScorer#countUnnormalized`、`:518` `EsTermNormalizer#judge` 均为 `private`，`{@link}` → `{@code}` |

**§4.3 的两处偏离报告原建议（执行时才发现）**：

1. **`:275-278` 顺带堵了一个潜在 NPE**：`StylesTable.getStyleAt(idx)` 对越界索引返回 **null**（不是抛异常），原写法 `styles.getStyleAt(styleIdx).getDataFormat()` 会 NPE。存局部变量后自然写成 `st != null &&`，越界样式索引从「NPE」变成「按非日期格式处理」。这是**修好**、不是改坏，但确实不是「逐字等价」，故记在此。
2. **`:101-104` 的取舍**：报告建议「catch 后仅记日志或 `addSuppressed`」。本类**没有 logger**（不是 `@Slf4j`），为一行删除失败引一个静态 logger 不划算；`addSuppressed` 需要把主异常在 catch 里存成局部变量、结构要拆两处。故采用「catch 后吞掉 + 注释说明」。**副作用**：原来「读取成功但删临时文件失败」会让整个导入抛 IOException，现在会成功 —— 残留一个临时文件不该让导入失败，属修好方向。

**评估后未做**：

| 报告条目 | 未做理由 |
|----------|----------|
| §4.6 的「缓存为**常量**」 | 改成了「抽方法、每次返回新对象」。**理由**：`Element` / `Standardization` 都是可变 `@Data`，而 `QcRuleStore.normalize` 会把取到的子对象**直接 set 进生效规则集**（`current.completeness = …`）—— 共享常量等于把一个可变全局对象挂进业务状态，日后谁 `getElements().add(…)` 就是全局污染。`EntityTypes` 自己就写着「构建完包成不可变后才赋给 static」这条约定。抽方法已消掉报告要消的那份浪费（原先为取一个子对象要构造 14 条一致性规则 + 2 条格式规则再丢掉） |
| §4.4 `AsyncTaskRegistry.prune` 全表扫描 | 报告自评「低优先」。改动要换数据结构（`ConcurrentHashMap` → `TreeMap`/环形），会动 `submit` 的并发语义；而当前量级 `MAX_TASKS=2000`、单次排序约 2×10⁴ 次比较，可忽略。**不为它引入新的并发风险** |
| §4.9 `StatsCache.invalidateForWrite` 双重自增 | 幂等（多一次 Redis 调用），改动要动 `scope()` 语义，收益 < 风险，留观察 |
| §4.9 `columnIndex` → POI `CellReference` / `EsConfig` 硬编码超时 / `DataInitializationListener:82` 日志硬编码 / `QcRuleStore.warnings` 归属 / `AsyncConfig` 关停丢弃 / `EntityNormalizer:429` | 均为**存疑项**或需改可见行为，见下「需用户拍板」 |

> **验证方式说明**：§4.6 / §4.7 / §4.8 三项是「实现替换、行为应等价」，故没有靠读代码下结论 —— 写了**临时用例实测后删除**（保持用例总数仍为 556，便于与批次 1/2/3 的基线对比）。§三.3 / §三.4 的删除由既有测试（`LlmClientTest` / `JwtRevokeTest`）覆盖；§4.3 / §4.9 由既有用例覆盖。
>
> **本轮新发现（未擅自改，记此）**：`QcRuleSet` 类头 javadoc（`:11-12`）与 `defaults()` javadoc 仍写「运行时可被 `data/qc-rules.json` 覆盖，字段级深合并」，但该文件在批次 25.a 已删（`QcRuleStore` 类头自己就写着「该文件从来不存在」）—— 两处 javadoc 是**残留失真**。属「文档与代码不一致」，且 `QcRuleSet` 的类头 javadoc 正是本条（§4.6）所在文件的说明，建议并入后续批次或拍板项。

### 批次 5 —— 已完成（提交 `b21477c`，7 文件 +45/-31，`mvn -o test` = 556 / 0 / 0 全绿）

**裁定口径**（用户 2026-10-17 授权「你帮我决定，不要影响出错而影响现在的项目的运行」）：**只做「证明为零行为改动」的项**；凡「可能改变可见行为 / 涉及并发与关停语义 / 需运行期确认 / 属净减防御」的，一律保留现状。故本批 6 项全部是注释、日志文案、判空口径收敛与 1 处 NPE 补漏。

**已做**：

| 报告条目 | 落地内容 |
|----------|----------|
| §九 批次 4 新发现 | `QcRuleSet` 类头 javadoc 与 `defaults()` javadoc 删掉「可被 `data/qc-rules.json` 覆盖」的失效说明，改述「运行时权威是 DB」 |
| §五.5 | `pom.xml` Caffeine 注释订正：实际使用方是 `RegexCache`（正则编译备忘）与 `QcRuleStore`（组织规则行短 TTL）；`VersionedCache` 是自写 `LinkedHashMap(accessOrder=true)`（实测确认，不是 Caffeine） |
| §4.9 | `AsyncConfig` 类头补注释：关停不等在跑任务（`setWaitForTasksToCompleteOnShutdown` 默认 false）是**有意取舍** —— 150s 长响应宁可丢也不阻塞关停 |
| §4.9 | `DataInitializationListener` 启动日志不再写死 `"tcm_ehr@localhost:3306"`（与实际 `DataSource` 无关，排查连接问题时会误导） |
| §五.3 | `LlmSecretCipher.isBlank`（与 `TextUtil.isBlank` **逐字相同**）与 `RecordFilter.notBlank`（极性等价）收敛到 `TextUtil.isBlank` |
| §七.6 | `EntityNormalizer.normalize` 的 `herbs` 遍历补判空 —— 同一 VO 在 `prefetchRecall` 已判空、其余 8 类走 `normEntities` 也判空，**只有这一处漏**；`structured_data` 缺 `herbs` 键即 NPE（500） |

### 存疑项裁定（2026-10-17，用户授权由我决定）

| 存疑项 | 裁定 | 理由 |
|--------|------|------|
| §4.5 版本号放大（`LlmConfigStore` / `QcRuleStore`） | **不做** | 改按 key 维度维护版本 = 重写缓存失效逻辑。写错的后果是「改了配置不生效」（静默、极难查），收益只是省几次重算 —— 风险与收益完全不成比例 |
| §4.9 `AsyncConfig` 关停丢弃在跑任务 | **不做，只补注释** | 改成「等跑完」会让关停阻塞到 150s 长响应结束。「丢还是等」是运维取舍，不是缺陷；已在类头写明是有意取舍 |
| §4.9 `QcRuleStore.warnings` 全局告警归属 | **不做** | 改成按组织存要动数据结构 + `QcServiceImpl:320` 的读取口径 → **会改变前端看到的告警内容**，属可见行为改动 |
| §2.9 七个空壳异常是否收敛 | **不做** | 收敛会改异常类名 → 改 `GlobalExceptionHandler` 映射 → 改 HTTP 状态与错误文案，属可见行为改动；且七者各自语义清晰（`BadCredentials` / `ResourceNotFound` …），合掉反而丢信息 |
| §三.10 `GlobalExceptionHandler.handleBind` 是否死 handler | **不做** | 报告自己标注「需一次真实请求确认」。既然要「不影响现在能跑的系统」，运行期无法验证就不动 |
| §三.11 `RoleInterceptor` 类级分支 / 空 roles 分支 | **不做** | 删掉后若有人在类上加 `@RequireRole`，鉴权会**静默失效**（安全漏洞）。属「净减防御」，不能做 |
| §三.12 `RequireRole.@Target(TYPE)` | **不做** | 与 §三.11 一体两面，同上 |
| §五.1 归一主循环未收敛 | **不做** | 报告自己指出两者判据**有差异**（Governance 额外要求 `standardTerm != 原文`，且无否定语境拦截）→ 合并必然改行为，且是主链路大重构 |
| §五.2 字段清单四处平行维护 | **不做** | 建单一字段注册表要同时动 `RecordUtil.column` / `textHash` / `AiServiceImpl` 映射 / `ExcelHeaderFields` / `NlpTextComposer` 五处，属大重构，漏一处就是数据错 |
| §五.4 `AiFeature.key()` 与 `AiController` 命名不一致 | **不做** | 改任一侧都动 `/api/ai/async/{kind}` 白名单 = **改接口契约** |
| §4.9 `columnIndex` → POI `CellReference` | **不做** | 报告自己写「两者边界不同」（本项目接受 `"C7"` 混合串并返 -1）→ 换实现会改边界行为 |
| §4.9 `EsConfig` 超时硬编码 / `RestHighLevelClient` 迁移 | **不做** | 前者要新增配置面；后者是 ES 客户端依赖大迁移。都不该在「不动能跑的系统」前提下做 |
| §4.9 `StatsCache.invalidateForWrite` 双重自增 | **不做** | 幂等（多一次 Redis 调用），改动要动 `scope()` 语义，收益 < 风险 |
| §七.6 `EntityNormalizer:429` `getHerbs()` 未判空 | **✅ 做** | 不是「过度防御」：这是**口径不一致的漏网**（同一 VO 两处已判空、8 类已判空，只有这处漏）。补判空只会把「崩（500）」变「跳过」，方向是减少出错 |
| §五.3 空白判空三份 | **✅ 做** | 两处实现逐字相同 / 极性等价，收敛后 556 全绿 |
| §五.5 `pom.xml` Caffeine 注释 | **✅ 做** | 纯注释订正，实测证据在 |
| §九 批次 4 新发现（`QcRuleSet` 两处 javadoc） | **✅ 做** | 纯注释订正，硬证据在（该文件批次 25.a 已删） |
| §4.9 `DataInitializationListener:82` 日志硬编码 | **✅ 做** | 只改日志文案，去掉写死地址 |

> **一句话总结**：**改的 6 项都不改变任何对外行为**（注释 / 日志文案 / 等价实现收敛 / 一处 NPE 补漏）；**没改的 12 项**全是「改了就可能悄悄改变行为或削弱防御」的 —— 按你的要求，宁可留着。
