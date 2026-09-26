# 中医电子病历质控与标准化系统 — 代码审查报告

> 依据 `docs/AI代码审查提示词.md` 执行。**全程只读**：未修改任何代码 / 配置 / 数据库，未提交、未推送、未重启用户服务。
> 审查时间：2026-09-25 22:10 ~ 23:10。基线提交 `b4899a2`。

## 审查说明

| 项       | 内容                                                                                                                                                              |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 审查对象 | 后端 `java-backend/`（146 个 .java）、前端 `frontend/src/`（25 .vue + 20 .js）、NLP `python-nlp/`（2 个 .py）、`data/`、`database-init.sql`、`docs/`、`README.md` |
| 契约基准 | `docs/中医电子病历质控与标准化系统-openapi.yaml`（46 路径 / 50 操作）                                                                                             |
| 工具链   | JDK 17（`F:\jdk17`）、Maven 3.9.16、Node 22.22.2、vite 6.4.3                                                                                                      |
| 编译     | 后端 `mvn -o -f java-backend/pom.xml compile`；前端 `vite build`                                                                                                  |
| 未做     | 未启第二个后端实例（启动会写 Redis 并重建 5 个 ES 索引，属写操作）；未改库、未改配置、未提交                                                                      |
| 说明     | 提示词中写 `JAVA_HOME=F:\jdk24`，实际 `pom.xml` 的 `java.version=17`，按 JDK 17 执行                                                                              |

## 结论概览

- **编译**：后端主源码通过；**测试源码编译失败（44 处 / 6 个文件）**，`mvn -o test` 直接 BUILD FAILURE，74 个用例全部不可执行。
- **构建**：前端 `vite build` 通过（2289 模块 / 9.6s），仅 1 条既有的 chunk >700 kB 警告。
- **契约**：openapi 50 个操作 ↔ Controller 50 个路由映射**一一对应，无缺无余**；50/50 均带 `【权限：…】`，且与 `@RequireRole` 逐条一致。3 个操作前端未调用（见 §2.1）。
- **问题总量**：**阻断 2 / 高 8 / 中 16 / 低 12**，共 38 条。

---

## 一、问题清单

### 阻断

#### Z1 测试源码编译失败，74 个用例全部无法运行

- **位置**：`java-backend/src/test/java/com/tcm/ehr/` 下 6 个文件 44 处
- **证据**（`mvn -o -f java-backend/pom.xml test` 原文摘录）：

```
[ERROR] /D:/ZISHIKU/AI/tcm-ehr-qc/java-backend/src/test/java/com/tcm/ehr/common/utils/QcScorerTest.java:[38,36] 无法将类 com.tcm.ehr.common.utils.QcScorer中的方法 score应用到给定类型;
[ERROR]   需要: java.util.Map<java.lang.String,java.lang.Object>,com.tcm.ehr.domain.po.Record,boolean,com.tcm.ehr.common.config.QcRuleSet
[ERROR]   找到:    java.util.Map<java.lang.Object,java.lang.Object>,com.tcm.ehr.domain.po.Record,boolean
[ERROR]   原因: 实际参数列表和形式参数列表长度不同
[ERROR] /D:/ZISHIKU/AI/tcm-ehr-qc/java-backend/src/test/java/com/tcm/ehr/common/utils/QcScorerTest.java:[80,91] 找不到符号
[ERROR]   符号:   变量 TYPE_TREATMENT
[ERROR]   位置: 类 com.tcm.ehr.common.utils.LogicChecker
[ERROR] /D:/ZISHIKU/AI/tcm-ehr-qc/java-backend/src/test/java/com/tcm/ehr/controller/NlpControllerTest.java:[34,16] 无法将类 com.tcm.ehr.controller.NlpController中的构造器 NlpController应用到给定类型;
[ERROR]   需要: com.tcm.ehr.common.utils.PythonNlpClient,com.tcm.ehr.common.utils.EntityNormalizer,com.tcm.ehr.service.INlpBatchService,com.tcm.ehr.common.utils.OperationLogger
[ERROR]   找到:    com.tcm.ehr.common.utils.PythonNlpClient,com.tcm.ehr.common.utils.EntityNormalizer
[ERROR] /D:/ZISHIKU/AI/tcm-ehr-qc/java-backend/src/test/java/com/tcm/ehr/service/DictionaryImportTest.java:[65,42] 无法将类 com.tcm.ehr.common.config.LlmConfigStore中的构造器 LlmConfigStore应用到给定类型;
[ERROR]   需要: com.tcm.ehr.common.config.LlmProperties,tools.jackson.databind.ObjectMapper
[ERROR]   找到:    com.tcm.ehr.common.config.LlmProperties
[ERROR] /D:/ZISHIKU/AI/tcm-ehr-qc/java-backend/src/test/java/com/tcm/ehr/service/ReviewServiceTest.java:[44,19] 无法将类 com.tcm.ehr.service.impl.ReviewServiceImpl中的构造器 ReviewServiceImpl应用到给定类型;
[ERROR]   需要: com.tcm.ehr.mapper.RecordMapper,tools.jackson.databind.ObjectMapper,com.tcm.ehr.common.config.QcRuleStore
[ERROR]   找到:    com.tcm.ehr.mapper.RecordMapper,tools.jackson.databind.ObjectMapper
[INFO] BUILD FAILURE
```

| 测试文件                             | 错误数 | 出错行                             |
| ------------------------------------ | ------ | ---------------------------------- |
| `common/utils/LogicCheckerTest.java` | 18     | 17, 25, 28, 33, 36, 41, 43, 48, 51 |
| `common/utils/QcScorerTest.java`     | 14     | 38, 48, 55, 66, 77, 80, 81         |
| `common/utils/LlmClientTest.java`    | 6      | 31, 92, 107                        |
| `controller/NlpControllerTest.java`  | 2      | 34                                 |
| `service/DictionaryImportTest.java`  | 2      | 65                                 |
| `service/ReviewServiceTest.java`     | 2      | 44                                 |

- **根因**：生产侧签名在后续批次中变更，测试未同步。
  - `QcScorer.score(Map, Record, boolean)` → 增第 4 参 `QcRuleSet`（批Q 规则可配置）
  - `LogicChecker.TYPE_TREATMENT` / `TYPE_FORMULA` 常量被删（批S 一致性规则泛化为 `{触发类型, 期望类型, 触发值[], 期望值[]}`）
  - `NlpController` 构造器增 `INlpBatchService` / `OperationLogger`（批K 批量解析）
  - `LlmConfigStore` 构造器增 `ObjectMapper`（批I 配置持久化）
  - `ReviewServiceImpl` 构造器增 `QcRuleStore`（批Q）
- **影响**：**回归命令 `mvn -o test` 已失效**。评分（`QcScorer`）、逻辑校验（`LogicChecker`）、鉴权（`AuthControllerTest`/`AuthServiceTest`）、导入（`DictionaryImportTest`）、复核（`ReviewServiceTest`）这些关键逻辑**当前没有任何自动化验证**。之后每一处改动都只能靠手工点页面。这同时解释了为什么"74 项全绿"的口径会与现状脱节。
- **修复建议**：按上表逐处对齐构造器与静态方法签名；`LogicCheckerTest` 改为按 `QcRuleSet.ConsistencyRule` 构造用例数据。**修完必须重跑 `mvn -o test` 并确认 74 项通过**，再继续任何功能改动。

#### Z2 统计接口无数据域过滤，审核员可读到全库病历的证型 / 方剂词频

- **位置**：`service/impl/StatsServiceImpl.java:77`、`service/impl/StatsServiceImpl.java:241`、`mapper/RecordMapper.java:27`
- **证据**：

