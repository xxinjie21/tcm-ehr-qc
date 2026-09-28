# AI 执行计划

> ## ⛔ 本文件是**待批准的清单**，不是执行指令
>
> **未经用户明确指示，不得执行本文件的任何一项。**
> 用户说「改 / 修 / 执行 / 做」并**点名范围**时，才动手，且只做点名的那部分。
> 其余时间本文件只作参考；发现问题**写进《开发指南与待办》待办**，不擅自修复。
>
> 规则出处：`开发指南与待办.md`「二、开发规范 · 协作铁律（最高优先级）」。

---

> **用途**：把 `多课题组改造计划.md`、`开发指南与待办.md`、`可读性重构审查报告.md`、`前端专项审查报告.md` **四份文档的全部待办项合并、去重、排序**，形成一份可直接执行的任务清单；**另含 §七 专项计划**（源自 `docs/未命名.md` 的用户目标 + 2026-09-28 当面对齐的决策，非四份文档的合并）、**§八 待定项（提升并发量，未拍板）** 与 **§九 实体标准化改造 · 抽取修复**（源自 2026-09-28 数据集调研 + 与另一 AI 5 轮交叉复核，非四份文档的合并）。
> **执行者**：AI（本仓库协作代理）。
> **生成日期**：2026-09-28。
> **状态**：**只列未完成项** —— 已完成、或判定不做、或已被其它章节取代的条目**已从本文删除**。清理记录：**P1.4** 词典分页（已完成）、**P1.6**「TDZ」（经复核前提不成立，Vue 3 `<script setup>` 下不触发）、**P5.5** 按钮名（早已改对）、**P5.10** 只写字段（无消费者）、**P5.1 / P5.12**（内容并入 §七 L5 / L4–L6）。历史见 `git log`。**这不是"已核实无误"的清单** —— 行号来自实测但**会被后续代码变动冲掉，执行前必须逐条重新定位**；涉及设计决策的项已回退到《多课题组改造计划》的「待用户拍板」。

---

## 一、四份源文档的定位

| 文档 | 性质 | 产出什么 | 改造面 |
|---|---|---|---|
| `多课题组改造计划.md` | **功能改造**（设计已定，**6 项待拍板中 #1/#4 已由 §七 闭合，该文档尚未同步**） | R1~R6：数据隔离 + 角色重构 | 表结构、认证、RecordFilter、34 门禁、前端 19 处、2 新页面、openapi 50 处 |
| `可读性重构审查报告.md` | 代码质量 | R1~R6 + 单列：注释 / 重复 / 命名 / 长函数 / 结构 | 全库 182 文件（后端 + 前端 + Python） |
| `前端专项审查报告.md` | 前端性能 / 视觉 | P0~P3：加载 / 渲染 / 视觉 / 商业完整性 | `frontend/src` 25 个 SFC + 构建配置 |
| `开发指南与待办.md` | 缺陷台账 | 21 项已确认缺陷（高 3 / 中 10 / 低 8） | 跨前后端 |

四者**互不重复但严重交叉**：多课题组计划改的文件，正是另两份报告要重构的文件。因此**执行顺序决定返工量**。

---

## 二、重叠合并结论（同一件事只做一次）

| 合并后的事项 | 四份文档中的重复出处 |
|---|---|
| 科室下拉缓存 + `useDepartments()` | 前端 §2.4 / 前端 §4 / 可读性 §2.2「部门加载三处」 |
| 统一二次确认（`confirmBox`） | 前端 §3 / 前端 §P2 |
| 统一空态（`EmptyState`） | 前端 §3 |
| 三份 `FIELDS` 合一 + 列渲染组件 | 可读性 §2.2 / §4 / 前端 §4 |
| 分页常量 `PAGE_SIZES` | 可读性 §2.2 / 前端 §4 |
| 日期格式化统一走 `fmtDateTime` | 可读性 §2.2 / 可读性 §三 |
| 重置筛选块 `resetFilters()` | 可读性 §2.2 / 前端 §4 |
| 视觉令牌（`--surface` 等） | 前端 §7.1 / §7.6 / §P1 |
| 断点收敛 | 前端 §7.5 / §P3 |
| 前端校验与后端不一致 5 处 | 待办·中 / 前端 §8.3（数据隔离相关半条） |

---

## 三、分阶段执行计划

**排序原则**：① 不受大改影响的正确性缺陷先修 ② 结构化改造（多课题组）居中 ③ 在将被重写的代码上做局部重构放最后。

---

### 阶段 1 · 正确性缺陷（不受大改影响，优先）

| # | 事项 | 位置（已实测） | 改法 | 验收 |
|---|---|---|---|---|
| **P1.1** | 可编辑列表用 index 作 key（**数据错位风险**） | `Qc.vue:104`（`customFormats`）、`Qc.vue:112`（`form.consistency`） | 新增行生成稳定 `uid`（`crypto.randomUUID()`），`:key="c.id"`，提交前剥离 `id` | 删中间行后下拉值不错位；仅改 `Qc.vue` |
| **P1.2** | 5 处列表请求无竞态保护（**脏渲染**） | `Records.vue:312`、`NlpExtract.vue:400`、`Qc.vue:622`（`loadPrecheck`，原记 L609 已漂移）、`AuditLog.vue:145`、`Review.vue:286` | 仿 `components/TermInput.vue:32` 的 `seq` 取号：发起时 `const mine = ++seq`，返回后 `if (mine !== seq) return` | 快速连点翻页，列表与页码一致 |
| **P1.3** | python 截断无提示（**尾部实体静默丢失**） | `python-nlp/main.py:143` → `NlpExtractVO.truncated` | 前端 `NlpExtract.vue` 读 `res.data.truncated`，为 true 时横幅提示「文本超长已截断，尾部实体可能丢失」 | 造超长文本，横幅出现 |
| **P1.5** | 前后端校验不一致 5 处 | 就诊次数（`Records.vue` 前端 ≥1 / `CreateRecordDTO` 无 `@Min`）· 密码（前端 ≥6 / `RegisterDTO` 无 `@Size`）· 科室+医生（前端无限制 / `database-init.sql` 50 字）· 性别枚举 3 套 · 批任务 `limit` 无上限 | 后端补 `@Min` / `@Size` / `@Max`；前端补 `maxlength`（对齐 DB 列宽） | 直调 API 传越界值被 400 |
| **P1.7** | AiAssistant 同一问题传两遍 | `components/AiAssistant.vue` `ask()`：L233 先 `push`，L242 再 `historyText()` 读回（`slice(-6)` 含本条） | **保持 push 顺序不动**（L232 注释明确「让界面立刻有反馈」，调换会变慢），改 `historyText()` 内部排除末条：`messages.value.slice(0, -1).slice(-6)` | 请求体里 `question` 与 `history` 末行不再重复，且用户消息仍立即回显 |
| **P1.8** | `LogicCheckDTO.herbList` 收了不用（**规则失效**） | `QcServiceImpl.checkLogic:149-151` 只放 `patternList`/`treatmentList`/`formulaList` | ⚠️ **key 必须用 `herbs` 而非 `herbList`**：`LogicChecker.listOf:69` 按 `EntityTypes.byKey(type).structuredKey()` 取值，而 `herb` 的 structuredKey 是 **`herbs`**（`EntityTypes.java:39`）。写 `"herbList"` 永远读不到，**修复是空操作**。正确：`data.put("herbs", dto.getHerbList())` | `/api/qc/check/logic` 传中药后，「证候→中药」9 条规则可触发 |

---

### 阶段 2 · 多课题组改造（R1~R6）

