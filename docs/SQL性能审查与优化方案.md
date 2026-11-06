# SQL 性能审查与优化方案

> 范围：`records` 及相关表的**范围查询**（`RangeFilter` 的导入时间 / 就诊时间 / 科室）与其它影响前端加载的 SQL。
> 方法：4 个并行审查 agent（Mapper 反模式 / 范围查询链路 / 看板与报告 / 索引与谓词）+ 在**真实库**上跑 `EXPLAIN` 校正。
> 数据基线（2026-10-16）：`records` 34182 行，其中 `structured_data` 非空 33587 行、JSON 合计 **96.5 MB**；`review_tasks` 27465；`dictionary_terms` 8470。

---

## 0. TL;DR（先看这个）

| 优先级 | 问题 | 位置 | 影响 |
|---|---|---|---|
| **P0** | 报告页把命中的**全部病历**拉进内存并**每条解析 2 次 JSON**，还带一个无用的 `ORDER BY` | `StandardizationReportServiceImpl.java:567-571,579-585` | 报告首屏；全量行 + 无谓 filesort |
| **P0** | 术语/证候词频用 `JSON_TABLE` **全表解析 structured_data** | `RecordMapper.java:302-326,353-385,407-448` | `/stats/all`、`POST /stats`；1~4s |
| **P0** | `status` 谓词**完全没有索引** → 全表扫（唯一零覆盖列，且 schema 注释写错导致误删索引） | `RecordFilter.java:80`；`database-init.sql:82-85` | 记录搜索/清洗/导出 |
| **P1** | 单页 SQL 次数过多：报告 **36 次**、看板 **29 次**（冷缓存）；其中字典往返 **32 次** 是 N+1 | `StandardizationReportServiceImpl.java:502-530`、`StatsServiceImpl.java:196-276` | 首屏加载延迟 |
| **P1** | 看板 `overview` 对同一张表**聚合两次**，且 `MainLayout` 又调一遍 | `StatsServiceImpl.java:97-109`；`MainLayout.vue:344` | 首屏重复全扫 |
| **P1** | 分页每次 **COUNT + 列表** 两次扫；`JwtInterceptor` **每请求**查一次组织 | `RecordServiceImpl.java:557-559`；`JwtInterceptor.java:86` | 每次搜索/翻页 |
| **P1** | 记挂号/门诊号用前导通配 `LIKE '%x%'`，且 `outpatient_no` **无索引** | `RecordFilter.java:62,65` | 搜索退化全表扫 |
| **P1** | 趋势 `GROUP BY DATE_FORMAT(visit_time,'%Y-%m')` → 临时表 | `RecordMapper.java:203-214` | 看板额外开销 |
| **P2** | admin（无 org_id）排序/筛选变体缺索引 | `RecordFilter.java:99-140` | 部分场景 filesort |

**最重要的一句话**：真正拖慢前端首屏的不是"范围查询本身"（`EXPLAIN` 显示多数范围条件能走索引或 MySQL 8 的 skip scan），而是 **①报告页的全量物化 + 双 JSON 解析**、**②术语词频的 JSON 全表解析**、**③每页过多的往返次数（尤其字典 N+1）**。

---

## 0.1 实施进展（2026-10-17）

