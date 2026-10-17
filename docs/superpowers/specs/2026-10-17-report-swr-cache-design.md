# 报告接口 stale-while-revalidate 缓存设计

日期：2026-10-17
状态：已实施（2026-10-17）；`mvn test` 572/572 通过，e2e 见 §6
范围：`/stats/standardization-report`（标准化报告）一个接口；`extra` / `deduction` 本轮不动

## 1. 背景与实测

报告页是全站最重的读接口。本轮先量清了它 5s 冷算到底花在哪（`records` 34182 行，
`org_id=grp-default-2026`）：

| 项 | 实测 |
|---|---|
| `structured_data` 合计 | 96.5MB（均 3012B/行） |
| `qc_results` 合计 | 13.1MB（均 408B/行） |
| 服务端读这两列（`SUM(LENGTH(...))`，含全量磁盘读） | **~3.0s**，连跑三次 3.03/3.03/3.01s 不变 |
| 加权基线（`SUM(score)`，窄列） | 0.10s |
| `innodb_buffer_pool_size` | 134217728（128MB） |
| `records` 表体 | data 290MB + index 43MB |

结论：

1. 报告**已经做了列投影**（`StandardizationReportServiceImpl.rowsInDomain:591` 只取 8 列，
   由 `RecordColumnNameGuardTest` 守卫），没有可砍的列、也没有 `ORDER BY`；
   **SQL 侧没有剩余优化空间**。
2. 那 ~3.0s 是**磁盘 I/O**，不是缓存没热 —— 表体 290MB + 索引 43MB 超过默认 128MB 的
   buffer pool，装不下，重复读不会变快。
3. 剩下约 2s 是 Jackson 解析（每条 `structured_data` + `qc_results` 各一次，
   `toRows:601`）。
4. 因此：**5s ≈ 3s 读盘 + 2s 解析，而「读盘」就是负载本身**，靠 SQL / 解析优化都消不掉大头。

方向随之确定：**不能让它更快，就让它别挡在用户前面** —— 用 stale-while-revalidate（SWR）。

## 2. 目标与非目标

| 维度 | 决策 |
|---|---|
| 核心目标 | 缓存**因时间过期**时，用户请求**立即拿到旧报告**，那 5s 移到后台重算 |
| 正确性 | **写入后首个请求仍阻塞算新值**（正确性优先）：绝不把「已改动的旧数字」端给用户 |
| 接口 | 只改报告；`extra` / `deduction` 仍用现有 `getOrLoad`（无 SWR） |
| 前端 | **0 改动**（响应始终即时；`generatedAt` / 词典版本回显已能表达新鲜度） |
| 不做 | 并行解析（B）、物化报告（C）、调大 `innodb_buffer_pool_size` —— 均只写进文档，不动手 |

方案 A（本设计）单独即可消灭「用户等待 5s」这一核心痛点；B/C 是进一步的吞吐/彻底消除，非本轮目标。

## 3. 机制：双 key + 后台单飞重算

### 3.1 缓存值拆成两把 key（值格式不变，仍是 VO 的 JSON）

| key | 值 | TTL |
|---|---|---|
| `dataKey` | VO 的 JSON（**与现状完全一致**） | **hard TTL = 基准 ×10**（180s → 30min）± 抖动 |
| `dataKey:fresh` | `"1"`（新鲜标记） | **soft TTL = 基准**（180s）± 抖动 |

`dataKey` 的构造与现有 `getOrLoad` 完全相同（`{kind}:v{版本}:{参数摘要}[:{salt}]`），
因此**写入失效（版本号自增 → key 变）的语义天然保留**。

### 3.2 读路径

```
json = GET dataKey
if json 存在 且 可反序列化:
    if GET dataKey:fresh 存在:      # 新鲜
        return 旧值                          # 不重算
    else:                                # 陈旧（时间过期，数据未改）
        scheduleRefresh(dataKey, ...)        # 丢给后台池，本请求不等
        return 旧值                          # 立即返回
else:                                    # 冷启动 / 反序列化失败
    return loadSingleFlight(dataKey, ..., swr=true)   # 阻塞现算，写 data + fresh
```