> ⛔ **前置闸门**：R1 开工前必须先清掉《多课题组改造计划》的「待用户拍板」6 项。其中 **#1（`qc.batch.max-records: 30000` < 实际 35,355）是现在就 400 的功能阻塞** —— **已由 §七 L4/L5/L6 解决**（异步化 + 40000），做掉 L4~L6 后本闸门即清。**#4（`JAVA_HOME`）已闭合为 `F:\jdk24`**。剩余 4 项见该计划。

**完整内容见 `docs/多课题组改造计划.md`**，此处仅列批次与闸门：

| 批 | 内容 | 闸门 |
|---|---|---|
| **R1** | 建 2 表 + 改 4 表 + 存量迁移（500 条→`DEFAULT-2026`；`auditor` 任 owner；**`admin` 以 member 入组**，见该计划 §4.4 开关） | 迁移脚本幂等 |
| **R2** | 注册两形态 · JWT 不加组 · `JwtInterceptor` 每请求查组（**须带 `is_primary=1` + `g.status='active'`，且 DB 故障降级为无组**）· `RequestUtils` 补 3 访问器 · 菜单四套 · 停用拒登。⚠️ **前置硬依赖**：本步给每个请求加一次 DB 往返，而**当前连接池是 Hikari 默认 10**（`application.yml` 无 `hikari` 配置）→ **必须先落地 §八 C1（调大连接池）再做 R2**，否则并发能力会不升反降 | 3 个相关测试类绿；**停用组后旧 token 立即失效**；压测确认请求吞吐未下降 |
| **R3** | `RecordFilter` fail-closed + A 类 8 处换形参（**`domainGrade` 一并改名**）+ **B 类 4 处硬编码 `ROLE_ADMIN` 处置** + C 类 3 处 Mapper SQL + 4 处角色判断 + **13 类 20 处漏点补校验** + `review_tasks`/`nlp_task` 打组 | `RecordIsolationTest` |
| **R4** | 34 门禁 → 留 5 删 29 · ~~删 `POST /api/logs/purge`~~（**已提前到 §七 L3**）· 日志 3 读接口加 `operator`（**含独立的 `selectDistinctActions` SQL，实为 4 个 service 方法**）→ **改由 §七 L7 按 `group_id` 三档实现** | 前后端 build；普通用户看不到他人日志与操作类型 |
| **R5** | 13 接口 + `Groups.vue`/`MyGroup.vue` + `Register.vue` 建组选项 + 无组引导页 | 手工回归：申请→审批→拉人→隔离 |
| **R6** | openapi **50 处**（**BOM+LF**）+ 文档回写 + 三方一致性脚本 | 脚本 0 差异 |

> **顺序硬约束**：R3 必须先补组校验，R4 才能删门禁（否则直接越权）。

---

### 阶段 3 · 可读性重构（R1~R6）

> 放在多课题组之后：这样 R4/R5/R6 的重构落在**最终逻辑**上，不返工。

| 批 | 内容 | 风险 |
|---|---|---|
| **P3.1** | 注释风格 §六 10 项（`AiServiceImpl` 补 `@param` · HTML/CSS 注释空格 · 步骤标号混用 · `Qc.vue:654-656` 错位）+ Python §七 4 项 | 低 |
| **P3.2** | 低风险去重 §二：`stripCodeFence`（`AiServiceImpl:611` / `DictionaryServiceImpl` 内）· `isBlank` 6 份收敛 · 日期格式化 · **分页常量（`page-sizes` 现有 3 套：`Records`/`NlpExtract`/`AuditLog` 为 `[10,20,50]`，`Dictionary` 为 `[20,50,100,200]`，须一并收敛）** · 重置块 · `fieldOf` · 部门加载 | 低 |
| **P3.3** | 命名统一 §三：路由风格（5 类级 vs 6 方法级）· 缩写变量（`o`/`m`/`sd`/`sr`）· 前端 4 个「重置」名 · `fieldsOf`→`groupFields` · `getNlpBatch`→`getNlpBatchProgress` | 低 |
| **P3.4** | 长函数拆分 §四（只拆 5 个）：`importRecords` · `buildContext` · `normalizeStructuredData` · `handleImport` · `saveRules` | 中（须保请求时序与 `finally`） |
| **P3.5** | 字段定义合一 §二/§四：三份 `FIELDS`（`Records.vue:254`/`Review.vue:214`/`RecordDetailDialog.vue:54`）+ 列渲染组件（`AgeGenderCell`/`VisitTimeCell`） | 中（逐页比对渲染） |
| **P3.6** | 结构 §五：`GovernanceController` 错误体下沉到 `GlobalExceptionHandler` · 数据访问风格统一（直注 Mapper vs `ServiceImpl`） | 中（需回归错误码与权限） |
| **P3.7** | 单列：`/api/logs` 的 `size`→`pageSize`（**先改 openapi，BOM+LF**）· `useAiStore` 改名 | 中高（触契约） |

---

### 阶段 4 · 前端性能与视觉

| # | 事项 | 位置 | 改法 | 收益 |
|---|---|---|---|---|
| **P4.1** | Element Plus 按需引入 | `main.js:3-5` | `unplugin-vue-components` + `unplugin-auto-import`，`ElMessage`/`ElMessageBox` 显式引入。⚠️ `Qc.vue` 已用 **`el-select-v2`**（`Qc.vue` 一致性规则下拉），需确认该组件能被自动解析 | 首屏 gzip −200 kB |
| **P4.2** | 模板内函数提 computed（4 处） | `AiAssistant.vue:40` `lines()` · `Records.vue:186` `fieldsOf()` · `Qc.vue:88/89/91/94` `fmtOf` · `Review.vue:103/143/149` `originalText()` | 提为 `computed`，模板读缓存结果 | 消除每键重算 |
| **P4.3** | Dashboard 待办条 3 列→2 列 | `Dashboard.vue:310-315` | `repeat(2,1fr)` | 消掉右侧 1/3 空白 |
| **P4.4** | 视觉令牌 | `theme.css` + 约 20 处 `#fff` + 130+ 处颜色字面量 | 加 `--surface`/`--surface-sub`/`--danger-surface`/`--ochre-surface`，批量替换；圆角收 2/4/6 三档 | 拉齐卡片与状态色 |
| **P4.5** | 科室下拉缓存 + `useDepartments()` | `RangeFilter.vue:73-81` + 4 页 | Promise 单例缓存 + 抽 composable | 每页少 1 请求 |
| **P4.6** | Dashboard 三接口并行 | `Dashboard.vue:258` | `governanceStats()` 并入 `:251` 的 `Promise.all` | 管理员省 1 RTT |
| **P4.7** | resize 加 rAF 节流 | `Dashboard.vue:295` | `requestAnimationFrame` 合并；卸载取消帧 | 拖拽不掉帧 |
| **P4.8** | 轮询降频 + 页面可见性 | `NlpExtract.vue:770` | `loadBatchList` 降到 10s；`watch(activeTab)` 停轮询；`visibilitychange` 暂停 | 请求量 −80% |
| **P4.9** | 对比度不达标 | `MainLayout.vue:231`、`Review.vue:786` 的 `#a09c90`（2.6:1）；`Qc.vue:742` 临界 | 换 `--text-sub`（4.64:1） | 达 WCAG AA |
| **P4.10** | 骨架屏用了渐变（**违反「禁渐变」**） | `AiInterpretCard.vue:173/186` | 纯色 + `opacity` 呼吸 | 合规 |
| **P4.11** | 评分瀑布最终得分无突出 | `Qc.vue:954` `.wf-item.end`（内含 L295 的 `.wf-num`，**字号未单独放大**） | 给最终得分的 `.wf-num` 加到 16~17px bold | 扫读抓重点 |
| **P4.12** | 统一 confirm / 空态 / 动效 | `Qc.vue:576/660`、`Governance.vue:272` 裸 `ElMessageBox.confirm`；Dashboard/Governance/Review/Dictionary/AuditLog 裸 `el-empty`；hover 1px vs 2px | 走 `confirmBox` + `EmptyState`；动效统一 1px/`0.15s ease` | 交互一致 |
| **P4.13** | AiAssistant 拖动改 `transform` + rAF | `AiAssistant.vue:127/153-159` | `transform: translate()` + rAF | 进合成层无 layout |
| **P4.14** | 间距体系 | 7/9/11/13/18/22/26/48 混排 | 收 4/8/12/16/24 五档令牌 | 节奏统一 |
| **P4.15** | gzip / brotli 预压缩 | `vite.config.js:5-6` | 加 `vite-plugin-compression` | 依赖部署端 |
| **P4.16** | AuditLog 表格 `max-height` | `AuditLog.vue:49` | 补 `max-height="520"` | 50/页可用 |
| **P4.17** | 断点收敛 | `1559/1400/1200/1199/900/720` | 收 1560/1200/900 三档 | 需两档实测 |
| **P4.18** | `MainLayout.vue:245` `max-width:1600px` 死值 | 同上 | 改实际值或加注说明 | — |
| **P4.19** | `.gov-stats` 无 `wrap` | `Governance.vue:452` | 加 `flex-wrap:wrap` | 防挤 |