| 项 | 状态 | 落点 / 证据 |
|---|---|---|
| **P0-3** `status` 补索引 | ✅ 已实施 | 新增 `V7__add_records_status_index.sql` + 修 `database-init.sql`（原注释误称被覆盖）；真实库 `EXPLAIN`：org 路径 `Covering index lookup on idx_records_org_status_vt`、admin 路径 `idx_records_status_vt`，已从 `Table scan` 消除 |
| **P0-1** 报告页去掉无用 `ORDER BY` | ✅ 已实施 | `StandardizationReportServiceImpl.rowsInDomain` 改用 `RecordFilter.buildForAggregate`（同 WHERE、不拼排序键） |
| **P1-1** 字典 N+1（报告页 / 看板） | ✅ 已实施 | 新增 `IDictionaryTermStore.readEffectiveAll(orgId)`：一条 `org_id IN ('', ?)` 取回全部类型、内存合并（与逐类 `readEffective` 逐位同口径）；报告页 4 处与看板 5 类改用它，报告页字典往返 ≈32→1 条，看板 ≈10→1 条 |
| **P1-3** 分页 `COUNT` 带 `ORDER BY` | ➖ 无需改 | `buildForAggregate` 目前只接受 `FiltersDTO`，而 `countMatched` 传 `SearchDTO`；MySQL 对 `SELECT COUNT(*) ... ORDER BY` 直接忽略排序（聚合仅一行、无 filesort），无实际代价 |
| **P1-2** `overview` 重复聚合 | ⏸ 暂不实施 | 两条聚合口径不同（一条随筛选、一条全量待复核），且都走覆盖索引 `idx_records_org_grade_gov`；合并收益低于改动风险 |
| **P0-2** 词频 `JSON_TABLE` 全表解析 / 报告页命中行全量物化 + 双 JSON 解析 | ⏳ 待办（缓存仅缓解暖路径） | 词频接口前端暂未调用；报告页命中行物化未动（暖路径已 38ms）。根治需物化 `record_terms` 表 + 报告聚合下沉（见 §2 P0-2） |
| **P1-5 / P2-科室** 看板趋势 + 科室聚合全表扫 | ✅ 已实施 | 新增覆盖索引 `idx_records_org_agg(org_id, visit_time, department, grade)`（`V8__add_records_agg_index.sql` + `database-init.sql`）。`EXPLAIN ANALYZE`：两聚合由 `Table scan`（各 ~2.3s）→ `Covering index scan`（趋势 2306→50ms、科室 2287→16ms）。真实库 `/stats/extra` 冷路径 **5243→370ms**。根因是行宽 `avg_row_length≈12KB`、整表 ≈290MB，**不是**临时表 |
| **阶段 3** 聚合页短 TTL 缓存（报告 / 看板 / 扣分分布） | ✅ 已实施 | `StatsCache` 泛化 `getOrLoad` + 全局 kind 版本；报告 180s（salt=词典版本）、看板扩展 60s、扣分分布 60s。实测 extra 5243→54ms、report 5890→56ms、deduction 278→26ms；写路径失效实测通过。详见《缓存场景审查与改造方案》§十一 |
| **报告冷路径**：缓冲池 128MB→1GB + 解析并行（2026-10-28） | ✅ 已实施 | 见 §6：冷读 **6.2s → 2.3s**（缓冲池 6.2→3.3s；`toRows` 按记录并行 −26%）。口径与测试全绿，数字与旧版逐项一致 |
| **报告预热 + 缓存视角修复**（2026-10-28） | ✅ 已实施 | 见 §6.4 / §6.5：通用统计 key 补视角段（堵跨组织串数据）；启动后与每次写后自动预热「看全部」报告；`StatsController` 空白参数归一，让首屏请求命中预热 key。实测首页报告请求 **4994ms → 46ms**、写后首读 21ms，数字不变 |

**本轮验收**：`mvn test` 全量 **566/566 通过**（含新增 `readEffectiveAll` 合并口径测试 + `StatsCache.getOrLoad` 命中/失效/salt/白名单）；真实库 `EXPLAIN` 已确认 `status` 两路径命中新索引；真实库实测暖路径 extra 5243→54ms / report 5890→56ms / deduction 278→26ms，写后失效实测通过。

---


## 1. 现状：SQL 访问面与索引

- 全部 SQL 为 **MyBatis-Plus 注解**（无 XML）；`records` 的谓词集中在 `RecordFilter.java`。
- `records` 现有 **8 个二级索引**（与 `database-init.sql:80,92-107` 一致，已在真实库用 `information_schema.STATISTICS` 核对）：

| 索引 | 列 | 服务 |
|---|---|---|
| `idx_records_vt_desc_id` | `visit_time DESC, id ASC` | admin（无 org）默认序 / keyset |
| `idx_records_org_vt_id` | `org_id, visit_time DESC, id ASC` | 数据域默认序 / keyset |
| `idx_records_org_ct_id` | `org_id, create_time DESC, id ASC` | 导入时间范围（数据域） |
| `idx_records_org_dept_vt` | `org_id, department, visit_time DESC, id ASC` | 科室筛选 |
| `idx_records_org_grade_gov` | `org_id, grade, governed` | 指标卡 / 清洗统计 |
| `idx_records_org_score_desc_id` | `org_id, score DESC, id ASC` | 按分排序 |
| `idx_records_org_regno_id` | `org_id, registration_no, id` | 按挂号号排序 / 去重 |
| `uk_records_org_text_hash` | `org_id, text_hash` | 唯一约束 |