```java
// StatsServiceImpl.java:67-79  stats(StatsDTO) —— 无筛选条件时
} else {
    records = baseMapper.selectList(null);          // ← 全表，无数据域
}
return statsFor(records, dto.getType());
```

```java
// StatsServiceImpl.java:227-252  filterByFilters() —— 先全表载入再内存过滤
return baseMapper.selectList(null).stream()
        .filter(r -> dep == null || dep.equals(r.getDepartment()))
        ...
```

```java
// RecordMapper.java:21-29  selectOverview() —— 裸 SQL，无任何域条件
@Select("""
        SELECT
            COUNT(*) AS totalRecords,
            ...
        FROM records
        """)
Map<String, Object> selectOverview();
```

```java
// common/utils/RecordFilter.java:9-12（本仓库的既定约定）
 * 所有病历读取（查询 / 原始查看 / 后续图谱等）统一走本工具，禁止在业务方法中手写 where，
 * 避免口径漂移与"筛选条件绕过数据域"的越权。
```

- **影响**：`/api/stats`、`/api/stats/overview` 的权限档位是「登录即可」，审核员可直接调用。`StatsServiceImpl.recordsFor()`（:184）已正确走 `RecordFilter`，但 `overview()` 与 `stats(StatsDTO)` 这两条路径绕开了它 —— 审核员能拿到**非「待复核」域**病历的中医诊断、证候、方剂、中药词频。`AiServiceImpl.java:303` 又把这个全库 `overview()` 喂进 AI 上下文，越权面进一步扩大。同时 `selectList(null)` 在 3.5 万条数据集上会把整表读进内存。
- **修复建议**：`overview()` / `filterByFilters()` 改走 `RecordFilter.build(RequestUtils.currentRole(), …)`；`selectOverview()` 增加按角色拼条件的聚合 SQL（或按角色分流两条 SQL）；`filterByFilters()` 的 `selectList(null).stream()` 换成带条件的 `selectList(wrapper)`。

### 高

#### G1 「核心要素」口径三处分裂：契约 5 项、实现评 6 项、AI 报 5 项

- **位置**：`common/config/QcRuleSet.java:109-113`、`service/impl/AiServiceImpl.java:218-227`、`docs/中医电子病历质控与标准化系统-openapi.yaml:947`
- **证据**：

```java
// QcRuleSet.java:105-113 —— 实际参与评分的要素
public static QcRuleSet defaults() {
    QcRuleSet r = new QcRuleSet();

    // 完整性 6 要素（症状/疾病/证候/舌象/脉象/中药），取自实体类型目录
    for (String key : List.of("symptom", "disease", "pattern", "tongue", "pulse", "herb")) {
```

```java
// AiServiceImpl.java:218-227 —— AI 解读/助手侧的「核心字段缺失」
/** 核心要素缺失（5 项；真缺失口径，结构化为空且原始列也空；症状无原始列） */
private List<String> coreMissing(Map<String, Object> data, Record r) {
    List<String> missing = new ArrayList<>();
    if (listEmpty(data, "symptoms")) missing.add("症状");
    if (listEmpty(data, "patternList") && blank(r.getPattern())) missing.add("证候");
    if (listEmpty(data, "tongueList") && blank(r.getTongue())) missing.add("舌象");
    if (listEmpty(data, "pulseList") && blank(r.getPulse())) missing.add("脉象");
    if (listEmpty(data, "herbs") && blank(r.getPrescription())) missing.add("中药");
    return missing;
}
```

```yaml
# openapi.yaml:947
description: 评分响应（出参；满分100，最低0）。核心要素 5 项（症状/证候/舌象/脉象/中药），判定以结构化结果为准；治法、方剂不参与评分
```

- **影响**：`QcScorer.java:35-45` 按 `rs.getCompleteness().getElements()` 逐项生成 `"核心字段缺失"` 扣分，因此**缺「中医诊断」的病历会扣分（-6 或 -12）**，而 AI 解读与 AI 助手的 `coreMissing` 不含「疾病」，会回答「核心字段齐全」。同一份病历，质控页说缺、AI 页说齐 —— 用户会直接认定其中一个在骗人。契约（openapi）说 5 项，实现是 6 项，属契约不一致。
- **修复建议**：三处收敛为一份。`coreMissing()` 改为遍历 `qcRuleStore.get().getCompleteness().getElements()` 生成；openapi 与 `功能设计文档.md:708/1237`、`项目设计文档.md:94`、`待办计划.md:24` 的「5 项」表述同步改为按目录动态（或明确写 6 项）。

#### G2 全仓库零事务，多处多写操作无原子性

- **位置**：`service/impl/RecordServiceImpl.java:397-401`、`service/impl/ReviewServiceImpl.java:117-149`、`service/impl/DictionaryServiceImpl.java:105-107`、`service/impl/QcServiceImpl.java:195-197`
- **证据**（`grep -rn "@Transactional" java-backend/src/main/java | wc -l` → **0**）：

```java
// RecordServiceImpl.java:390-404 —— 先删子表、再删主表，两段写无事务
private DeleteRecordsVO doDelete(List<String> ids) {
    ...
    for (int i = 0; i < ids.size(); i += DELETE_CHUNK) {
        List<String> chunk = ids.subList(i, Math.min(i + DELETE_CHUNK, ids.size()));
        reviewTaskMapper.delete(new QueryWrapper<...>().in("record_id", chunk));
        deleted += baseMapper.deleteBatchIds(chunk);
    }
```

```java
// DictionaryServiceImpl.java:105-107 —— 先覆盖文件、再重建 ES 索引
fileService.write(type, entries);
esTermIndexService.rebuild(type, entries);
```

- **影响**：`database-init.sql:71` 有 `CONSTRAINT fk_review_record FOREIGN KEY (record_id) REFERENCES records(id)`。删除中途失败 → `review_tasks` 已清而 `records` 未删，这批病历失去复核任务（在待复核列表里消失）；复核中途失败 → 分数已写回而任务仍 pending；词典导入中途失败 → 文件已覆盖而 ES 索引是旧的，归一结果与词典页展示长期不一致。
- **修复建议**：上述方法加 `@Transactional(rollbackFor = Exception.class)`。ES 属外部副作用，无法随事务回滚，应改成「先写临时文件 → ES 重建成功 → 原子替换文件」，失败时用 `backup/` 回滚。

#### G3 导入缺「接诊时间」列时整批失败，且报错信息误导

- **位置**：`service/impl/RecordServiceImpl.java:487`
- **证据**：

```java
// :487 —— 与上一行的 get() 写法不一致
r.setVisitTime(parseDateTime(row.getCell(idx.getOrDefault("visitTime", -1))));
```

```java
// :491-494 同文件既有写法（正确）
private String get(Row row, Map<String, Integer> idx, String field) {
    Integer c = idx.get(field);
    return c == null ? null : cellText(row.getCell(c));
}
```

- **影响**：POI 的 `Row.getCell(-1)` 抛 `IllegalArgumentException`，被上层 catch 记成「第 N 行：Cell index must be >= 0」。当上传的 Excel 不含「接诊时间」列时，**每一行都失败**，而用户看到的是莫名其妙的 POI 内部报错，无法判断是列缺失还是格式错误。
- **修复建议**：与 `get()` 一致，先判 `Integer c = idx.get("visitTime"); if (c == null) return null;`。同时在导入前置校验里把「必需列缺失」单独报出来。

#### G4 批量解析逐条重算词典版本，缓存判断在读文件之后（缓存等于失效）

- **位置**：`service/impl/DictionaryFileServiceImpl.java:152-171`，调用点 `service/impl/NlpBatchServiceImpl.java:354`
- **证据**：