> **样式类改动一律须在 1600×900 与 1366×768 两档视口实测后合入**（项目硬规则，用户明确反感「框内滚动」）；**不改排版结构与布局**。

---

### 阶段 5 · 加固与收尾

| # | 事项 | 位置 | 改法 |
|---|---|---|---|
| **P5.2** | 静默截断其余 5 处无提示 | 扣分明细前 20 · 趋势 12 个月 · 复核跳过已删病历 · 失败样本前 50 · 任务列表 `LIMIT 50` | 各补 `truncated` 标记 + 界面提示（**其中「扣分聚合 `MAX_SCAN_RECORDS=3000`」另见 §七 L4**，非本条 5 处） |
| **P5.3** | 同一数据传两遍 4 处 | `Review.vue` 列表带全文又回查 · `ReviewServiceImpl:102` 每页内联整份 `structuredData` · `ReviewServiceImpl.parse` · `QcCheckDTO` 可同时带 id 与内联 | 列表不内联全文；或详情按需拉 |
| **P5.4** | 资源与并发加固 | 4 处 static 可变 map（`RecordServiceImpl.HEADER_FIELD`、`EntityTypes.BY_KEY` 等）· `LlmClient` 重建锁 · `OperationLogger` 实例锁 | map 改 unmodifiable；锁粒度评估（**注：`OperationLogger` 的实例锁随 L2 删 `writeFile` 一并消失，见 §七 L2**） |
| **P5.6** | 业务文字需过「脱敏」 | 界面泄漏正则 / 英文 key / 内部目录名处 | 换成业务语言 |
| **P5.7** | AI 助手与弹窗层级 | `AiAssistant.vue` vs 模态弹窗 | 定策略：提层级 或 收进弹窗 |
| **P5.8** | 空态与空列无解释 | `Dictionary.vue:37`（`:empty-text`）与 L51 `<template #empty>` 插槽并存，前者是死代码 · 「操作对象」恒空 · 别名列整列「无」 | 留着的空态解释「为什么空、怎么才有内容」；删死代码 |
| **P5.9** | 同一事实两个权威 | 架构性：判定结论口径只存一处 | ⚠️ **本条位置列写的是「方法」而非文件锚点**（"全局排查前端重算业务规则"），故**不可直接执行**。建议先做一次排查、产出具体条目（哪几个事实、哪几处重算）后再排期；**不要作为一条笼统任务开工** |
| **P5.11** | `RecordServiceImpl.taskStore` + 死接口 | `RecordServiceImpl:119/317` · `GET /api/records/import/{taskId}/status` | 导入是同步的，`taskStore` 无真实消费者 → 删接口与状态存储（**与多课题组 R2 联动**） |
| **P5.13** | 验证类未做 | 前端真机点击 · NLP 推理实测 · 3.5 万条分页 | 起服务实测（需授权） |

---

## 四、明确不做（三份文档的「不做」合并）

| 项 | 出处 | 理由 |
|---|---|---|
| `api/*.js` 薄封装层 | 可读性 §九.1 | 有意分层（集中 baseURL/timeout/参数形状） |
| `str(Object)` 三份直接合并 | 可读性 §九.2 | 语义不同（不 trim / trim 转 null / null 转空串），合并即改行为 → 应按语义**改名**后收敛（见 P3.2） |
| 为一致性改契约字段名 | 可读性 §九.4 | 原则「能不动就不动」；例外仅 `/api/logs` 的 `size`（P3.7 单列评估） |
| 归一 LLM 判定 | 待办·中 | 破坏 ES 唯一权威的确定性 |
| 解析断点续跑 | 待办·中 | 只做「中断标记 + 可重跑」 |
| 多实例部署一致性 | 待办·低 | 单机单实例定位 |
| 更改排版结构与布局 | 前端 §十 | 用户明确要求；视觉建议限于配色/令牌/层次/状态反馈/动效 |
| 用户管理独立页面 | 多课题组 §二 | 用「课题组 + 成员」模型覆盖，不另建 |
| 自助建组免审批 | 多课题组 §二 | 已定「自助申请 + 管理员审批」 |
| 日志清理功能（`purge`） | §七 7.1 #6 | 用户 2026-09-28 决定不做；**接受 `operation_log` 无界增长**（已知未闭环） |
| 日志 3 个月保留期 | §七 7.1 #7 | 用户决定不做（不引入定时任务） |
| 记录操作 IP | §七 7.1 #5 | PIPL 最小必要；用户决定不记 |

---

## 五、每阶段闸门
| 阶段 | 闸门 |
|---|---|
| 1 | `mvn -o test` 绿 + `npm run build` 通过；P1.1/P1.2 手工复现确认修复 |
| 2 | 按各批闸门（见多课题组计划 §十一）；终态 `RecordIsolationTest` + 三方一致性脚本 0 差异 |
| 3 | 每批 `mvn -o test` + `npm run build`；P3.4/P3.5 需逐页渲染比对 |
| 4 | 每项在 **1600×900 与 1366×768** 两档实测；P4.1 需回归 `el-table`/`el-dialog`/`el-select`/`el-select-v2` 自动解析 |
| 5 | 逐项复现原缺陷场景 |
| **§七 L1~L6** | 每批 `mvn -o test` + `npm run build`；L1/L2/L3 需确认无残留引用（改动非纯注释，`_onlycomments.py` 不适用）；L4 需扣分聚合在 35355 条下 `truncated=false`；L5/L6 需提交后离开再回来能看到进度、终态刷新正确 |
| **§七 L7** | 依赖 R1/R2 完成后；组员看不到他人日志、下拉框无他人操作类型 |
| **§九 ①~④** | `mvn -o test` 100 例绿 + `npm run build`；舌诊/脉诊各值抽取齐全、`天`/`失` 类截断项不残留；**质控分：完整性/格式/重复/一致性四维与改前一致，标准化维度应上升**（见 9.6 手工回归）；ES 停掉仍 503 + code=1010 |
| **§八** | 待定，未拍板 —— **不设闸门**（未批准前不执行） |