> 默认序 `visit_time DESC, id ASC` 是混合方向；admin 由 `idx_records_vt_desc_id` 覆盖（V4 引入），**暂无漂移**。

---

## 2. 发现（含 EXPLAIN 实测校正）

> 说明：审查 agent 给出的是静态推断；下表的"EXPLAIN 实测"是在真实库跑出的**修正**——MySQL 8 的 **index skip scan**、**LIMIT 早停**、**覆盖索引**使若干"缺索引"项没有想象中严重，已据实降级。

### P0-1 报告页全量物化 + 双 JSON 解析 + 无谓 ORDER BY
- 位置：`StandardizationReportServiceImpl.java:567-571`（`rowsInDomain`）、`:579-585`（`toRows`）
- 代码：
  ```java
  wrapper.select("id","visit_time","score","update_time","chief_complaint",
                 "self_report","structured_data","qc_results");
  return toRows(recordMapper.selectList(wrapper));   // build(...) 带 ORDER BY visit_time DESC, id ASC
  for (Record r : records) rows.add(new Row(r, parseJson(r.getStructuredData()), parseJson(r.getQcResults())));
  ```
- **EXPLAIN 实测**（无 LIMIT + 该投影）：`Sort: visit_time DESC, id -> Table scan on records (cost=13974 rows=24774)` → **全表扫 + filesort**。
- 代价：34182 行 × 2 次 Jackson 解析 ≈ **6.8 万次解析**；随后 7 个 pass（`byMonth`/`coverage`/`unmatched`/…）在 Java 里再走一遍。
- 修复（阶段 1）：`rowsInDomain` 改用 **`RecordFilter.buildForAggregate(...)`（无 ORDER BY）**；把可下沉的指标（按 normLevel 计数、按月计数、分数分布）改用 SQL 聚合；或整份报告加短 TTL 缓存。

### P0-2 术语/证候词频 JSON_TABLE 全表解析
- 位置：`RecordMapper.java:302-326`（`selectTermFreq`）、`:353-385`（`selectTermFreqMulti`）、`:407-448`（`selectPatternFreq`）
- 与 `selectPatternFreq` 尤其严重：`UNION ALL` 两趟扫 `records`，第二趟每行 `JSON_EXTRACT/TYPE/LENGTH` 各一次 + 递归 CTE ×64 拆分；`JSON_EXTRACT` 在 `WHERE` 里非 sargable。
- 注：组件文档已实测 `selectTermFreqMulti` 1.05~1.61s、四次 `selectTermFreq` 3.26~3.83s。
- 可达路径：`GET /stats/all`、`POST /stats`（前端页面暂未调用，但属同族接口）。
- 修复（阶段 3）：归一化时把词条写入 **`record_terms` 物化表**（`org_id, visit_month, kind, term, cnt`）并建索引，查询改为对该表聚合；至少确保只走 60s `StatsCache`、绝不在首屏触发。

### P0-3 `status` 无索引 → 全表扫（且是被 schema 注释误导删掉的）
- 位置：`RecordFilter.java:80`（`SearchDTO.status`）
- **EXPLAIN 实测**：`Table scan on records (cost=13974)` → 确凿全表扫。
- 根因：`database-init.sql:82-85` 注释声称 `idx_status` 被"org 前缀复合索引覆盖"，但 **`records` 没有任何索引含 `status`** → 是注释错误 + 误删。
- 修复（阶段 2）：
  ```sql
  ALTER TABLE records ADD INDEX idx_records_org_status_vt (org_id, status, visit_time DESC, id ASC);
  ALTER TABLE records ADD INDEX idx_records_status_vt     (status, visit_time DESC, id ASC); -- admin 变体
  ```