```java
// DictionaryFileServiceImpl.java:152-171
public String currentVersion() {
    try {
        StringBuilder sb = new StringBuilder();
        long stamp = 0;
        for (String type : com.tcm.ehr.common.config.EntityTypes.dictKeys()) {
            Path f = dir().resolve(fileNameOf(type));
            if (Files.exists(f)) {
                stamp = stamp * 31 + Files.getLastModifiedTime(f).toMillis() + Files.size(f);
                sb.append(type).append('=').append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');   // ← 无条件全量读
            } else {
                sb.append(type).append("=\n");
            }
        }
        if (cachedVersion != null && stamp == cachedStamp) {      // ← 缓存判断在读文件之后
            return cachedVersion;
        }
```

```java
// NlpBatchServiceImpl.java:354 —— 每条病历调用一次
json = StructuredDataMeta.stamp(objectMapper, json, dictionaryFileService.currentVersion());
```

- **影响**：`cachedVersion` 只能省下最后那次 MD5，5 个词典文件的**全文读取 + 拼接在每条病历上都重跑一遍**。按 3.5 万条算，一次全库批量解析要多做约 17.5 万次文件读取。500 条实测 89 秒，其中相当一部分是这个开销。
- **修复建议**：把 `stamp` 的计算（`lastModifiedTime` + `size`）提到读文件之前，命中缓存直接返回；或在批量任务开始时取一次 version，整个任务复用。

#### G5 停机时运行中的批量任务被写成 COMPLETED

- **位置**：`service/impl/NlpBatchServiceImpl.java:95-99`（`@PreDestroy`）、`:258-266`（任务收尾）
- **证据**：

```java
// :94-99
@PreDestroy
void shutdown() {
    running = false;
    if (workers != null) {
        workers.shutdownNow();
    }
}
```

```java
// :252-266
try {
    cancelled = idSource != null
            ? runByIds(id, idSource, t, failures, truncated, processed)
            : runByFilter(id, t, failures, truncated, processed);
} finally {
    t.setStatus(cancelled ? NlpTask.CANCELLED : NlpTask.COMPLETED);   // ← 被中断也走这条
```

```java
// :94-99 启动时只纠正 RUNNING/QUEUED
.in("status", List.of(NlpTask.RUNNING, NlpTask.QUEUED))
.set("status", NlpTask.INTERRUPTED)
```

- **影响**：`shutdownNow()` 中断 worker，`finally` 仍会执行并把 `done < total` 的任务标成 `COMPLETED`。任务列表显示「已完成」，实际只解析了一部分；`@PostConstruct` 的纠正逻辑只覆盖 `RUNNING/QUEUED`，下次启动不会修正这个错误状态。用户会以为全库解析完了。
- **修复建议**：`finally` 里判断 `if (!running) t.setStatus(NlpTask.INTERRUPTED);`，或在 `@PreDestroy` 中先把所有 RUNNING 任务落库为 INTERRUPTED。

#### G6 凭据明文入库配置 + JWT 密钥可猜 + 4 处绝对路径硬编码

- **位置**：`java-backend/src/main/resources/application.yml:9`、`:17`、`:73`、`:54`、`:60`、`:97`、`:101`
- **证据**：

```yaml
datasource:
  password: "123456"                                          # :9
data:
  redis:
    password: "123456"                                        # :17
jwt:
  secret: tcm-ehr-qc-jwt-secret-key-2026-course-design        # :73
dictionary:
  dir: D:/ZISHIKU/AI/tcm-ehr-qc/data/dictionaries             # :54
qc:
  rules-file: D:/ZISHIKU/AI/tcm-ehr-qc/data/qc-rules.json     # :60
llm:
  config-file: D:/ZISHIKU/AI/tcm-ehr-qc/data/llm-config.json  # :97
log:
  operation-file: D:/ZISHIKU/AI/tcm-ehr-qc/logs/operation.log # :101
```

- **影响**：JWT secret 与项目名强相关且出现在仓库里，任何人可据此签发任意角色（含管理员）的 token，`@RequireRole` 全部失效。4 处绝对路径使项目换机器 / 换目录必须改配置。数据库与 Redis 明文弱口令。此外 `useSSL=false&allowPublicKeyRetrieval=true`（:7）明文传输。
- **修复建议**：密钥与口令改 `${DB_PASSWORD}` / `${REDIS_PASSWORD}` / `${JWT_SECRET}` 环境变量注入，secret 换 ≥32 字节随机值；4 个路径改为 `${DATA_DIR:data}/...`、`${LOG_DIR:logs}/...` 相对形式。

#### G7 MyBatis 全量 SQL 打印 + 应用 debug 日志常开

- **位置**：`java-backend/src/main/resources/application.yml:66`、`:105`
- **证据**：

```yaml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl   # :66
logging:
  level:
    com.tcm.ehr: debug                                      # :105
```

- **影响**：每一次查询都把 SQL 与绑定参数打到 stdout，**病历正文、主诉、现病史等敏感内容以及登录查询的用户名会整段进日志文件**（`logs/` 下），同时显著拖慢批量操作。属隐私与性能双输。
- **修复建议**：删掉 `log-impl`（或改 `org.apache.ibatis.logging.nologging.NoLoggingImpl`），`com.tcm.ehr` 回 `info`，需要时用 `logging.level.com.tcm.ehr.mapper=debug` 临时开。

#### G8 前端复制了可配置的分级阈值，与同文件注释自相矛盾

- **位置**：`frontend/src/views/Review.vue:403`（对照 `:389-392` 注释与后端 `common/config/QcRuleSet.java:100-102`）
- **证据**：

```js
// Review.vue:389-392 注释
/**
 * 复核后预估评分（原型「复核后预估评分」区）。
 * 只做「已补齐的核心字段把对应扣分加回」这一条，且明确标注以服务端重算为准 ——
 * 前端不复制规则表，避免与服务端判定口径漂移。
 */

// Review.vue:403 实现
const grade = score >= 90 ? '合格 → 进入数据清洗' : score >= 60 ? '待复核' : '无效'
```

```java
// QcRuleSet.java:100-102 —— 阈值可被管理员在「规则配置」里改
private int qualified = 90;
private int invalid = 60;
```

- **影响**：注释写着「前端不复制规则表」，下一行就把 90/60 写死了。管理员在质控页把合格线改成 85 后，复核页的「复核后预估评分」仍按 90 判级，与真实重算结果不一致。
- **修复建议**：`GET /api/qc/rules` 是「登录即可」，复核页可直接取 `thresholds.qualified/invalid` 参与判级；或干脆不展示预估分级，只展示预估分数。

### 中

#### M1 分页上限未强制，`pageSize` 可任意放大

- **位置**：`common/config/MybatisPlusConfig.java:18`、`domain/dto/SearchDTO.java`、`controller/RecordController.java:111`
- **证据**：

```java
// MybatisPlusConfig.java:18
new PaginationInnerInterceptor(DbType.MYSQL)      // 未 setMaxLimit
```

```java
// RecordServiceImpl.java:419（LogServiceImpl.java:46 / ReviewServiceImpl.java:54 同型）
int size = dto.getPageSize() == null || dto.getPageSize() <= 0 ? 20 : dto.getPageSize();   // 无上限
```

- **影响**：`POST /api/records/search {"pageSize": 1000000}` 一次拉全表，等同于绕过导出权限拿全量数据。`SearchDTO` 也没有 `@Min/@Max` 且 `search()` 未加 `@Valid`。
- **修复建议**：`PaginationInnerInterceptor` 设 `setMaxLimit(500L)`；`SearchDTO` 的 `pageSize` 加 `@Max(200)` 并在 Controller 上加 `@Valid`。

#### M2 全表载入内存（统计 / 导出 / 扣分聚合）