**统一命令**（项目硬约束）：
- 后端：`mvn -o test`（`D:\devSoft\apache-maven-3.9.16\bin\mvn.cmd`，不在 PATH；或 wrapper `C:\Users\xxinj\.m2\wrapper\dists\apache-maven-3.9.15\9925cc1d\bin\mvn.cmd`；**不带 `clean`**）。`JAVA_HOME` = **`F:\jdk24`**（`start-all.bat` 已定，闭合多课题组「待用户拍板」#4）
- 前端：`npm run build`（`frontend/`）
- 推送：需 Steam++

---

## 六、执行顺序总览

```
阶段1 正确性缺陷（6 条，均未完成）
   ↓
┌──────────────────────────────────────────┐
│ §七 专项 L1→L3（日志减负，纯删减）        │ ← 零依赖，最先做，缩小面
│ §七 专项 L4→L6（上限 40000 + 异步化）     │ ← 零依赖
│ §九 实体抽取修复 ①~④（修 苔/部位/回补）  │ ← 零依赖；⑤ 词典扩充已完成
└──────────────────────────────────────────┘
   ↓
§八 C1 调大 Hikari 连接池（10 → 20~50）    ← **R2 的前置条件**，见 §八 状态
   ↓
阶段2 多课题组改造 R1→R6（结构大改）
   └─→ §七 专项 L7（日志 group_id 三级可见，依赖 R1/R2）
   ↓
阶段3 可读性重构 R1→R6（落在最终逻辑上）
   ↓
阶段4 前端性能与视觉 P0→P3
   ↓
阶段5 加固与收尾

旁挂（不排入主线）：§八 C2~C5 提升并发量其余项 —— 待定，未拍板
```

**为什么不先做可读性/前端再做大改**：可读性 R4（长函数拆分）、R5（字段合一）、R6（结构调整）与多课题组 R3/R4/R5 **改同一批方法与文件**。先重构再改造 = 同一段代码改两遍；先改造再重构 = 只改一遍。

---

## 七、日志与规模上限专项计划（L1~L7）

> **来源**：`docs/未命名.md`（用户目标：日志体量 / #35「一份真实数据集 35355 份，项目相关上限需要调整（检查）」/ #1「批量解析处理条数上限太少」）+ 2026-09-28 用户当面对齐的决策。
> **性质**：设计**已定稿，无待用户拍板项**（原多课题组「待拍板」#1、#4 已在本专项闭合）。
> **取代关系**：本专项**取代**下列既有条目 —— 原 **P5.12**「重算上限」（已删，改判为「异步化」）、原 **P5.1**「重算全局单锁」（已删，改任务表防重）、**P5.2** 中「扣分聚合截断」一条、**P5.4** 中「`OperationLogger` 实例锁」一条、阶段2 **R4** 的「删 `purge`」与「日志 3 读接口加 `operator`」两条（后者改为按 `group_id` 三档）。
> **相关**：并发量提升（Hikari 池 / NLP 多进程 / 多实例）见 **§八（待定）**；其中 C2（长操作移出请求线程）与本专项 L5 重合。

### 7.1 决策记录（2026-09-28 全部已定）

| # | 决策 | 取值 |
|---|---|---|
| 1 | 数据集目标 | **40000**（实测样本：40MB / 35355 条 ≈ **1.13 KB/条** → 40000 条 ≈ 45MB） |
| 2 | 批量重算 | **异步化**（新表 `qc_task`，照 `nlp_task` 范式） |
| 3 | 日志可见性 | **按 `group_id` 过滤，3 档**：管理员全部 / 组长本组 / 组员自己 —— **取代**《多课题组改造计划》§3.2 的「组长也仅自己」 |
| 4 | 日志存储 | **DB 唯一**（`operation_log` 表），删文件写入 |
| 5 | IP | **不记**（PIPL 最小必要；消费方仅 2 处） |
| 6 | 日志删除功能 | **不要**（`purge` 全套删除；**接受表无界增长**，为已知未闭环项） |
| 7 | 日志 3 个月窗口 | 不做 |
| 8 | 上限：`qc.batch.max-records` / `MAX_SCAN_RECORDS` / NLP 默认 | 均 **40000** |
| 9 | 上限：导入 `max-file-size`(50MB) / `MAX_FILES`(20) | **不动**（40MB 单文件已能过，40000≈45MB 仍过） |
| 10 | 异步并发 | **1**（重算 DB 写密集，不与在线查询抢库） |
| 11 | `JAVA_HOME` | **JDK 24**（`start-all.bat` 已定，闭合原 #4） |

### 7.2 前提与排序（不可颠倒）

```
L1 去 IP ──────┐
L2 删文件日志 ─┼── 零依赖、纯删减，可立即做，独立回滚
L3 删 purge ───┘
L4 扣分聚合收窄列 + 上限 40000 ─┐
L5 异步化后端（qc_task）─────────┼── 独立
L6 异步化前端 + 上限值 ──────────┘  依赖 L5
R1/R2 多课题组改造 ─────→ L7 日志 group_id 三级可见（依赖 R1/R2）
```

> **已核实的前提**：仓库现有 `research_groups` / `group_members` / `currentGroupId` **0 处** —— 多课题组改造未开工，故 **L7 必须排在 R1/R2 之后**；L1~L6 不受影响。
>
> ⚠️ **DDL 落库方式（易漏）**：`database-init.sql` **不被 Spring 自动执行**（`application.yml` 无 `spring.sql.init`），且只有 `CREATE TABLE IF NOT EXISTS` —— 对**存量库**的改列 / 删列 / 加索引**都不会生效**。故：**新表**（`qc_task`）写进 `database-init.sql` 即可（存量库重跑也能建）；**改列 / 删列 / 索引**必须按《多课题组改造计划》§5.2 的约定**另附 `ALTER` 迁移脚本**。本专项涉及：L1 删 `ip`、L7 的索引（`group_id` **列**已由 R1 加，见 7.9）。

### 7.3 L1 去 IP

| 文件 | 改动 |
|---|---|
| `database-init.sql` | `operation_log` 建表语句删 `ip VARCHAR(45)` 列 |
| `OperationLog.java` | 删 `ip` 字段(L33)、类注释(L11) |
| `OperationLogger.java` | 删 `MAX_IP`(L44)、`log()` 的 `currentIp()`(L62)/传参(L69)、`insertDb` 的 `ip` 形参(L108) 与 `setIp`(L119)、类注释(L25) |
| `RequestUtils.java` | 删 `currentIp()`(L36-55，删后**全仓零引用**) |
| `LogServiceImpl.java` | CSV 表头 `IP`(L186)、`csv(l.getIp())`(L195) |
| `AuditLog.vue` | 删 IP 列(L62) |
| `openapi` | `OperationLogVO.ip`(L518-519) 删 |

> **存量库**：需补 `ALTER TABLE operation_log DROP COLUMN ip;`。可**留列不删** —— 代码不再写、`OperationLog` 实体已无该字段，留下的列恒 NULL、无害；为干净建议删。
>
> `AiServiceImpl:395` 仅注释提到「不含 IP」，无需改；`Records.vue` 的 `.ip-hd`/`.ip-sub` 是导入进度框，与 IP 无关，不动。

### 7.4 L2 删文件日志