### P1-1 单页 SQL 次数 / 字典 N+1
- **报告页首屏 ≈ 36 次 SQL**：`dictQuality` 对 8 类 × (`readEffective`=base read 2 + own read 2)= **32 次**（`StandardizationReportServiceImpl.java:502-530`）+ `rowsInDomain` 1 + 版本查 2。
- **看板首屏 ≈ 29 次 SQL（冷）/ ~19（暖）**：`overview` 4 + `extra` 23（trend 1 + dept 1 + score 1 + 5 类 × `termCount` × 2 read × 2 查询 = 20）+ `departments` 1 + `governance/stats` 1。
- 修复（阶段 1）：新增 `DictionaryTermStore.readEffectiveAll(orgId)`——用 `WHERE org_id IN ('',?)` + 一次版本查询把 8 类一次取回，**32 → 2**；`termCount` 同理改批量。

### P1-2 看板 `overview` 重复聚合 + `MainLayout` 重复调用
- 位置：`StatsServiceImpl.java:97-109`：先 `selectOverviewFiltered`，再 `selectOverviewAll/Org`，但后者只用了 `pendingReviewCount`（total/合格/无效被丢弃）。
- `MainLayout.vue:344` 在应用外壳挂载时又发一次 `/stats/overview`。
- **EXPLAIN 实测**：`selectOverviewAll` 已能走 `idx_records_org_grade_gov` 的**覆盖索引扫描**（不是整行表扫）——所以"再加 grade 索引"**不必要**；真正该做的是**别查两遍**。
- 修复（阶段 1）：把 pending 合进同一次聚合（或第二条只 `SELECT SUM(...)`）；`MainLayout` 复用 Dashboard 的结果。

### P1-3 分页 COUNT + 列表两次扫；`JwtInterceptor` 每请求查组织
- 位置：`RecordServiceImpl.java:536`（list）、`:557-559`（count，且 `RecordFilter.build(...)` 带 ORDER BY）；`JwtInterceptor.java:86` → `OrgMemberMapper`。
- 每次 `POST /records/search` 至少 3 个往返（组织查询 + count + list）；`OrgServiceImpl.resolvePrimaryOrg:79-88` 兜底还能 N+1。
- 修复（阶段 1）：count 用 `buildForAggregate`（无 ORDER BY）；一次请求内缓存组织解析；`useDepartments` 结果前端单例复用。

### P1-4 前导通配 LIKE
- 位置：`RecordFilter.java:62,65`：`wrapper.like("registration_no"|"outpatient_no", ...)` → `LIKE '%x%'`。
- `registration_no` 有 org 前缀索引但被前导 `%` 废掉；`outpatient_no` **无任何索引**。
- 修复（阶段 1/2）：改 `likeRight`（前缀，可用 `idx_records_org_regno_id`）；确需子串则给 `outpatient_no` 建索引或 `FULLTEXT`。

### P1-5 趋势 `GROUP BY DATE_FORMAT(...)` / 科室 `GROUP BY department` 全表扫
- 位置：`RecordMapper.java:203-214`（趋势）、`:226-234`（科室）
- **EXPLAIN ANALYZE 实测**：两者均 `Table scan on records`（34182 行，各 ~2.3s）。根因**不是**行数、也不是临时表，而是 records 行极宽（`avg_row_length≈12KB`，`structured_data`/`qc_results` 两个 TEXT，整表 ≈290MB）：聚合只需 `visit_time` / `department` / `grade` 三列，却被迫读整行。
- 修复（✅ 已实施，`V8`）：一条覆盖索引 `idx_records_org_agg (org_id, visit_time, department, grade)` **同列覆盖两条聚合** → `Covering index scan`（≈20ms）。单条即可服务 org 与 admin（后者退化为整条索引覆盖扫，索引 ≈2MB），**无需** STORED 生成列、也无需 admin 变体。`score` 分布已足够快（96ms），未纳入。