- **位置**：`service/impl/StatsServiceImpl.java:241`、`service/impl/GovernanceServiceImpl.java:318`、`service/impl/GovernanceServiceImpl.java:292`
- **证据**：

```java
// StatsServiceImpl.java:241
return baseMapper.selectList(null).stream()

// GovernanceServiceImpl.java:318
return baseMapper.selectList(null).stream()

// GovernanceServiceImpl.java:292（导出预览前 10 条）
result.put("sample", records.subList(0, Math.min(10, records.size())));
```

- **影响**：3.5 万条病历（含 `structured_data` / `qc_results` 两个大 JSON 列）全量进 JVM 堆，内存峰值高、GC 压力大；导出预览只需要 10 条却先载入全表。
- **修复建议**：导出改分页游标 / 流式写出；预览用 `LIMIT 10` 单独查询；`sample` 与导出走同一个脱敏函数。

#### M3 Redis 防重锁：不可用时静默放行、释放时不校验持有者

- **位置**：`service/impl/QcServiceImpl.java:365-381`
- **证据**：

```java
// :368-372
} catch (Exception e) {
    log.warn("[质控重算] Redis 不可用，跳过防重锁: {}", e.getMessage());
    return true;                                   // ← 放行
}

// :377
redis.delete(BATCH_LOCK_KEY);                      // ← 不校验 value 是否是本次持有
```

- **影响**：Redis 故障时批量重算可并发重复提交，产生重复扣分与重复 `review_tasks`；锁 900 秒过期后被其他实例获取时，本实例收尾会误删他人的锁，防重彻底失效。
- **修复建议**：Redis 不可用应拒绝提交（503）或降级为进程内 `ReentrantLock`；锁 value 用本次请求的 UUID，释放前比对（或 Lua 原子删除）。

#### M4 词典写入与 ES 重建无补偿，且 `rebuild` 有「索引不存在」窗口

- **位置**：`service/impl/EsTermIndexServiceImpl.java:55-64`、`common/config/DataInitializationListener.java:83`
- **证据**：

```java
// EsTermIndexServiceImpl.java:55-64
public void rebuild(String type, List<TermEntry> entries) throws IOException {
    String index = indexName(type);
    if (exists(type)) {
        client.indices().delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT);   // ← 先删
    }
    CreateIndexRequest create = new CreateIndexRequest(index);
    ...
    client.indices().create(create, RequestOptions.DEFAULT);                              // ← 再建
```

```java
// DataInitializationListener.java:75-88 —— 每次启动都对 5 类词典各 rebuild 一次
for (String type : TermTypes.ALL) {
    ...
    esTermIndexService.rebuild(type, entries);
```

- **影响**：delete 与 create 之间有一个窗口，此刻归一检索会拿到 `index_not_found` → 归一接口返回 503（`code=1010`）。**每次服务重启都会经历 5 次这个窗口**，用户表现为「重启后第一次解析偶发失败」。另外 rebuild 抛异常时文件已被覆盖，接口 500，文件与索引不一致且无补偿。
- **修复建议**：改「建新索引（带版本后缀）→ 灌数据 → 切别名」，不做 delete；或至少 delete 后立刻 create（把 mapping 构建提到 delete 之前）。启动时的重建可改为「索引已存在且版本一致则跳过」。

#### M5 导入任务状态 Map 永不清理

- **位置**：`service/impl/RecordServiceImpl.java:115`、`:130`
- **证据**：

```java
private final Map<String, ImportStatusVO> taskStore = new ConcurrentHashMap<>();
```

（全文件无任何 `remove` / 过期清理）

- **影响**：每次导入新增一条，长期运行内存持续增长（每条含失败明细）。属慢性泄漏。
- **修复建议**：改有界 + TTL 的缓存（Caffeine），或在任务完成后延时清理。

#### M6 `writeJson` 失败返回字符串 `"null"`，会让批量任务退化为全库扫描

- **位置**：`service/impl/NlpBatchServiceImpl.java:397-403`、`:166`、`:405-414`、`:272`
- **证据**：

```java
// :397-403
private String writeJson(Object o) {
    try {
        return objectMapper.writeValueAsString(o);
    } catch (Exception e) {
        return "null";                                  // ← 静默降级
    }
}
```

```java
// :166 提交时
t.setFiltersJson(writeJson(filters));
// :405-414 执行时
if (json == null || json.isBlank() || "null".equals(json)) return null;
// :272 拿到 null 后的分支
QueryWrapper<Record> wrapper = RecordFilter.build(RecordFilter.ROLE_ADMIN, readFilters(t.getFiltersJson()));
```

- **影响**：序列化失败时筛选条件丢失，任务从「指定范围」退化成对全库分页扫描。用户以为只跑了筛选出来的那几百条，实际在跑全库。
- **修复建议**：`writeJson` 失败应抛异常阻止任务提交，不允许降级为 `"null"`。

#### M7 同一「资源不存在」语义两套错误码

- **位置**：`service/impl/ReviewServiceImpl.java:104-106`、`controller/ReviewController.java:50`（对照 `service/impl/ReviewServiceImpl.java:95`）
- **证据**：

```java
// ReviewServiceImpl.java:95
throw new ResourceNotFoundException(1006, "病历不存在");
// ReviewServiceImpl.java:104-106
    ...
    return null;                                       // ← 无待复核任务时返回 null
// ReviewController.java:50
return Result.error(2003, "复核记录不存在或状态已完结");
```

- **影响**：错误码表出现 1006 / 2003 两套「不存在」，前端需要分别处理；`return null` 的路径前端拿到 `data: null` 而非错误码。
- **修复建议**：统一走 `ResourceNotFoundException`，把 2003 登记进错误码表并只保留一条路径。

#### M8 三处 `ElMessageBox.confirm` 未捕获，取消即产生未处理的 Promise rejection

- **位置**：`frontend/src/views/AuditLog.vue:163`、`frontend/src/views/Dictionary.vue:402`、`frontend/src/views/NlpExtract.vue:737`
- **证据**：

```js
// AuditLog.vue:163-170（try 在 confirm 之后，catch 抓不到）
await ElMessageBox.confirm(
  ...
)
try {
  const res = await purgeLogs(purgeDate.value)
```

```js
// NlpExtract.vue:737
await ElMessageBox.confirm('确定取消该批量解析任务吗？已处理的不回滚。', '取消任务', { type: 'warning' })
try {
```

（对照 `Qc.vue:509` / `Records.vue:357` / `Governance.vue:255` 均为 `try { await confirm } catch { return }`）

- **影响**：点「取消」时控制台出现 `Uncaught (in promise)`，与其余 5 处写法不一致。功能上取消仍生效（异常中断了后续代码），属噪声 + 一致性缺陷。
- **修复建议**：统一抽 `confirmBox()` 封装，内部 `try/catch` 并返回布尔。

#### M9 接口失败未捕获，列表停在旧值

- **位置**：`frontend/src/views/Dictionary.vue:209-217`（`loadTerms`）、`:396-399`（`loadBackups`）、`frontend/src/components/LlmConfigDialog.vue:147-165`（`loadConfig`）、`:195-205`（`handleSave`）
- **证据**：

```js
// Dictionary.vue:209-217
try {
  ...
} finally {
  loading.value = false
}            // 只有 try/finally，无 catch
```

- **影响**：接口失败时只弹一个 toast，列表/表单停留在上一次的值，用户可能把旧数据当成最新结果。
- **修复建议**：补 `catch` 并置失败态（复用 `EmptyState` 的 `failed`）。

#### M10 导出 / 预览丢「分级」筛选，范围静默不生效

- **位置**：`frontend/src/views/Governance.vue:281-288`（对照 `:226-233`、`:265`）
- **证据**：