| 文件 | 改动 |
|---|---|
| `OperationLogger.java` | 删 `logFile`(L48-49)、`writeFile`(L87-104)、`buildContent`(L72-85，仅 `writeFile` 用)、`TS`(L37)、imports(L10-14)、`log()` 中 `writeFile` 调用(L67-68)、类注释(L18-31 双写描述) |
| `application.yml` | 删 `log.operation-file`(L104)；**`logging:`(L106) 不动**（Spring 应用日志，另一回事） |
| `OperationLog.java`(L11) / `LogController.java`(L23) | 注释「文件 + 入库双写」→「审计页唯一数据源」 |

> 代价（用户已同意）：DB 写失败时无兜底留痕。`logs/` 目录此后后端**零引用**（`.gitignore` 的 `logs/` 保留无害）。

### 7.5 L3 删日志删除功能（purge）

| 文件 | 改动 |
|---|---|
| `LogController.java` | 删 import(L6) + `purge` 方法(L90-101) |
| `PurgeLogDTO.java` | **整个文件删除** |
| `ILogService.java` | 删 `purgeBefore`(L55)；**保留** `listRecentByOperator`(L64，AI 助手 `AiServiceImpl:398` 在用) |
| `LogServiceImpl.java` | 删 `archiveDir`(L44-45)、`purgeBefore`(L109-146)、`writeArchive`(L161-181) |
| `api/log.js` | 删 `purgeLogs`(L18-19) |
| `AuditLog.vue` | 删清理按钮(L21-22)、弹窗(L26-47)、import(L94)、状态与 handler(L189-210)、CSS(L241-247)、顶注释(L2) |
| `openapi` | 删 `POST /api/logs/purge`(L1609) |

### 7.6 L4 规模上限定稿 + 扣分聚合收窄

| 位置 | 现值 → 定稿 | 说明 |
|---|---|---|
| `application.yml:59` `qc.batch.max-records` | 30000 → **40000** | 配合 L5；退化为「提交时范围校验」 |
| `QcServiceImpl:79` `MAX_SCAN_RECORDS` | 3000 → **40000** | **必须先收窄 SELECT**，见下 |
| `NlpExtract.vue:705` `batchLimit` | 1000 → **40000** | 对应目标 #1；服务端本就无上限（UI `:max="100000"` L302） |
| `SearchDTO @Max(200)` | **不动** | 每页条数，与数据集规模无关 |

**L4 的关键（`MAX_SCAN_RECORDS` 的真问题）**：`/api/qc/deduction-stats` 在 `Qc.vue` 的挂载钩子（`onMounted` L731 → `loadDedStats()` L734）**每次进页都跑**，且 `QcServiceImpl:440` 的分页扫描（`deductionStats` L429）**默认拉全部列**（含 `present_illness`/`chief_complaint` 等 TEXT 大字段）。定稿做法（**两段式**）：
1. **主扫描（快路径）**：`wrapper.select("id","grade","qc_results")` —— 只取 3 列；扣分数据在 `records.qc_results`(JSON)，`scoreOf` 优先读它（L497-501）
2. **回退子集（慢路径，应极少）**：`qc_results` 为空的记录，按 ID 批量回查**整行**再走「现算」。⚠️ **不可只选 3 列就直接回退** —— `QcScorer` 的回退需 `structured_data` + 19 个原始列（`rawValue` L634-652），否则未评分记录被误判为「全缺失」。重算后（L5）几乎命中不到回退
3. 上限 3000 → **40000**
4. 仍慢再加 **60s Redis 缓存**（该接口每次进 QC 页都跑，缓存收益大；视实测再上，非必需）

> ⚠️ **两段式的边界（须知道）**：**导入后未重算**时 `qc_results` 全为空 → 回退子集 = 全库，两段式**退化为「全量回查」**，收益归零（与现状同慢）。**重算后**才几乎命中不到回退。故首屏若慢：要么先跑一次重算（L5），要么上 60s 缓存。

> **导入内存风险（可选加固，不阻塞）**：`RecordServiceImpl` 用 POI `WorkbookFactory`(L215，.xlsx=XSSF **全量进内存**) + `parsedRows` 攒满再 `saveBatch`(L305)，40MB 文件峰值约 400–700MB 堆；`start-all.bat` 未设 `-Xmx`（默认堆=物理内存 1/4）。建议确认堆 ≥1GB，或改「边解析边分批 `saveBatch`(每 1000 条)」。**上限数值不动**。

### 7.7 L5 批量重算异步化（后端）

| 层 | 内容 |
|---|---|
| DB | 新表 `qc_task`：`id` / `status(QUEUED/RUNNING/COMPLETED/CANCELLED/INTERRUPTED/FAILED)` / `total` / `done` / `success` / `failed` / `qualified` / `pending_review` / `invalid` / `current_label` / `filters_json` / `created_by` / **`role`（提交时角色快照，供 worker 重建 `RecordFilter`）** / `failure_list` / `failure_truncated` / `create_time` / `started_at` / `finished_at` + `idx_status` / `idx_create_time` |
| PO/Mapper | 新增 `QcTask.java`、`QcTaskMapper.java` |
| ⚠️ **线程绑定（最关键）** | `RequestUtils.currentRole()/currentUsername()` 读 `RequestContextHolder`（**线程绑定**），后台线程取到的是 **`"unknown"`（不是 null）** → `RecordFilter.build("unknown", …)` 的 `domainGrade` 返回 null → **退化成「不过滤 = 全库」**（`RequestUtils:12/20/31`、`RecordFilter:79/181`）。**必须在提交线程捕获 `currentRole()`+`currentUsername()` 写入 `qc_task`，worker 用捕获值**；结尾 `operationLogger.log` 的操作人也用捕获值 —— 但 **`OperationLogger.log(action,target,detail)` 现从 `RequestUtils` 内部取操作人，故须新增 `log(action,target,detail,operator,role)` 重载**（否则异步任务的操作人记成 `"unknown"`）。**NLP 模板为此硬编码 `RecordFilter.ROLE_ADMIN`（`NlpBatchServiceImpl:193/363`，即多课题组「B 类 4 处」之一）—— 不可照抄该做法** |
| Service | 新增 `IQcBatchService` + `QcBatchServiceImpl`（照 `NlpBatchServiceImpl`：`@PostConstruct` 重启把 RUNNING/QUEUED 标 `INTERRUPTED`；`@PreDestroy` 补标 + `awaitTermination`；固定线程池**并发 1**；`BlockingQueue` + 取消位；分页 1000 逐条 → **复用 `QcServiceImpl.processOne`(L268)**（内部已含 `QcScorer` + 回写 + `upsertReviewTask` + 计数）；批内 `seenHash` 去重保留；周期性写 `qc_task` 进度）。⚠️ 复用前提：`processOne` 现为 **`private`**，须改为包级/`public`（注意 `TransactionAnnotationTest` 的 javadoc 提到它，改可见性后需同步该注释） |
| 同步实现处置 | **移除 `IQcService.scoreBatch` 的同步实现**（分页循环迁入 `QcBatchServiceImpl`；`processOne` 留在 `QcServiceImpl`）。**消费者仅 `QcController:92-93` 与 `IQcService:55`**（已核实：无其它引用、无测试） |
| 配置 | 新增 `qc.batch.concurrency: 1` |
| 防重 | **去掉** Redis 全局锁，改判「表中是否已有 QUEUED/RUNNING」（原 P5.1，已删）。**清理面**：`redis` 字段 + `StringRedisTemplate`/`Duration` import + `RELEASE_IF_OWNER`(L72) + `BATCH_LOCK_KEY`(L63) + `LOCK_TTL_SECONDS`(L65) + `LOCAL_LOCK_TOKEN`(L67) + `localLock`(L69) + `acquireLock`(L524) + `releaseLock`(L545) + 类 Javadoc L54「Redis SETNX 防重」 |
| 接口 | `POST /api/qc/score/batch` 改为**提交返回 taskId**（**破坏性变更**）；新增 `GET /api/qc/score/batch`（任务列表）、`GET /api/qc/score/batch/{id}`（进度+分级汇总）、`POST /api/qc/score/batch/{id}/cancel`（取消）。**`QcBatchResultVO` 保留**：进度接口仍复用同一套分级汇总字段 |
| 测试 | 新增 `QcBatchServiceImplTest`（照 `NlpBatchServiceImplTest`）；**已核实**存量 0 处 `scoreBatch` 测试，不破坏 |
| openapi | `POST /api/qc/score/batch`(L2537) 重写为异步 4 接口 |