### P2 其它（部分已被 MySQL 8 缓解，据实测降级）
- **admin 导入时间范围**：`EXPLAIN` 显示 LIMIT 查询走 `idx_records_vt_desc_id` 索引扫描 + 过滤 + **早停**；COUNT 走 `idx_records_org_ct_id` 的 **index skip scan**。→ 加 `idx_records_ct_vt` 属"锦上添花"，非必需。
- **admin 科室筛选 + 默认序（LIMIT）**：`Index scan on idx_records_vt_desc_id` + 过滤，早停 → 尚可。
- **admin `department` DISTINCT / 科室聚合**：科室聚合已由 `idx_records_org_agg` 覆盖（见 P1-5）；`selectDepartmentsAll` 的 DISTINCT 仍走 `idx_records_org_dept_vt` 覆盖扫描 + 临时表去重（返回行数=科室数，可忽略）。
- **admin 按 score/挂号号排序**：走对应对应 org 覆盖索引扫描 + 提前排序（`limit input to 20 rows per chunk`）→ 非默认排序的 admin 变体可选加索引。
- **冗余/无用索引（可安全删除，降低写放大）**：
  ```sql
  ALTER TABLE nlp_task DROP INDEX idx_create_time;   -- 无查询使用
  ALTER TABLE nlp_task DROP INDEX idx_nlp_task_org;  -- 被 uk_nlp_task_req 最左前缀覆盖
  ALTER TABLE qc_task  DROP INDEX idx_create_time;   -- 无查询使用
  ALTER TABLE qc_task  DROP INDEX idx_qc_task_org;   -- 被 uk_qc_task_req 最左前缀覆盖
  ALTER TABLE qc_task  DROP INDEX idx_status;        -- 仅 UPDATE ... WHERE id AND status
  ```
- **legacy 漂移**：`database-init.sql` 的索引治理（commit `cda73d5`）只改了 init、**未加 Flyway 迁移**；老库仍带 `idx_records_org/idx_records_org_visit_time/idx_registration_no/idx_status/idx_grade`。需补一个迁移统一（也顺带修 `status`）。

---

## 3. 优化计划（分阶段，按性价比排序）

### 阶段 0 — 度量基线（先量后改）
1. 开慢查询日志：`slow_query_log=1, long_query_time=0.5`，跑一轮真实操作采集 TOP SQL。
2. 前端用 Performance API 记录首屏：`/records/search`、`/stats/standardization-report`、看板首屏。
3. 对每条待改 SQL 先 `EXPLAIN ANALYZE` 存档，作为改后对比。

### 阶段 1 — 代码改动（零/低风险，先做）
- [ ] `rowsInDomain` 去 `ORDER BY`（改 `buildForAggregate`）+ 大范围改流式 `Cursor`。
- [ ] `overview` 合并两次聚合成一次；`MainLayout` 不再重复拉 `overview`。
- [ ] 新增 `readEffectiveAll` / 批量 `termCount`：报告 32 次字典查询 → 2 次。
- [ ] `countMatched` / `selectCount` 一律用无 ORDER BY 的 wrapper。
- [ ] `registration_no` 改 `likeRight`；`outpatient_no` 明确"不支持子串或加索引"。
- [ ] `existingHashFor` / QC fallback 显式列投影，避开 `structured_data`/`qc_results`。

### 阶段 2 — 索引增删（DDL + Flyway）
- [ ] 新增 `records.status` 索引（org + admin 两个变体）——**唯一确凿零覆盖的谓词**。
- [ ] 视实测补 admin 变体：`idx_records_dept_vt`、`idx_records_ct_vt`、`idx_records_score_desc_id`。
- [ ] 删除阶段 2 列出的 5 个冗余/无用索引。
- [ ] 新增迁移补齐 legacy 漂移（D2/D3），并修正 `database-init.sql` 的错误注释（status 那句）。

### 阶段 3 — 结构性优化
- [ ] `visit_month` STORED 生成列 + 索引，消除趋势的临时表。
- [ ] `record_terms` 物化表（归一化写路径维护，`StatsCacheInvalidator` 失效），词频查询改聚合该表。
- [ ] 报告整份结果短 TTL 缓存（对齐 `StatsCache` 现有做法）。
- [ ] 组织解析 `resolvePrimaryOrg` 加请求级/短 TTL 缓存。