```js
// :226-233 范围文案 —— 含 grade
if (filters.grade) parts.push(filters.grade)

// :265 清洗请求 —— 含 grade
const res = await cleanApi({ filters: { ...filters } })

// :281-288 导出/预览请求 —— 丢掉 grade
const buildPayload = () => ({
  format: format.value,
  filters: {
    department: filters.department || '',
    dateRange: filters.dateRange || [],
    pattern: filters.pattern || ''
  }
})
```

- **影响**：`buildPayload()` 被 `previewDataset`（:347）与 `exportDataset`（:360）共用。用户把范围设成「全部 · 待复核」时，页面文案与清洗都按「待复核」执行，**导出却按全部分级导**。用户拿到的数据集比预期大，且没有任何提示。
- **修复建议**：`buildPayload()` 补 `grade: filters.grade || ''`，与清洗保持同一份参数组装函数。

#### M11 加载中 / 空数据 / 接口异常三态未统一

- **位置**：`frontend/src/components/EmptyState.vue` 仅被 `views/Dashboard.vue:61/73/86` 使用
- **证据**（其余各页各自内联）：

```
Records.vue:66-68      → 仅 el-empty 文案
NlpExtract.vue:54-56   → 仅 el-empty 文案
Qc.vue:231-235         → 仅 el-empty 文案
AuditLog.vue:60-65     → 自造 available 变量做失败态
Governance.vue:9-12    → 自造 statsFailed 做失败态
```

- **影响**：接口异常在多数页面表现为「空数据」，用户无法区分「没查到」与「服务坏了」，也没有重试入口。这违反「兜底状态全局统一」。
- **修复建议**：各页统一改用 `EmptyState`（它已支持 `loading` / `failed` / `retry`）。

#### M12 两个范围查询组件并存，参数组装方式不同

- **位置**：`frontend/src/components/RangeFilter.vue`（被 Records / NlpExtract / Qc / Governance 使用）与 `frontend/src/components/StatsFilter.vue`（仅 Dashboard 使用）
- **证据**：

```html
<!-- RangeFilter.vue:18-27 —— 时间是一个 daterange 数组 -->
<el-date-picker v-model="inner.dateRange" type="daterange" value-format="YYYY-MM-DD" ... />

<!-- StatsFilter.vue:14-19 —— 时间是两个独立 date -->
<el-date-picker id="sf-start" v-model="model.start" type="date" value-format="YYYY-MM-DD" ... />
<el-date-picker id="sf-end"   v-model="model.end"   type="date" value-format="YYYY-MM-DD" ... />
```

```js
// StatsFilter.vue:29 —— 假数据兜底
departments: { type: Array, default: () => ['内科', '外科', '儿科', '针灸科'] }
```

- **影响**：字段名不同（`dateRange: [a,b]` vs `start`/`end`）、交互不同（无查询按钮 vs 有查询/重置）、字段集不同（含分级/证候 vs 不含）。「范围查询语法与参数组装方式保持一致」不成立。另外 `StatsFilter` 的默认科室是**演示数据里不存在**的四个科室，一旦 `departments` 未传入就会显示错误选项。
- **修复建议**：合并为一个范围条组件，对外统一 `{department, dateRange, pattern, grade}`；`StatsFilter` 的默认值改为 `() => []`。

#### M13 21 字段定义与列表列渲染重复三份

- **位置**：`views/Records.vue:249-271`、`views/Review.vue:211-233`、`components/RecordDetailDialog.vue:47-69`（FIELDS 内容完全相同）；`views/Review.vue:235-239` 与 `components/RecordDetailDialog.vue:71-77`（`fieldOf` 重复）；`views/Records.vue:40-59`、`views/NlpExtract.vue:29-48`、`views/Qc.vue:206-225`（列渲染重复，年龄/性别表达式逐字相同）
- **影响**：改一个字段名要动 3~6 处，极易漏改导致某页显示旧字段。
- **修复建议**：抽 `frontend/src/constants/recordFields.js` 与公共列表列组件。

#### M14 字号 16 种取值、同类面板标题 5 种字号；颜色/圆角硬编码

- **位置**：全前端（`views/*.vue` + `components/*.vue` + `styles/theme.css`）
- **证据**（按出现次数排序的 `font-size` 取值）：

```
57× 12.5px   37× 12px    27× 13px    16× 11.5px   7× 11px    6× 14px
 4× 13.5px    4× 22px     2× 10.5px   1× 24px   1× 21px  1× 20px  1× 18px  1× 17px  1× 16px  1× 15px
```

同属「面板 / 区块标题」这一视觉层级的实际取值：

```
14px  → Governance.vue:540 .result-hd、Records.vue:687 .result-hd、PanelCard.vue:35
13px  → Governance.vue:496 .step-title、:608 .preview-hd、NlpExtract.vue:812 .pane-hd、Dictionary.vue:556 .convert-hd
12.5px→ Dictionary.vue:500 .ded-hd、AuditLog.vue:203 .purge-tip
```

其余硬编码：`background: #fff`（Dashboard.vue:291、Governance.vue:399/425/551、NlpExtract.vue:956 等）；`border-radius` 同时存在 2px / 3px / 4px / 6px（`StatsFilter.vue:39` 6px 与 `Dashboard.vue:322` 2px 是同类卡片）。`padding|margin|gap` 的硬编码数值共 **162 处**。

- **影响**：`styles/theme.css` 里只有颜色与 `--el-border-radius-base: 2px`，没有字号 / 间距 / 背景变量。视觉层级靠逐个组件手写，改一次全局风格要改上百处；0.5px 级的字号差（12px / 12.5px / 13px）在页面上表现为同一层级的文字大小不一。
- **修复建议**：在 `theme.css` 增加 `--fs-title / --fs-body / --fs-note / --space-* / --card-bg / --radius-*`，逐页替换；同类面板标题统一到 `--fs-title`。

#### M15 分页参数名与默认值不统一，`pageSize` 魔法值散落

- **位置**：`controller/LogController.java:37`（`size`，默认 10）vs `controller/ReviewController.java:34`（`pageSize`，默认 20）
- **证据**：

```java
// LogController.java:36-37
@RequestParam(defaultValue = "1") int page,
@RequestParam(defaultValue = "10") int size) {

// ReviewController.java:33-34
@RequestParam(defaultValue = "1") Integer page,
@RequestParam(defaultValue = "20") Integer pageSize,
```

```js
// 前端跟随：AuditLog.vue:113  size: 10 ； 其余 4 页 pageSize: 10
// pageSize: 10 散落于 Records.vue:298 / NlpExtract.vue:388 / Review.vue:273 / Qc.vue:531
// :page-sizes="[10, 20, 50]" 在 5 个文件里各写一遍
```

- **影响**：同一套分页在日志页叫 `size`、其它页叫 `pageSize`；默认页大小 10 硬编码 4 处、可选页大小 5 处。改默认值需改 5 个文件。
- **修复建议**：统一 `page/pageSize`；把 `DEFAULT_PAGE_SIZE` / `PAGE_SIZES` 抽到常量文件。

#### M16 文档口径与代码不一致（6 处）