### 7.8 L6 异步化前端 + 上限值

| 文件 | 改动 |
|---|---|
| `api/qc.js` | 加 4 个封装（提交 / 列表 / 进度 / 取消） |
| `Qc.vue` | `handleRecompute`(L670) 由「等结果」改为「提交拿 taskId → 2s 轮询进度条(`done/total`) → 终态提示分级汇总 → 刷新 `loadPrecheck`/`loadDedStats`」；轮询参照 `NlpExtract.vue` 范式 |
| 上限值 | `NlpExtract.vue:705` 默认 1000 → 40000（若 L4 未同时做） |

### 7.9 L7 日志 `group_id` 三级可见（依赖 R1/R2）

| 层 | 改动 |
|---|---|
| DB（列已由 R1 加） | `group_id` 列**已由《多课题组改造计划》§5.2 的 `ALTER` 加好**（L261，同一列，**L7 勿重复加**）。L7 只加索引：删 `idx_operator` → 建 `idx_group_time(group_id,log_time)`、`idx_operator_time(operator,log_time)`；保留 `idx_log_time`/`idx_action`。`idx_operator_time` 同时覆盖 AI 助手的 `listRecentByOperator`（`WHERE operator=? ORDER BY log_time DESC`），故可安全删单列 `idx_operator` |
| 写入 | `OperationLogger.insertDb` 取 `RequestUtils.currentGroupId()`（**R2 提供**）填入 |
| 读取 | `LogServiceImpl.buildWrapper`(L207) 追加 scope：管理员无条件 / owner `group_id=myGroup` / member `group_id=myGroup AND operator=me` / 无组 `operator=me`（**一次覆盖 `page`/`listForExport`/`exportCsv`**） |
| 特殊 | `OperationLogMapper.selectDistinctActions` 是**无参硬编码 SQL**，必须单独改（否则组员下拉框仍显示他人操作类型） |
| 权限 | 3 读接口（`/api/logs` L1506、`/actions` L1560、`/export` L1587）「仅管理员」→「登录即可」 |
| 前端 | `AuditLog.vue` 视需要加「所属组」列 |
| openapi | `OperationLogVO.groupId` 新增；3 接口权限标签 |

> **与 L5 交叉**：异步重算任务结尾的那条审计日志（7.7）运行在**后台线程**，`RequestUtils.currentGroupId()` 同样拿不到 → 该条日志的 `group_id` 也必须用**提交时捕获值**（连同 7.7 新增的 `log(...)` 重载一起传）。
>
> **快照式的后果（须知悉）**：行带 `group_id` 快照、过滤按「我当前的组」—— 组长留在本组可看本组全部历史；组长**换组**后看不到原组日志；组长**降为组员**后变为只看自己。这是「数据主权在组」的自然结果。

### 7.10 批次总表

| 批 | 内容 | 依赖 | 可独立回滚 |
|---|---|---|---|
| **L1** | 7.3 去 IP | — | ✅ |
| **L2** | 7.4 删文件日志 | — | ✅ |
| **L3** | 7.5 删 purge | — | ✅ |
| **L4** | 7.6 扣分聚合收窄列 + 抬 40000 | — | ✅ |
| **L5** | 7.7 后端异步化（表 + 服务 + 4 接口 + openapi） | — | ✅ |
| **L6** | 7.8 前端轮询 + 上限值 | L5 | ✅ |
| **L7** | 7.9 日志 `group_id` 三级可见 | **R1/R2** | ✅ |

### 7.11 必须同步的既有文档

| 文档 | 改动 |
|---|---|
| `多课题组改造计划.md` | §3.2（日志可见改 3 档）、§6.6（加 `group_id` 过滤）、§6.5(L453 删 purge 说明)、待拍板 **#1**（已解）、**#5**（改「接受无界增长、不做清理」）、§十二 风险表两条 |
| `AI执行计划.md` | 原 P5.12 / P5.1（均已删，内容并入 §七）；P5.2 / P5.4 / R4 保留但注明被 §七 取代（见 §七 开头「取代关系」） |
| `开发指南与待办.md` | 日志双写 / 绝对路径条目、开发基线「批量重算同步 + 3 万」→ 异步 + 40000、数据规模；**另：L123「当前 95 例全绿」已过期，实际 100 例**；**L288「词典查询 100 条静默截断」已由分页修复** |
| `功能设计文档.md` | L118/128/132/560/1055（`logs/operation.log`）、L1098（purge） |
| `项目设计文档.md` | L157/214/249/330/434/830/849 |
| `前端专项审查报告.md` | L232（CSV 清理 + 归档 → 删） |
| `openapi` | 7.3 / 7.5 / 7.7 / 7.9 各处（**BOM+LF**） |

### 7.12 验收（每批）

`mvn -o test` 全绿（预估 **≥97 例**）· `npm run build` 通过 · openapi 按 **BOM+LF** 用 `python -X utf8` 字节补丁 · **提交排除** `docs/未命名.md`、`RecordServiceImpl.java`、`RecordServiceImplTest.java`、`start-all.bat`（非本专项改动）。

**手工回归**：卸载 IP 后审计页正常 / 导入 40MB×1 文件成功且可查 / 全库批量重算提交后离开再回来能看到进度 / 扣分聚合统计到 35355（`truncated=false`）/ L7 后组员看不到他人日志且下拉框无他人操作类型。

---

## 八、提升并发量（C1 已是阶段 2 的前置条件）

> **状态**：⚠️ **C1 不再是「可选优化」，而是阶段 2（R2）的前置条件** —— 2026-09-28 复核：R2 要给**每个请求**加一次 DB 往返（`JwtInterceptor` 每请求查组），而当前连接池是 Hikari 默认 **10**（`application.yml` 无 `hikari` 配置）。**不做 C1 就做 R2，并发能力会不升反降。** 其余各条仍为待定，未获批不执行。
> 现状上限（配置层推导，**未压测**）：Tomcat 200 线程 · **Hikari 默认 10 连接（硬瓶颈）** · Redis 共享连接（非瓶颈，但重算锁单任务）· **Python NLP 单进程**（实际并发 ≈ CPU 核数）· 单实例。

| # | 动作 | 改动 | 效果 |
|---|---|---|---|
| **C1** | 调大 Hikari 连接池 | `spring.datasource.hikari.maximum-pool-size: 10 → 20~50` | 直接抬并发上限（唯一硬瓶颈） |
| **C2** | 长操作移出请求线程 | §七 L5 重算异步化（已定）；**导入也改异步 / 分批落库** | 不长时间占连接，在线请求不被挤 |
| **C3** | NLP 多进程 + 限流 | `uvicorn --workers=N`（按核数）+ `Semaphore` 限并发 | 抬 NLP 并发（**每进程各加载一份模型，内存 ×N**） |
| **C4** | 「每请求查组」加短缓存 | R2 的 `JwtInterceptor` 查组成员 → Redis / 本地缓存（秒级 TTL） | 抵消 R2 新增的每请求 DB 往返 |
| **C5** | 多实例（要更高才做） | 前置：重算 / 批解析的**进程内防重**改 DB / Redis 分布式防重 | 水平扩容 |