### 阶段 4 — 前端
- [ ] 看板重卡（词频/报告）改懒加载或用户触发，不阻塞首屏。
- [ ] 首屏请求并行（`Promise.all`）并去重（Dashboard 与 MainLayout 共用 store）。
- [ ] `useDepartments` 结果单例复用，避免多页重复请求。

---

## 4. 验收指标

| 指标 | 现状 | 目标 |
|---|---|---|
| 报告页首屏 SQL 次数 | ~36 | ≤ 5 |
| 看板首屏 SQL 次数 | ~29（冷） | ≤ 10 |
| `EXPLAIN` 中 `Table scan on records` | status 查询、无 LIMIT 投影列表 | 除已知可接受项外为 0 |
| `/records/search` p95 | 待基线 | 下降 ≥ 40% |
| 报告页 p95 | 待基线 | 下降 ≥ 50% |
| 表扫描行数（报告） | ~34k 行 + 2 JSON/行 | 聚合下沉后不再整行物化 |

---

## 5. 风险与回退

- **加索引**：增加写放大（导入/清洗/质控批回写）。故先删 5 个冗余索引抵消。
- **STORED 生成列**：`visit_month` 占空间（~34k×7B，可忽略）；DDL 需在低峰执行。
- **物化表 `record_terms`**：存在一致性风险——必须挂到归一化/清洗/复核所有写路径的失效钩子；否则回退为"保留 JSON_TABLE + 缓存"。
- **去 ORDER BY**：确认报告/统计不再依赖行序（当前实现自己重排，安全）。
- 所有 DDL 走 Flyway，可回退为对应 `DROP INDEX`/`DROP COLUMN`。

---

## 附录 A：EXPLAIN 实测摘录（2026-10-16，真实库 `tcm_ehr`）

```
-- admin 导入时间范围 COUNT（无 LIMIT）
Aggregate: count(0)
  -> Filter: create_time between ...
     -> Covering index skip scan on idx_records_org_ct_id ...   (cost=828)

-- status 过滤
Limit: 20
  -> Filter: records.status = 'pending'
     -> Table scan on records                                     (cost=13974)   ← 无索引

-- admin overview 聚合（无 WHERE）
Aggregate: count(0), sum(case grade...)
  -> Covering index scan on records using idx_records_org_grade_gov  (cost=13974)

-- admin department DISTINCT
Sort: records.department
  -> Table scan on <temporary>
     -> Temporary table with deduplication
        -> Covering index scan on records using idx_records_org_dept_vt

-- 趋势 GROUP BY DATE_FORMAT
Table scan on <temporary>
  -> Aggregate using temporary table
     -> Covering index scan on records using idx_records_vt_desc_id

-- 无 LIMIT 的列表投影（报告/COUNT 风格）
Sort: visit_time DESC, id
  -> Table scan on records                                        (cost=13974)   ← 全表扫 + filesort
```

## 附录 B：评审覆盖（4 个并行 agent）
1. Mapper 反模式（17 个 Mapper + `RecordFilter` + 所有 `QueryWrapper`）。
2. 范围查询链路（`RangeFilter.vue` → `/records/search` → `RecordFilter.build` → 索引）。
3. 看板/报告（`StatsServiceImpl`、`StandardizationReportServiceImpl`、`/stats/*`）。
4. 索引与谓词（`database-init.sql` + Flyway V2–V6 + `schema-h2.sql`，含漂移核对）。

---

## 6. 报告冷路径二次优化（2026-10-28）

> 上下文：§0.1 的缓存把报告**暖路径**压到 56ms（`StatsCache` SWR）；本节处理**冷路径** ——
> 进程刚起 / 刚写入后第一次读 / Redis 冷。真实库基线：`records` 34182 行，
> `structured_data` 110.3MB + `qc_results` 11.4MB，HTTP 冷读 ≈ **6.2s**（128MB 缓冲池）。
> 前端同步把报告拆成独立 loading（主看板不再等它），本节只谈后端。

### 6.1 先量：冷路径到底花在哪