关键点：陈旧判定只看 `fresh` 标记是否存在，**不看值**；因此读路径始终只在 Redis 上做
两次 `GET`，不需要额外的时钟字段或格式改造。

### 3.3 后台重算（`refreshAsync`）

- 提交到 `StatsCache` 自持的**有界线程池**：core 1 / max 2 / 队列 16 / 守护线程 /
  `DiscardPolicy`（满则丢弃，下次读再触发）/ `@PreDestroy` 关闭。
- **复用现有单飞锁**（`LOCK_PREFIX + dataKey`）：多实例、多并发读只有一个真重算，
  其余拿到锁失败即退出。
- 重算走 `computeAndCache(..., swr=true)`，重写 `data` + `fresh`。
- **重算的任何异常只记日志，绝不影响本次返回**；池满 / 已关闭同样静默跳过。

### 3.4 正确性（写入 → 新 key → 冷算）

写入调 `StatsCacheInvalidator.invalidateForWrite()` → `GLOBAL` 版本号自增 →
`dataKey` / `fresh` 都落在**新版本段**、均为空 → 落到第 3.2 节的冷分支，**阻塞现算新值**。
即「写入后一定看到新数字」，无需任何额外代码，是版本号前缀机制的固有结论。

### 3.5 降级

| 场景 | 行为 |
|---|---|
| Redis 不可用 | 读 `dataKey` 失败 → null → 冷分支 → 直接现算（与现状一致，不因缓存挂掉打不开页面） |
| `data` 在、`fresh` 读失败 | 按陈旧处理：返回旧值 + 尝试后台重算（不返回错误） |
| 后台重算持续失败 | 最多陈旧 30min（hard TTL），之后 `data` 也过期 → 回落阻塞现算 |
| 部署前遗留的旧值（无 `fresh` key） | 判为陈旧 → 返回旧值 + 后台刷新，**不产生冷算尖峰** |
| 值反序列化失败 | 视同未命中 → 阻塞现算（覆盖类结构不兼容的极端情况） |

## 4. 关键前置：现算体必须与请求上下文解耦 ⚠️

SWR 的后台重算**没有请求线程**，而 `RequestContextHolder` 是线程绑定的
（`RequestUtils` 类注释已明示：后台线程拿到的是 `"unknown"` 而不是 null，且
`RecordFilter` / `RequestUtils` 的取值一律 fail-closed）。核对报告现算体的线程绑定依赖，
共 4 处：

| 位置 | 依赖 |
|---|---|
| `StandardizationReportServiceImpl.java:255` | `termStore.effectiveDictVersion(RequestUtils.currentOrgId())`（salt） |
| `:274` | `termStore.readEffectiveAll(RequestUtils.currentOrgId())` |
| `:300` | `termStore.effectiveDictVersion(RequestUtils.currentOrgId())`（sourceVersion） |
| `:590` | `RecordFilter.buildForAggregate(RecordFilter.domainOrgId(), filters)` ← 内部再读 `viewAllOrgs()` |

若不在后台线程里“重放”上下文，`currentOrgId()` 会退化成 `""`、`domainOrgId()` 落到
「不可能值」哨兵、`viewAllOrgs()` 恒 false —— **后台重算会算出错误（甚至空）的报告并回填**。
这是本方案唯一的真实风险点，必须在实现 SWR 之前先修掉。

**做法**（与代码库既有约定一致，见 `RequestUtils` 注释「异步任务必须在提交线程捕获所需值
再传给 worker」）：在请求线程的 `report()` 里捕获一份**上下文快照**，显式传进现算体，使其成为纯函数：

- 新增 `record ReportContext(String orgId, String domainOrgId, boolean viewAllOrgs)`；
- `report()` 在**请求线程**里组好快照（`RequestUtils.currentOrgId()`、
  `RecordFilter.domainOrgId()`、`RequestUtils.viewAllOrgs()`），再交给 loader 闭包；