**不需要动**：Tomcat 线程（200 够）、Redis（共享连接非瓶颈）。

> **与既有计划的关系**：C2 中「重算异步化」已在 §七 L5，**「导入异步化」尚未立项**；C4 依赖 R2 先落地。

---

## 九、实体标准化改造 · 抽取修复（2026-09-28 定稿）

> **来源**：2026-09-28「数据集还有哪些实体需要标准化」调研（数据集 = `docs/电子病历精简脱敏数据_500行.xlsx`，500 条）＋与另一 AI（opencode）**5 轮交叉复核**，分歧已归零。
> **性质**：第 ⑤ 步（词典扩充）**已完成**；①~④ 仍是**待批准项**，未经用户明确指示不执行。
> **一句话**：**不改实体，改抽取**。
> **取代关系**：**取代**同日形成的「加 7 类实体 ＋ 7 份词典 ＋ 6 维要素索引 ＋ 重算评分 ＋ 全量重建」方案（否决理由见 9.1）。
> **与既有计划的关系**：第 4 步落在批量解析链路（`NlpBatchServiceImpl.processOne`）内，与 **§七 L5**（批量重算异步化）**无冲突**、与 **§八 C2**（长操作移出请求线程）**不重叠**。

### 9.1 为什么不动 `EntityTypes`（决策记录）

| 备选方案 | 否决理由 |
|---|---|
| 加 7 类（舌质／苔色／苔质／面色／表情／脉象要素／西医诊断，`dict=true`） | `EntityTypes` javadoc 自陈两条约束：「固定 9 类（对齐 NLP 可抽取的实体）」「不落盘、不做用户自定义」。这 7 维**不是 NLP 可抽取的实体**，是本数据集的取值维度 → 换数据集要改码 ＋ 重编译 ＋ 重建索引 |
| 加 1 个 `term_element` 索引（维度外置可配置） | 机制上可行，但**要素词典撞上判定规则**：`EsTermNormalizer.judge` 二级·包含是「多命中**取最短**标准词」→ 输入 `脉沉细无力`、词典收 `沉`／`细`／`无力` → 取最短 = `沉`，**长表述被压成单字，比 0% 更糟** |
| 新增 `tongues.json` ／ `pulses.json` | 同上「取最短」；且 `dict=false` 时是**死文件**（`DataInitializationListener:87` 只遍历 `TermTypes.ALL = dictKeys()`；`DictionaryController` 用 `TermTypes.ALL.contains(type)` 校验会直接 400）→ **没有「加文件但不置 dict」这个中间选项** |
| 改 `QcRuleSet:160` 要素来源（`dictKeys()` → 显式 6 项） | 舌象 346 ＋ 脉象 346 实体**无 `normLevel`**（`QcScorer:88` 按未归一计）→ 每条病历立刻扣满 5 分；而「脉象词典不该加」又把出口堵死 → 两件事**互锁** |
| 内存词表 ／ trie 兜底 | 违背 **2026-09-23 已定决策**：ES 是归一唯一权威、无内存兜底（`EsTermNormalizer` javadoc「为什么没有内存兜底」） |
| percolate ／ 原始列文本索引 | 实现成本高、与「能复用就复用」冲突；且 ES 三级判定**已经就是**词表匹配要做的事，不需另造 |

**结论**：`EntityTypes` **一个字都不改**（9 类仍 9 类，5 类有词典仍 5 类）；ES 索引数不变；`QcRuleSet` 计分行为不变。

### 9.2 七步（可执行版）

> 编号说明：原为 ①②③④⑤⑥⑦ 共 7 步，其中 ⑤（词典扩充）**已提前完成**、⑦（前端展示）经评估**不需要做**（要素不进评分、无展示诉求），故实际待执行的是 **①~④ 六步**，下面按原编号保留以免与既有引用脱节。

| 步 | 文件 | 改法 | 验收 |
|---|---|---|---|
| **①** | `python-nlp/main.py:40` | `RULE_TONGUE` 字符类**加 `苔`**：`r"舌[^…]{1,8}"` → `r"[舌苔][^，。、；;、\s]{1,8}"` | `舌质淡红，苔薄白` → `['舌质淡红','苔薄白']`（现只出前者） |
| **①b** | `python-nlp/main.py:41` | `RULE_PULSE` 尾部加**脉位锚**：`(?:[，,](?:左\|右)[^，。、；;、\s]{1,8})?` | `脉细数，左尺无力` → 一条含部位（现丢「左尺无力」，**100/500** 受影响） |
| **②** | `QcRuleSet.java:160` | **只加注释**：登记「此处用 `dictKeys()`（5 类，含方剂、不含舌象／脉象），与 `开发指南与待办.md:29` 的『6 项（含舌象／脉象、不含方剂）』**口径相反**」。**不改行为** | 无（纯注释） |
| **③** | `EsTermNormalizer.java` | 新增 `scan(type, text)` ≈ 20 行：复用 `recall` ＋ **contains 谓词**，**收集全部命中、不取最短** | 单测：`scan("herb", "天麻10g，菊花10g")` 返回 2 条，**不**退化成 1 条最短 |
| **④** | `NlpBatchServiceImpl.processOne`（≈ L456，`Record r` 在作用域内） | `entityNormalizer.normalize(vo)` 之后加**回补**：`herb` ← `r.getPrescription()`、`disease` ← `r.getTcmDiagnosis()`（**异常触发**：该字段存在未命中项才做）。**先剔截断项**（判据：归一失败 **且** content 是某 `scan` 命中词的**真子串**；**不用「长度 1」启发式**）→ append → `dedupByTerm` | 中药归一率 93% → 显著上升；`天`／`失` 类截断项不再残留 |
| **⑤** | ✅ **已完成（2026-09-28，超出原计划，详见 9.7）** | `data/dictionaries/*.json` | 原计划「只扩 `diseases.json`」，实际**合并国标词表、一次动了 3 本**；疾病 26%→51.3%、证候 83%→100%、症状 16%→22.5%；`currentVersion()` 变 → 5 索引一次性全量重建 |
| **⑥** | — | 量化验收：**漏召率** ＋ **泛化留出**（见 9.6） | 见 9.6 |

> **①b 为什么锚「左／右」**：中医脉位 = 左右 ＋ 寸关尺，是**标准语义锚点**（非格式锚点）。若照搬「任意逗号续接」，`脉细，舌红` 的「舌红」会被吞进脉象。

### 9.3 明确不做

`EntityTypes` 增类型 ／ 改 `dict` ｜ `pulses.json` ／ `tongues.json` ｜ 要素分解 ｜ `term_element` 索引 ｜ percolate ｜ 内存 trie ｜ `QcRuleSet:160` 行为改动 ｜ `code` / ICD 编码 ｜ 闻诊 ／ 问诊 ／ 处方用法 ／ 针灸贴敷（数据集无列）｜ 方剂模块（用户已暂搁）。

### 9.4 本次不解决，但用户可自助解决（**词表是数据，不是代码**）

下列缺口**不改任何代码**，平台已有入口即可提升 —— `DictionaryController` 的 `POST /import`（L47）＋ `POST /rollback`（L125）；前端 `Dictionary.vue` 已有「术语库导入」上传区（L73）与「版本回滚」面板（L190），且页面**已在提示**「当前为演示词典…**导入正式词典后可提升归一命中率**」（L15）。