| 阶段 | 耗时 | 度量方式 |
|---|---|---|
| MySQL 取 9 列全量（≈122MB） | **1.8s** | 独立 JDBC 程序，同一条投影 SQL |
| `toRows` JSON 解析（Jackson） | 0.55s（顺序）/ **0.20s（并行）** | 同上，进程内计时，32 核 |
| 其余（MyBatis 行映射、8 类聚合、VO 序列化、Redis 写、HTTP） | ≈0.85s | 总时长与上两项的差额 |
| 其中 GC 暂停 | 0.16s / 7 次 | JFR `jfr view gc-pauses` |

结论：**取数占六成、解析占两成**。SQL 侧已无索引可用（条件只落在 `grade` / `org_id` 上，
`structured_data` 是 TEXT 且要全量读到），真正的地板是「把 122MB JSON 搬进 JVM」，其次才是解析它。

### 6.2 改动一：`innodb_buffer_pool_size` 128MB → 1GB（实例参数）

默认 128MB 缓冲池装不下 322MB 的 `records`，每次全表扫都真读磁盘：

| 度量 | 128MB（默认） | 1GB |
|---|---|---|
| `SELECT SUM(LENGTH(...))` 全表 | 3.55s | **0.52s** |
| 全表取 9 列（CLI，含管道开销） | 4.9–5.2s | 1.9–2.8s |
| `/stats/standardization-report` 冷读 | 6.2s | **3.3s** |

- 落盘方式：`SET PERSIST innodb_buffer_pool_size = 1073741824;` —— 写入数据卷里的
  `mysqld-auto.cnf`，容器重启/重建（沿用 `windows_mysql-data` 卷）都保留；
  `SELECT * FROM performance_schema.persisted_variables` 可核。
- 为什么不用 `my.cnf` / `docker-compose`：仓库里没有容器编排文件（`start-all.bat` 只探端口、不建容器），
  改 `my.cnf` 需要重建容器，收益与风险不成比例。
- 回退：`SET PERSIST innodb_buffer_pool_size = 134217728;`
- ⚠️ `windows_mysql-data` 卷被删除后该设置丢失，需重跑一次。

### 6.3 改动二：`toRows` 按记录并行解析

- 落点：`StandardizationReportServiceImpl.toRows`（≥512 行走并行，小数据域仍走顺序）
- 依据：记录之间完全独立；`ObjectMapper` / `log` 线程安全；`Collectors.toList()` 按源序归并
  （行序被 `rangeOf` 的首末接诊时间依赖，不能赌）
- 实测（同一份代码，仅 8080=旧 / 8081=新，两边都 `-XX:TieredStopAtLevel=1`，各 3 次冷读）：

| | 旧 | 新 |
|---|---|---|
| 冷读均值 | 3075ms | **2271ms（-26%）** |

- 口径核验：新旧两版 `coverage[0].total=33238`、`unmatched.total=78241` 完全一致 —— 只是变快，数字没动。
- 回归：`mvn test` 全绿，新增「并行解析与入参同序、两条 JSON 都真被解析」用例。

**合计**：冷读 6.2s → 2.3s（-63%），暖读 56ms 不变；再加 §6.5 的预热后，**管理员日常见到的
「起服务后 / 写完后第一次读」≈ 0**（预热已回填），只有带筛选或组员视角仍是冷算。

### 6.4 改动三：通用统计缓存 key 补上「视角」段（前置修复）

为什么必须先做：通用 kind 的 key 原为 `{kind}:v{全局版本}:{md5(筛选)}[:{salt}]`，**不含视角** ——
「管理员看全部」与「组员只看本组」在同一组织下算出**同一条 key**，谁先算，另一方就命中谁的结果。
单组织部署（本库只有 `grp-default-2026`）两者数据恰好相同，所以一直没暴露；但预热会主动往 key 里写
「看全部」的结果，等于把一个潜在串数据变成常态，故先补视角再做预热。

- 落点：`StatsCache.viewScope(boolean, String)`（`ALL` / 组标识 / `NONE`）、`cacheKey(kind, viewScope, ...)`、
  新的 7 参 `getOrLoadSwr(kind, keyParams, salt, viewScope, ...)`；报告侧由 `ReportContext` 快照算出视角传入，
  与 `invalidateForWrite()` 的 `scope()` / `ALL` / `GLOBAL` 三段版本号口径一致。