| 位置                            | 文档写的                                         | 实际                                                       | 性质                   |
| ------------------------------- | ------------------------------------------------ | ---------------------------------------------------------- | ---------------------- |
| `openapi.yaml:947`              | 核心要素 **5 项**                                | 代码评 **6 项**（含疾病）                                  | 契约不一致（见 G1）    |
| `功能设计文档.md:708/1215/1237` | 核心要素 5 项                                    | 同上                                                       | 文档过期               |
| `项目设计文档.md:94`            | 核心要素 5 项                                    | 同上                                                       | 文档过期               |
| `待办计划.md:24`                | 核心要素 5 项                                    | 同上                                                       | 文档过期               |
| `需求分析报告.md:157`           | 6 要素、每项扣 **15** 分、含治法/方剂            | 现行 6 要素、两档 -6/-12，治法方剂已退出评分               | 老口径未回写           |
| `项目设计文档.md:873`           | QcController 8 个接口，枚举含「质控图谱」        | 接口数对，但「质控图谱」已删，漏列 3 个规则接口 + 扣分聚合 | 枚举过期               |
| `README.md:35`                  | MySQL **4** 张核心表                             | `database-init.sql` 有 **5** 张（多 `nlp_task`）           | 数量过期               |
| `README.md:37-49`               | 结构树只有 docs / java-backend / frontend / data | 还有 `python-nlp/`、`database-init.sql`、`logs/`、`tools/` | 结构树不完整           |
| `后续开发方案.md:470`           | 接口数已改为 **41 个**                           | 现为 46 路径 / 50 操作                                     | 历史记录，建议标注批次 |

### 低

- **L1** `backupExists` 无路径前缀校验，可用于探测任意文件是否存在 —— `service/impl/DictionaryFileServiceImpl.java:147-149` 的 `Files.exists(backupDir().resolve(backupFilename))` 未做前缀校验（真正的写入已被 `:99` 的 `startsWith` 拦住，故仅信息泄露）。
- **L2** `warnings` 用 `ArrayList`，并发 `add` 不安全；告警文案「内置 5 要素」已过时 —— `common/config/QcRuleStore.java:35`、`:129`。改 `CopyOnWriteArrayList`，文案改 6 要素。
- **L3** 导出预览 `sample` 未脱敏 —— `service/impl/GovernanceServiceImpl.java:292`，与导出页「自动脱敏」承诺不一致。
- **L4** 死代码 7 处 —— `views/Qc.vue:321` `standardOpen`；`views/Governance.vue:340` `closeDetail`；`views/Placeholder.vue`（未挂路由）；`api/records.js:11` `importStatus`；`api/stats.js:7` `getStats`；`components/LlmConfigDialog.vue:119` `emit('saved')`（父组件未监听）；`views/NlpExtract.vue:473/505/522` 只写不读的 `text`。
- **L5** 可编辑列表用 index 作 `key` —— `views/Qc.vue:100`（`customFormats`，含删除）、`views/Qc.vue:108`（一致性规则，含 `splice`）、`components/StructuredDataCard.vue:19`、`components/AiAssistant.vue:33`。删除中间项时可能复用错误的 DOM 状态。
- **L6** `onMounted` 中引用后声明的 `handleResize` —— `views/Dashboard.vue:268`（声明在 `:271`）。当前靠回调延后执行才成立，属隐患。
- **L7** 「查看运行中任务」不启动轮询 —— `views/NlpExtract.vue:750-757` 的 `viewTask` 只赋值 `activeTask`，未 `startPoll()`，进度不会自刷新。
- **L8** 两个 `RecordFilter.build` 重载排序键不一致 —— `common/utils/RecordFilter.java:68`（SearchDTO 重载有 `orderByDesc("visit_time")`）vs `:80-100`（FiltersDTO 重载无 orderBy）。同一工具的两种入参给出不同排序。
- **L9** Controller 路由风格不统一 —— `Auth`/`Dictionary`/`Llm`/`Qc`/`Record`/`Review`/`Stats` 用类级 `@RequestMapping`；`Governance`/`Log`/`Nlp`/`Ai` 在方法上写全路径。
- **L10** 日志导出文件名三处不一致 —— `controller/LogController.java:58` 设 `operation_logs.csv`；`views/AuditLog.vue:150` 覆盖为 `audit_logs_${Date.now()}.csv`；归档文件名是 `audit-archive-20260925_155231.csv`（`LogServiceImpl.java:110`）。前端丢弃了后端的 `Content-Disposition`，且毫秒时间戳不可读。
- **L11** NLP 长文本静默截断，无标记 —— `python-nlp/main.py:107-108` 的 `max_length=510`，超长病历尾部实体静默丢失，响应仍报 `modelAvailable=true`。
- **L12** NLP 侧若干规范问题 —— `python-nlp/main.py:93` `@app.on_event("startup")` 已废弃；`:85/:90` 用 `print` 且异常无 traceback；`:16` `MODEL_DIR="model"` 相对路径依赖启动目录；`requirements.txt:2-5` 全部未锁版本；`:43-44` `text: str` 无 `max_length`；`:29-30` 病因表同时含「湿热」与「湿热蕴结」会重复命中。

---

## 二、契约一致性核对表

### 2.1 openapi ↔ Controller ↔ 前端 逐接口对照

结论：**50 个操作在 Controller 侧全部存在，路径与方法逐条一致；50/50 均带 `【权限：…】` 标注，且与 `@RequireRole` 完全吻合**；前端 47 个 api 函数覆盖 50 个操作中的 47 个，无「调用了未定义接口」的情况。