- `computeReport(f, ctx)` 用 `ctx.orgId()` / `ctx.domainOrgId()` / `ctx.viewAllOrgs()` 取代
  上述 4 处线程绑定读取；
- `RecordFilter` 增加**加法式**重载
  `buildForAggregate(String orgId, FiltersDTO, boolean viewAllOrgs)`，供
  `rowsInDomain(domainOrgId, viewAllOrgs, f)` 调用（保留原 2 参重载行为不变）。

> 说明：不在后台线程里直接 `RequestContextHolder.setRequestAttributes(...)` 重放 ——
> 请求结束后容器会回收/清理 servlet request 及其属性，5s 后读取处于未定义状态。显式传参
> 既确定、又可单测，且与本项目对批任务的既有做法一致。

## 5. 改动清单

| 文件 | 改动 |
|---|---|
| `common/cache/StatsCache.java` | 新增 `getOrLoadSwr(...)`、`FRESH_SUFFIX`、`SWR_HARD_FACTOR`、`refreshPool` / `refreshExecutor`、`refreshAsync`、`isFresh`；`computeAndCache` 增 `swr` 开关（非 SWR 调用方行为不变） |
| `common/utils/RecordFilter.java` | 新增 `buildForAggregate(String, FiltersDTO, boolean)` 重载（原签名委托它） |
| `service/impl/StandardizationReportServiceImpl.java` | 新增 `ReportContext`；`report()` 组快照并改调 `getOrLoadSwr`；`computeReport` / `rowsInDomain` 改吃快照 |
| `test/.../StatsCacheTest.java` | 新增 SWR 单测（见 §6） |

前端、其它 kind、数据库结构、迁移脚本均**不改**。

## 6. 验收标准

单测（`StatsCacheTest`，沿用现有 mock `StringRedisTemplate` + Map 键空间风格，
测试用同步执行器注入以保证确定性）：

1. **新鲜**：`data` + `fresh` 都在 → 返回旧值，**不调 loader**。
2. **陈旧秒回**：`data` 在、`fresh` 不在 → **返回的是旧值**（不是新值）、且触发一次 loader。
3. **写入失效**：`invalidateForWrite()` 后 → 版本段变、两 key 皆空 → **阻塞现算**，且新 key 落在 `v1`。
4. **旧格式值**：只有 `data`、没有 `fresh`（模拟升级前遗留）→ 按陈旧处理（返回旧值 + 刷新），不冷算。
5. **Redis 故障**：读失败 → 直接现算返回，不抛异常。

端到端（`:19090` 掷地实例 + 真实 MySQL/Redis，走真实 HTTP；管理员全量 34182 行）：

| 步骤 | 实测 |
|---|---|
| 首请求（冷算） | **4358ms**，`records=34182` |
| 180s 内再请求 | **14ms**，`generatedAt` 不变（命中） |
| key TTL | data **1798s** / fresh **181s**（= 设计值 1800 / 180） |
| 只删 `:fresh`（模拟时间到期）→ 再请求 | **15ms 秒回旧值**（对比冷算 4358ms） |
| 等后台重算 | `:fresh` 于 **~2.8s** 后被重写；回填 `range.records=34182`，与请求线程结果**字节一致** |
| bump 全局版本（模拟写入）→ 首请求 | **4615ms 阻塞**，现算新值 |

## 7. 明确不做（及理由）

- **B 并行解析**：只省得掉 ~2s 里的部分，那 3s 读盘省不掉；收益有限、引入并发复杂度。
- **C 物化报告**：彻底消除冷算，但只能覆盖默认筛选（部门 × 时间 × 分级组合无法穷举），改动最大。
- **调大 `innodb_buffer_pool_size`**：是让那 3s 读盘变内存命中的唯一低成本手段，且对全站重查询都有益，
  但属部署/运维决定 —— 仅记入文档作为建议，不改代码、不改配置。