| 缺口 | 现状 | 自助解决方式 |
|---|---|---|
| 症状未归一 | **22.5%**（扩充后；未归一仍是最大类，约占未归一实体 60%+） | 继续导入标准症状词表（《中医诊断学》症状术语）；扩充后 20→72 条已缓解一部分 |
| 舌象 ／ 脉象未归一 | 均 **0%**（仍无词典） | 需标准舌脉术语表；注意须收**完整短语**（见 9.5 #4） |
| 疾病未命中里的西医病名 | 扩充后大幅下降（`腰椎间盘突出症` 等已成为精确命中），仍有部分未覆盖（如 `慢性胃炎`／`2型糖尿病`） | 待 ICD 词表到位；**升格为归一词典前须先补 ES mapping 的 `code` 字段 ＋ `toEntry` 映射**，否则 `normCode` 恒空 |

> **注**：症状归一率偏低的原因**不是「收益低」** —— 未命中的 110 种里 `神疲乏力`／`食少纳呆`／`腹胀便溏`／`尿量减少` 都是**标准中医症状术语**。真实原因是**缺数据源 ＋ 规模大**（覆盖它需数千条词表，扩充到 72 条后仍远远不够）。

### 9.5 残留风险（如实记录，不掩盖）

| # | 风险 | 说明 |
|---|---|---|
| 1 | **①／①b 本身就是格式规则** | 「以 `舌`／`苔`／`脉` 起头 ＋ 标点边界」与总原则「对术语敏感、对格式不敏感」**冲突**；因要素词表被砍，它们是舌象／脉象的**唯一路径** → 如实记为**「没有词表时的次优选择」，非终态** |
| 2 | **残留格式假设** | ①／①b 要求值以 `舌`／`苔`／`脉` 起头。换数据集若写 `左关尤甚`（无 `脉` 字）或苔色单独成列 → **仍会漏** |
| 3 | **①b 产出可能含逗号** | `脉细数，左尺无力` 会并成一条实体。**不影响计分**，但将来做脉象分解要重拆；且**含逗号词条不适合进脉象词典**（包含匹配易误伤）→ 又一条「不加 `pulses.json`」的理由 |
| 4 | **终态是什么** | `judge` 二级「取最短」只对**要素级**词典有害；若 `tongues.json`／`pulses.json` 收**完整短语**（`舌质淡红`／`脉沉细无力`）则走**精确命中**、不丢信息 —— 机制上可行，但需要一份**标准舌脉术语表**（与症状同一数据源问题） |

### 9.6 验收

**基线（500 条演示数据，`structured_data` 逐实体统计）**：

| 实体 | 实体数 | 已归一（旧词典） | 归一率（旧） | 归一率（**新词典，2026-09-28 实测**） |
|---|---|---|---|---|
| 中药 herbs | 3522 | 3282 | **93%** | 93%（词典未动） |
| 证候 patternList | 683 | 569 | **83%** | **100%**（已合并国标） |
| 疾病 diseases | 515 | 136 | **26%** | **51.3%**（已合并国标） |
| 症状 symptoms | 2963 | 486 | **16%** | **22.5%**（已合并国标） |
| 舌象 tongueList | 346 | 0 | **0%** | 0%（仍无词典） |
| 脉象 pulseList | 346 | 0 | **0%** | 0%（仍无词典） |
| 病因 causeList | 237 | 0 | **0%** | 0%（不在本次范围） |

> ⚠️ **旧词典基线仅供对比**。词典已合并（9.7），**当前基线是右列**，拿左列当起点会误判「没提升」。

**验收项**：① **漏召率**（`scan` 自身指标：词典条数 → 漏召率曲线，用于确认 `scan` 不漏召；**注意不是抽取覆盖率**）② **泛化留出**：抽 50 条**不参与调参**的病历验证，防过拟合 ③ 输出「**未归一实体 TOP-N 清单**」（= 后续词表补齐行动清单）④ `mvn.cmd -o test` 全绿（**100 项**）＋ `node node_modules/vite/bin/vite.js build` 通过。

> 「病名 136/500 → 提升」这条**已由词典扩充达成**（9.7），不再作为本节 ①~④ 的验收目标；①~④ 只针对**抽取层**（截断回补 / 病名回补）。

**手工回归**：导入 500 条 → 中药／疾病归一率上升、无「天」／「失」类截断实体残留 → 质控分变化符合预期（**完整性／格式／重复／一致性四个维度必须与改前完全一致；标准化维度应当上升** —— 第 ④ 步回补的实体带 `normLevel`，`QcScorer:88-89` 的「未归一计数」会随之减少、扣分下降，**这是改进不是回归**，不要按「分数应完全不变」去判）→ ES 停掉仍返回 **503 ＋ code=1010**（未被本次改动破坏）。

### 9.7 已完成的词典扩充（2026-09-28，非本节七步）

> 原七步的第 ⑤ 步只规划了「扩 `diseases.json`」。实际执行时**一次合并了 3 本**，此处补记事实，避免后来人重复做、或误以为还没做。

| 词典 | 原条数 | 现条数 | 归一率变化 |
|---|---|---|---|
| `diseases.json` | 10 | **1357** | 26% → **51.3%** |
| `patterns.json` | 30 | **2080** | 83% → **100%** |
| `symptoms.json` | 20 | **72** | 16% → **22.5%** |
| `herbs.json` / `formulas.json` | 66 / 5 | 未动（无可下载来源） | 93% / — |

**做法**：从国标／行业标准词表（PDF/DOCX）转成 JSON，与原有演示词条**合并**（旧词在前）而非替换 —— 纯替换会**退步**（证候 83%→78.5%、症状 16%→10.1%），因为原 30/20 条是按本数据集措辞手挑的。转换工具：`tools/convert-standard-pdf.py`（新增 DOCX 支持与 `--section`，默认 dry-run、`--write` 才落盘）、`tools/eval-dictionary-impact.py`（离线复刻三级判定算命中率，不碰 ES／库）。原文件备份在 `%TEMP%/tcm-term/orig/`，5 本词典均在 git 内可还原。

**⚠️ 类目词陷阱（已处理，供后来人参考）**：国标里类目词会把成员词当别名（`消渴类病` 的别名含 `消渴`、`痹证类病` 含 `痹证`），而这些成员词在国标里**不是独立标准词**。一级精确是**按列表顺序取首个命中**，撞车时结果取决于词条顺序。转换时已丢弃撞车别名并在合并后再消一次；类目词本身保留（是国标正式条目）。

**两个遗留问题**：

| # | 问题 | 影响 / 处理 |
|---|---|---|
| 1 | **`diseases.json` 含 5 个 1 字标准词**（`癣`/`疖`/`痈`/`发`/`疽`） | `EsTermNormalizer.judge` 二级是「多命中**取最短**」→ 任何含这些字的疾病词会被压成该单字。**本数据集 10 个病名不含这些字，当前无影响**；换数据集前应剔除 1 字条目 |
| 2 | **ES 的 `_meta.version` 与后端算的不一致** | 索引由转换工具**直接对 ES 重建**（不经后端），写入的版本号与 `DictionaryFileServiceImpl.currentVersion()`（MD5 取前 12 位）不同 → **下次重启会再触发一次全量重建**（同内容重建，无害但耗时）。**重启一次即可对齐** |

> 术语查询分页（`searchTerms` 支持 `page`/`size`、`Dictionary.vue` 加 `el-pagination`、`Qc.vue` 下拉改 `el-select-v2`）**已于同日完成**（原 P1.4，已从阶段 1 删除）。