| #   | 接口                                      | openapi 权限    | Controller                                | 前端 api → 使用页面                         | 三方结论 |
| --- | ----------------------------------------- | --------------- | ----------------------------------------- | ------------------------------------------- | -------- |
| 1   | `POST /api/auth/register`                 | 公开            | AuthController.java:29 · 登录即可         | auth.js → Register                          | 一致     |
| 2   | `POST /api/auth/login`                    | 公开            | AuthController.java:41 · 登录即可         | auth.js → Login                             | 一致     |
| 3   | `GET /api/logs`                           | 仅管理员        | LogController.java:33 · 管理员            | log.js → AuditLog                           | 一致     |
| 4   | `GET /api/logs/actions`                   | 仅管理员        | LogController.java:46 · 管理员            | log.js → AuditLog                           | 一致     |
| 5   | `GET /api/logs/export`                    | 仅管理员        | LogController.java:53 · 管理员            | log.js → AuditLog                           | 一致     |
| 6   | `POST /api/logs/purge`                    | 仅管理员        | LogController.java:65 · 管理员            | log.js → AuditLog                           | 一致     |
| 7   | `POST /api/records/import`                | 仅管理员        | RecordController.java:45 · 管理员         | records.js → Records                        | 一致     |
| 8   | `GET /api/records/import/{taskId}/status` | 仅管理员        | RecordController.java:57 · 管理员         | records.js → 导出但无页面调用               | 一致     |
| 9   | `POST /api/records`                       | 仅管理员        | RecordController.java:68 · 管理员         | records.js → Records                        | 一致     |
| 10  | `DELETE /api/records`                     | 仅管理员        | RecordController.java:94 · 管理员         | records.js → Records                        | 一致     |
| 11  | `POST /api/records/delete-by-filter`      | 仅管理员        | RecordController.java:103 · 管理员        | records.js → Records                        | 一致     |
| 12  | `GET /api/records/raw/{recordId}`         | 登录即可        | RecordController.java:74 · 登录即可       | records.js → NlpExtract/Records/Review      | 一致     |
| 13  | `PUT /api/records/{recordId}`             | 仅管理员        | RecordController.java:85 · 管理员         | records.js → NlpExtract                     | 一致     |
| 14  | `POST /api/nlp/extract`                   | 登录即可        | NlpController.java:55 · 登录即可          | nlp.js → NlpExtract                         | 一致     |
| 15  | `POST /api/nlp/extract/batch`             | 仅管理员        | NlpController.java:90 · 管理员            | nlp.js → NlpExtract                         | 一致     |
| 16  | `GET /api/nlp/extract/batch`              | 仅管理员        | NlpController.java:117 · 管理员           | nlp.js → NlpExtract                         | 一致     |
| 17  | `GET /api/nlp/extract/batch/{id}`         | 仅管理员        | NlpController.java:99 · 管理员            | nlp.js → NlpExtract                         | 一致     |
| 18  | `POST /api/nlp/extract/batch/{id}/cancel` | 仅管理员        | NlpController.java:110 · 管理员           | nlp.js → NlpExtract                         | 一致     |
| 19  | `POST /api/governance/normalize`          | 登录即可        | GovernanceController.java:40 · 登录即可   | governance.js → NlpExtract                  | 一致     |
| 20  | `GET /api/governance/stats`               | 仅管理员        | GovernanceController.java:99 · 管理员     | governance.js → Dashboard/Governance        | 一致     |
| 21  | `POST /api/qc/check`                      | 仅管理员        | QcController.java:40 · 管理员             | **前端未调用**                              | 一致     |
| 22  | `POST /api/qc/check/logic`                | 仅管理员        | QcController.java:47 · 管理员             | **前端未调用**                              | 一致     |
| 23  | `GET /api/review/tasks`                   | 管理员 / 审核员 | ReviewController.java:32 · 管理员、审核员 | review.js → Review                          | 一致     |
| 24  | `POST /api/records/{recordId}/review`     | 管理员 / 审核员 | ReviewController.java:41 · 管理员、审核员 | review.js → Review                          | 一致     |
| 25  | `POST /api/export/dataset`                | 仅管理员        | GovernanceController.java:72 · 管理员     | governance.js → Governance                  | 一致     |
| 26  | `POST /api/export/dataset/preview`        | 仅管理员        | GovernanceController.java:92 · 管理员     | governance.js → Governance                  | 一致     |
| 27  | `POST /api/governance/clean`              | 仅管理员        | GovernanceController.java:59 · 管理员     | governance.js → Governance                  | 一致     |
| 28  | `POST /api/qc/score`                      | 仅管理员        | QcController.java:54 · 管理员             | qc.js → Qc/Review                           | 一致     |
| 29  | `POST /api/qc/score/batch`                | 仅管理员        | QcController.java:61 · 管理员             | qc.js → Qc                                  | 一致     |
| 30  | `GET /api/qc/rules`                       | 登录即可        | QcController.java:67 · 登录即可           | qc.js → Qc                                  | 一致     |
| 31  | `PUT /api/qc/rules`                       | 仅管理员        | QcController.java:74 · 管理员             | qc.js → Qc                                  | 一致     |
| 32  | `POST /api/qc/rules/reset`                | 仅管理员        | QcController.java:81 · 管理员             | qc.js → Qc                                  | 一致     |
| 33  | `GET /api/qc/deduction-stats`             | 仅管理员        | QcController.java:88 · 管理员             | qc.js → Qc                                  | 一致     |
| 34  | `POST /api/dictionary/import`             | 仅管理员        | DictionaryController.java:39 · 管理员     | dictionary.js → Dictionary                  | 一致     |
| 35  | `POST /api/dictionary/convert`            | 仅管理员        | DictionaryController.java:59 · 管理员     | dictionary.js → Dictionary                  | 一致     |
| 36  | `GET /api/dictionary/terms`               | 登录即可        | DictionaryController.java:72 · 登录即可   | dictionary.js → Dictionary/Qc/TermInput     | 一致     |
| 37  | `POST /api/dictionary/rollback`           | 仅管理员        | DictionaryController.java:84 · 管理员     | dictionary.js → Dictionary                  | 一致     |
| 38  | `GET /api/dictionary/backups`             | 登录即可        | DictionaryController.java:100 · 登录即可  | dictionary.js → Dictionary                  | 一致     |
| 39  | `POST /api/stats`                         | 登录即可        | StatsController.java:65 · 登录即可        | stats.js → 导出但无页面调用                 | 一致     |
| 40  | `GET /api/stats/overview`                 | 登录即可        | StatsController.java:33 · 登录即可        | stats.js → Dashboard                        | 一致     |
| 41  | `POST /api/records/search`                | 登录即可        | RecordController.java:111 · 登录即可      | records.js → NlpExtract/Qc/Records          | 一致     |
| 42  | `GET /api/stats/all`                      | 登录即可        | StatsController.java:45 · 登录即可        | **前端未调用**                              | 一致     |
| 43  | `GET /api/stats/extra`                    | 登录即可        | StatsController.java:55 · 登录即可        | stats.js → Dashboard                        | 一致     |
| 44  | `GET /api/stats/departments`              | 登录即可        | StatsController.java:39 · 登录即可        | stats.js → Dashboard/Governance/RangeFilter | 一致     |
| 45  | `POST /api/ai/interpret`                  | 登录即可        | AiController.java:29 · 登录即可           | ai.js → AiInterpretCard                     | 一致     |
| 46  | `POST /api/ai/chat`                       | 登录即可        | AiController.java:42 · 登录即可           | ai.js → AiAssistant                         | 一致     |
| 47  | `POST /api/ai/review`                     | 管理员 / 审核员 | AiController.java:52 · 管理员、审核员     | ai.js → Review                              | 一致     |
| 48  | `GET /api/llm/config`                     | 仅管理员        | LlmController.java:42 · 管理员            | llm.js → LlmConfigDialog                    | 一致     |
| 49  | `PUT /api/llm/config`                     | 仅管理员        | LlmController.java:49 · 管理员            | llm.js → LlmConfigDialog                    | 一致     |
| 50  | `POST /api/llm/test`                      | 仅管理员        | LlmController.java:56 · 管理员            | llm.js → LlmConfigDialog                    | 一致     |

### 2.2 权限三方对照结论

| 页面                  | `meta.roles`    | 该页调用的接口所需角色                                                                                                      | 结论                               |
| --------------------- | --------------- | --------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| 首页看板 Dashboard    | 管理员 / 审核员 | `/stats/*`（登录即可）                                                                                                      | 一致（接口比页面更宽，无越权风险） |
| 人工复核 Review       | 管理员 / 审核员 | `/review/tasks`、`/records/{id}/review`、`/ai/review`（管理员/审核员）；`/records/raw/{id}`、`/qc/score`（登录即可/管理员） | 一致                               |
| 病历数据 Records      | 管理员          | `/records/*`（管理员）                                                                                                      | 一致                               |
| 结构化解析 NlpExtract | 管理员          | `/nlp/extract/batch*`（管理员）                                                                                             | 一致                               |
| 质控校验 Qc           | 管理员          | `/qc/*`（管理员，`/qc/rules` 读取为登录即可）                                                                               | 一致                               |
| 清洗与导出 Governance | 管理员          | `/governance/*`、`/export/*`（管理员）                                                                                      | 一致                               |
| 术语词典 Dictionary   | 管理员          | `/dictionary/*`（写操作为管理员）                                                                                           | 一致                               |
| 日志审计 AuditLog     | 管理员          | `/logs/*`（管理员）                                                                                                         | 一致                               |

后端侧：`WebMvcConfig` 的拦截器顺序正确（`JwtInterceptor` 先于 `RoleInterceptor`，未登录回 401、角色不足回 403），放行 `login`/`register`/`OPTIONS`。`@RequireRole` 共 35 处，覆盖全部敏感接口。

**唯一需要复核的一处**：`/api/stats`、`/api/stats/overview` 的契约档位是「登录即可」，但实现返回**全库**聚合（见 Z2）。契约本身没写错，是实现的域过滤缺失。

---

## 三、文档与代码一致性