- 新 key 形状：`tcm:ehr:cache:stats:report:v{n}:ALL:{md5}:{salt}`；词频 `wordFreq` 本来就是
  `{scope}:v{n}:all:{md5}`，未动。
- 代价：所有通用统计 key 变一次（等价于一次全量失效），无数据风险。
- 测试：`StatsCacheTest` 新增「两视角不共用 key」「预热视角显式传入后管理员请求能命中同一把 key」
  「预热值不会被组员命中」。

### 6.5 改动四：启动预热 + 写后预热（把冷算藏到没人看的时候）

冷算 2.3s 只发生在两个时刻：进程刚起、写入后第一次读。两者都能提前算掉。

- `service.impl.ReportWarmup`（新组件）：`ApplicationReadyEvent` 后预热一次；
  挂在 `StatsCacheInvalidator.invalidateStats()` 的钩子上，任何写操作失效缓存后自动预热
  —— 挂在失效这一个口子上，是因为「失效了没预热 = 读者照旧阻塞」而「预热了没失效 = 旧版本写新值」，
  两者必须同源，不能指望 9 个写方法各自记得调。
- 约束：单线程守护池、队列 1、`DiscardPolicy`（一次导入会连续触发多次失效，不允许堆任务抢库）；
  预热失败只记一条 warn，绝不影响写入或读路径（读路径最多回到现算）。
- 视角：**只预热「看全部」**（`orgId=""` + `viewAllOrgs=true`，基础层词表版本与真实请求的 salt 逐字相同）；
  `auth.admin-can-view-data=false` 时不存在该视角，直接不预热。
- ⚠️ 上线实测发现的一个坑（已修）：预热用的是空 `FiltersDTO`（全 null），而前端清空筛选后发的是
  `department=&start=&end=`（空串），缓存 key 是 DTO 的 JSON 摘要 —— `{"department":""}` 与 `{}`
  是两把 key，于是首屏报告仍走 5s 冷算。修法：`StatsController.filters` 把空白参数归一成 null
  （语义与 `RecordFilter` 的 `notBlank` 判定一致），并加 `StatsControllerFiltersTest` 钉住
  「空串与 null 摘要相同」。副作用：overview / extra 的 key 也一次性变化（等价一次失效），无数据风险。
- 实测（真实库，backend 重启后）：
  - 启动 23s 时 `report:v22:ALL:...:fresh` 已由预热写好；
  - 首页实测：报告请求 **46ms**（归一前同一请求 4994ms）、overview 66ms、extra 55ms，页面数字 88.8% / 56.6% 不变；
  - 通过 API 新增 1 条病历 → 版本号 22→23，3s 内 `report:v23:ALL:...` 自动回填，报告首读 **21ms**；
  - 删掉该病历 → 24，再次自动回填，首读 **17ms**，库内已无残留（`COUNT=0`）；
  - 全程 `coverage[0].total=33238` / `unmatched.total=78241` 与预热前逐项一致。

**边界（没做到的部分，如实记录）**：

| 场景 | 是否受益 |
|---|---|
| 起服务后管理员第一次看报告 | ✅ 已预热 |
| 写入后管理员第一次看报告 | ✅ 写后自动预热 |
| 带日期 / 科室筛选的首次读 | ❌ 组合无界，不预热，仍为冷算 ≈2.3s |
| 组内成员视角首次读 | ❌ 只预热「看全部」，成员按自己的组冷算 |
| `admin-can-view-data=false` | ❌ 无「看全部」视角，预热整体关闭 |

### 6.6 没做的（与理由）

| 候选 | 收益 | 不做 / 暂缓的理由 |
|---|---|---|
| 聚合下沉 `record_terms` 物化表 | 直接省掉 110MB 搬运（-1.8s） | 需挂到归一化/清洗/复核/导入全部写路径的失效钩子，属结构性改造，见 §2 P0-2，另立批次 |
| 流式取数 + 边取边解析 | 再省 ≈0.5s | MyBatis `ResultHandler` 流式 + 生产者/消费者队列，收益 20%、复杂度与出错面明显上升 |
| 把 `qc_results` 拆成二次查询（仅合格行） | ≈0.15s | 只占 9%，多一条 SQL 与一次 join 语义，不划算 |