| 项                | 文档                                                                                            | 代码 / 实际                                              | 结论         |
| ----------------- | ----------------------------------------------------------------------------------------------- | -------------------------------------------------------- | ------------ |
| 接口总数          | `README.md:53`：50 个（openapi 46 路径 / 50 操作）                                              | 46 路径 / 50 操作；Controller 50 个映射                  | 一致         |
| 接口总数          | `功能设计文档.md:1104`：50 个，与 openapi 一一对应                                              | 一致                                                     | 一致         |
| 接口总数          | `项目设计文档.md:148/228/272`：50 个接口、11 个 Controller                                      | 11 个 Controller、50 个映射                              | 一致         |
| Controller 明细   | `项目设计文档.md:851-871`：Record 8 / Log 4 / Review 2 / Ai 3 / Llm 3                           | 逐条核对一致                                             | 一致         |
| QcController 明细 | `项目设计文档.md:873`：8 个接口，含「质控图谱」                                                 | 数量对，但「质控图谱」已删，漏列 3 个规则接口 + 扣分聚合 | 不一致       |
| 核心要素          | `openapi.yaml:947` 等：5 项                                                                     | `QcRuleSet.java:110`：6 项                               | 不一致（G1） |
| 扣分规则          | `需求分析报告.md:157`：每项 15 分、含治法/方剂                                                  | 6 要素、-6/-12 两档、治法方剂不参与                      | 不一致       |
| 数据库表数        | `README.md:35`：4 张核心表                                                                      | `database-init.sql`：5 张（多 `nlp_task`）               | 不一致       |
| 项目结构          | `README.md:37-49`                                                                               | 缺 `python-nlp/`、`database-init.sql`、`logs/`、`tools/` | 不一致       |
| 开关默认值        | `application.yml:78/88/89`：`nlp.enabled=true`、`llm.enabled=false`、`llm.convert-enabled=true` | 与注释、`LlmConfigStore` 运行时覆盖逻辑一致              | 一致         |
| 权限标注          | 「openapi 每个接口首部必须带 `【权限：…】`」                                                    | 50/50 齐全                                               | 一致         |
| Jackson 版本      | 「Jackson 3，包名 `tools.jackson.*`」                                                           | `com.fasterxml.jackson` 0 命中；无 `new ObjectMapper()`  | 一致         |
| 术语规范          | 「禁止出现『治理』」                                                                            | 源码/文案/注释中 `治理` 0 命中                           | 一致         |

---

## 四、回归风险与验证清单

### 4.1 修 Z1（测试编译）后必须重跑

```bash
# 后端：先恢复可运行的回归基线
cd "D:/ZISHIKU/AI/tcm-ehr-qc"
JAVA_HOME=F:/jdk17 "D:/devSoft/apache-maven-3.9.16/bin/mvn" -o -f java-backend/pom.xml test
# 期望：Tests run: 74, Failures: 0, Errors: 0

# 前端
node node_modules/vite/bin/vite.js build      # 在 frontend/ 下
```

### 4.2 按问题逐条对应的验证点

| 改动            | 必须重测                                                                                                                   |
| --------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Z2 统计域过滤   | 用审核员 token 调 `/api/stats/overview`、`/api/stats`，确认只统计 `grade='待复核'`；管理员侧数字不变（500 / 406 / 94 / 0） |
| G1 核心要素口径 | 取一份**缺中医诊断**的病历：`POST /api/qc/score` 与 `POST /api/ai/interpret` 的结论必须一致                                |
| G2 加事务       | 制造中途失败（如删除时注入异常），确认 `review_tasks` 与 `records` 同时回滚                                                |
| G3 导入列缺失   | 上传不含「接诊时间」列的 Excel，确认报「缺少必需列」而不是 `Cell index must be >= 0`                                       |
| G4 版本缓存     | 500 条批量解析耗时对比（当前基线 89 秒）                                                                                   |
| G5 停机状态     | 批量解析跑到一半停服务，重启后任务状态应为 `INTERRUPTED` 而非 `COMPLETED`                                                  |
| G6 密钥外置     | 换 `JWT_SECRET` 后旧 token 全部失效（401），新登录正常                                                                     |
| G7 日志级别     | 确认 stdout 不再出现 SQL 与绑定参数                                                                                        |
| G8 分级阈值     | 把 `thresholds.qualified` 改成 85，复核页预估分级随之变化                                                                  |
| M1 分页上限     | `POST /api/records/search {"pageSize": 100000}` 应被拒绝或截断到上限                                                       |
| M4 索引重建     | 重启后立刻调归一接口，不应出现 503（`code=1010`）                                                                          |
| M10 导出范围    | 范围设「待复核」后导出，行数应等于待复核数（94）而非全库（500）                                                            |
| M11 三态统一    | 停后端后逐页打开，应显示「失败 + 重试」而不是空数据                                                                        |

### 4.3 全站冒烟（改动后固定跑）

```
登录 → 首页看板（4 卡片 + 趋势 + 科室合格率）→ 病历数据（筛选/详情/列显示）
→ 结构化解析（单条解析 + 批量任务轮询）→ 质控校验（规则配置弹窗 + 扣分明细）
→ 人工复核（审核员身份走一遍）→ 清洗与导出（预览 + 导出 + 清洗）
→ 术语词典（导入/回滚/格式说明）→ 日志审计（筛选/导出/清理弹窗）
```

---

## 五、未检查 / 存疑项

- **运行期启动与接口实测** —— 起第二个实例会写 Redis 键（`tcm:startup:ping`）并**重建 5 个 ES 索引**（`DataInitializationListener:83` → `EsTermIndexServiceImpl.rebuild` 是 delete + create），属写操作，超出只读授权。提示词允许「启后端到临时端口」，但该启动副作用会改动用户的 ES 环境，故未执行。
- **运行中实例是否等价于 HEAD** —— 未重启用户服务。`:8080`（PID 7820）启动于 **21:17:57**，而最近三次提交是 21:31（批T）、21:37、22:04（术语统一），**该实例早于这三次提交**，其行为不代表 HEAD。`GET /api/stats/overview` 回 401、`POST /api/auth/login` 回 200 属预期。
- **`mvn clean`** —— 离线仓库里 `maven-clean-plugin:3.5.0` 缓存自一个已不可达的仓库，`-o` 下无法解析。用 `mvn -o test`（不带 clean）绕过，属环境问题不是项目问题。
- **3.5 万条真实数据集的性能与上限** —— 当前库内是 500 条演示数据。`qc.batch.max-records: 30000` 与 `docs/未命名.md:35` 的 35,355 条冲突：**全库批量重算会被 400 拒绝**（`QcServiceImpl.java:138-139`）。`docs/待办计划.md:14` 声称批K 已「35355 上限核对」，但核对的是批量解析条数，不是这个上限 —— **存疑，需用户确认**。
- **多实例部署下的行为** —— 单机环境。`NlpBatchServiceImpl` 的队列与取消位、`LlmConfigStore`、`QcRuleStore` 均为**进程内状态**，多实例会分裂（见 M3 与 M4）。
- **前端运行时无白屏** —— 未起前端做真机点击。静态核对：25 个 SFC 的 Vue API 均正确 import、模板引用的组件均已注册，未发现会整站白屏的问题。
- **NLP 模型推理实测** —— 未起 / 未改 NLP 服务，只读阅读 `python-nlp/main.py`（见 L11/L12）。
- **数据库结构变更** —— 只读。现网库结构变更由用户手动执行（`docs/待办计划.md:49`）。

### 附：工作区未提交改动（非本次审查产生）

```
 D docs/未命名-审核与计划.md        # HEAD 中由 96cda0b 提交，工作区已删除
 M docs/未命名.md
?? docs/AI用户视角审查报告.md      # 上一轮「用户视角审查」产出
```

未提交的删除有丢失风险，请确认是否为有意操作。

---

审查过程中未修改任何项目代码、配置或数据；所有结论均来自本次实际执行的编译/构建输出、源码原文与接口响应。
