# AI 执行计划

> ## ⛔ 本文件是**待批准的清单**，不是执行指令
>
> **未经用户明确指示，不得执行本文件的任何一项。**
> 用户说「改 / 修 / 执行 / 做」并**点名范围**时，才动手，且只做点名的那部分。
> 其余时间本文件只作参考；发现问题**写进《开发指南与待办》待办**，不擅自修复。
>
> 规则出处：`开发指南与待办.md`「二、开发规范 · 协作铁律（最高优先级）」。

---

> **用途**：把 `多课题组改造计划.md`、`开发指南与待办.md`、`可读性重构审查报告.md`、`前端专项审查报告.md` **四份文档的全部待办项合并、去重、排序**，形成一份可直接执行的任务清单。
> **执行者**：AI（本仓库协作代理）。
> **生成日期**：2026-09-28。
> **状态**：已逐条核实四份源文档的声明与代码现状（行号均实测有效），重叠已合并、冲突已排序、不做项已剔除。

---

## 一、四份源文档的定位

| 文档 | 性质 | 产出什么 | 改造面 |
|---|---|---|---|
| `多课题组改造计划.md` | **功能改造**（决策已锁定） | R1~R6：数据隔离 + 角色重构 | 表结构、认证、RecordFilter、34 门禁、前端 19 处、2 新页面、openapi 51 处 |
| `可读性重构审查报告.md` | 代码质量 | R1~R6 + 单列：注释 / 重复 / 命名 / 长函数 / 结构 | 全库 182 文件（后端 + 前端 + Python） |
| `前端专项审查报告.md` | 前端性能 / 视觉 | P0~P3：加载 / 渲染 / 视觉 / 商业完整性 | `frontend/src` 25 个 SFC + 构建配置 |
| `开发指南与待办.md` | 缺陷台账 | 21 项已确认缺陷（高 3 / 中 10 / 低 8） | 跨前后端 |

四者**互不重复但严重交叉**：多课题组计划改的文件，正是另两份报告要重构的文件。因此**执行顺序决定返工量**。

---

## 二、审核发现：源文档自身需要先纠正的 3 处

| # | 问题 | 位置 | 纠正动作 |
|---|---|---|---|
| **D1** | **事实错误**：称 openapi 是「BOM + **CRLF**」 | `可读性重构审查报告.md:48` | 实测**全文件 0 个 CR 字节、3474 个裸 LF**，`.gitattributes` 有 `* text=auto eol=lf`。执行「`size`→`pageSize`」时若按 CRLF 处理，会让整份文件 diff 炸开。**改为 BOM + LF** |
| **D2** | **重复立项**：§8.3「数据隔离 / 用户管理 / 审计合规」三项已被多课题组计划覆盖 | `前端专项审查报告.md:230-232` | 标注「由《多课题组改造计划》R1/R3/R4 解决，不重复做」——其中数据隔离=grade→课题组、用户管理=`users.status`+组管理页、审计合规=删 `purge` |
| **D3** | **遗漏归属**：§8.1 判 `GET /api/records/import/{taskId}/status` 为「死接口」 | `前端专项审查报告.md:208` | 多课题组 R2 会动 `RecordServiceImpl.taskStore`，两者必须一起处置（见 P5.11） |

> 这 3 处需在开工前写回源文档，否则后续按错信息执行。

---

## 三、重叠合并结论（同一件事只做一次）

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

## 四、分阶段执行计划

**排序原则**：① 不受大改影响的正确性缺陷先修 ② 结构化改造（多课题组）居中 ③ 在将被重写的代码上做局部重构放最后。

---

### 阶段 0 · 文档纠错（开工前，10 分钟）

- **P0.1** 修 D1：`可读性重构审查报告.md:48` 的「BOM + CRLF」→「BOM + LF」
- **P0.2** 修 D2：`前端专项审查报告.md:230-232` 三项标注「由多课题组计划覆盖」
- **P0.3** 修 D3：`前端专项审查报告.md:208` 标注与 P5.11 联动

---

### 阶段 1 · 正确性缺陷（不受大改影响，优先）

| # | 事项 | 位置（已实测） | 改法 | 验收 |
|---|---|---|---|---|
| **P1.1** | 可编辑列表用 index 作 key（**数据错位风险**） | `Qc.vue:104`（`customFormats`）、`Qc.vue:112`（`form.consistency`） | 新增行生成稳定 `uid`（`crypto.randomUUID()`），`:key="c.id"`，提交前剥离 `id` | 删中间行后下拉值不错位；仅改 `Qc.vue` |
| **P1.2** | 5 处列表请求无竞态保护（**脏渲染**） | `Records.vue:312`、`NlpExtract.vue:400`、`Qc.vue:609`、`AuditLog.vue:145`、`Review.vue:286` | 仿 `components/TermInput.vue:32` 的 `seq` 取号：发起时 `const mine = ++seq`，返回后 `if (mine !== seq) return` | 快速连点翻页，列表与页码一致 |
| **P1.3** | python 截断无提示（**尾部实体静默丢失**） | `python-nlp/main.py:143` → `NlpExtractVO.truncated` | 前端 `NlpExtract.vue` 读 `res.data.truncated`，为 true 时横幅提示「文本超长已截断，尾部实体可能丢失」 | 造超长文本，横幅出现 |
| **P1.4** | 词典查询 100 条静默截断 | `DictionaryServiceImpl.searchTerms`（`break` 处） | 响应加 `total` / `truncated`；前端 `Dictionary.vue` 显示「仅显示前 100 条（共 N 条）」 | 词典 >100 条时提示正确 |
| **P1.5** | 前后端校验不一致 5 处 | 就诊次数（`Records.vue` 前端 ≥1 / `CreateRecordDTO` 无 `@Min`）· 密码（前端 ≥6 / `RegisterDTO` 无 `@Size`）· 科室+医生（前端无限制 / `database-init.sql` 50 字）· 性别枚举 3 套 · 批任务 `limit` 无上限 | 后端补 `@Min` / `@Size` / `@Max`；前端补 `maxlength`（对齐 DB 列宽） | 直调 API 传越界值被 400 |
| **P1.6** | 8 处 TDZ 声明顺序 | `Dictionary.vue`（watch 回调引用 6 个后置声明 + `handleConvert`/`doImport` 互引）· `Qc.vue`（`params`）· `Records.vue`（`tableRef`/`selectedIds`）· `Governance.vue`（`filters`） | 被引用的 `const` 声明前移 | 调换声明顺序后不报 `ReferenceError` |
| **P1.7** | AiAssistant 同一问题传两遍 | `components/AiAssistant.vue` `ask()`：先 `push` 再 `historyText()` 读回 | `historyText()` 改为传「不含本条」的历史（如先取 `slice(-6)` 再 push） | 请求体里 `question` 与 `history` 末行不再重复 |
| **P1.8** | `LogicCheckDTO.herbList` 收了不用（**规则失效**） | `QcServiceImpl.checkLogic:149-151` 只放 pattern/treatment/formula | `data.put("herbList", dto.getHerbList())` | `/api/qc/check/logic` 传中药后，「证候→中药」9 条规则可触发 |

---

### 阶段 2 · 多课题组改造（R1~R6）

**完整内容见 `docs/多课题组改造计划.md`**，此处仅列批次与闸门：

| 批 | 内容 | 闸门 |
|---|---|---|
| **R1** | 建 2 表 + 改 4 表 + 存量迁移（500 条→`DEFAULT-2026`；`auditor` 任 owner；**`admin` 不入组**） | 迁移脚本幂等 |
| **R2** | 注册两形态 · JWT 不加组 · `JwtInterceptor` 每请求查组 · `RequestUtils` 补 3 访问器 · 菜单四套 · 停用拒登 | 3 个相关测试类绿 |
| **R3** | `RecordFilter` fail-closed + 13 处换形参 + 4 处角色判断 + **12 类 16 处漏点补校验** + Mapper 3 处 SQL + `review_tasks`/`nlp_task` 打组 | `RecordIsolationTest` |
| **R4** | 34 门禁 → 留 5 删 29 · 删 `POST /api/logs/purge`（后端 + 前端 12 处） | 前后端 build |
| **R5** | 13 接口 + `Groups.vue`/`MyGroup.vue` + `Register.vue` 建组选项 + 无组引导页 | 手工回归：申请→审批→拉人→隔离 |
| **R6** | openapi 51 处（**BOM+LF**）+ 文档回写 + 三方一致性脚本 | 脚本 0 差异 |

> **顺序硬约束**：R3 必须先补组校验，R4 才能删门禁（否则直接越权）。

---

### 阶段 3 · 可读性重构（R1~R6）

> 放在多课题组之后：这样 R4/R5/R6 的重构落在**最终逻辑**上，不返工。

| 批 | 内容 | 风险 |
|---|---|---|
| **P3.1** | 注释风格 §六 10 项（`AiServiceImpl` 补 `@param` · HTML/CSS 注释空格 · 步骤标号混用 · `Qc.vue:654-656` 错位）+ Python §七 4 项 | 低 |
| **P3.2** | 低风险去重 §二：`stripCodeFence`（`AiServiceImpl:611` / `DictionaryServiceImpl:419`）· `isBlank` 6 份收敛 · 日期格式化 · 分页常量 · 重置块 · `fieldOf` · 部门加载 | 低 |
| **P3.3** | 命名统一 §三：路由风格（5 类级 vs 6 方法级）· 缩写变量（`o`/`m`/`sd`/`sr`）· 前端 4 个「重置」名 · `fieldsOf`→`groupFields` · `getNlpBatch`→`getNlpBatchProgress` | 低 |
| **P3.4** | 长函数拆分 §四（只拆 5 个）：`importRecords` · `buildContext` · `normalizeStructuredData` · `handleImport` · `saveRules` | 中（须保请求时序与 `finally`） |
| **P3.5** | 字段定义合一 §二/§四：三份 `FIELDS`（`Records.vue:254`/`Review.vue:214`/`RecordDetailDialog.vue:54`）+ 列渲染组件（`AgeGenderCell`/`VisitTimeCell`） | 中（逐页比对渲染） |
| **P3.6** | 结构 §五：`GovernanceController` 错误体下沉到 `GlobalExceptionHandler` · 数据访问风格统一（直注 Mapper vs `ServiceImpl`） | 中（需回归错误码与权限） |
| **P3.7** | 单列：`/api/logs` 的 `size`→`pageSize`（**先改 openapi，BOM+LF**）· `useAiStore` 改名 | 中高（触契约） |

---

### 阶段 4 · 前端性能与视觉

| # | 事项 | 位置 | 改法 | 收益 |
|---|---|---|---|---|
| **P4.1** | Element Plus 按需引入 | `main.js:3-5` | `unplugin-vue-components` + `unplugin-auto-import`，`ElMessage`/`ElMessageBox` 显式引入 | 首屏 gzip −200 kB |
| **P4.2** | 模板内函数提 computed（4 处） | `AiAssistant.vue:40` `lines()` · `Records.vue:186` `fieldsOf()` · `Qc.vue:88-94` `fmtOf`/`customFormats` · `Review.vue:103/143/149` `originalText()` | 提为 `computed`，模板读缓存结果 | 消除每键重算 |
| **P4.3** | Dashboard 待办条 3 列→2 列 | `Dashboard.vue:310-315` | `repeat(2,1fr)` | 消掉右侧 1/3 空白 |
| **P4.4** | 视觉令牌 | `theme.css` + 约 20 处 `#fff` + 130+ 处颜色字面量 | 加 `--surface`/`--surface-sub`/`--danger-surface`/`--ochre-surface`，批量替换；圆角收 2/4/6 三档 | 拉齐卡片与状态色 |
| **P4.5** | 科室下拉缓存 + `useDepartments()` | `RangeFilter.vue:73-81` + 4 页 | Promise 单例缓存 + 抽 composable | 每页少 1 请求 |
| **P4.6** | Dashboard 三接口并行 | `Dashboard.vue:258` | `governanceStats()` 并入 `:251` 的 `Promise.all` | 管理员省 1 RTT |
| **P4.7** | resize 加 rAF 节流 | `Dashboard.vue:295` | `requestAnimationFrame` 合并；卸载取消帧 | 拖拽不掉帧 |
| **P4.8** | 轮询降频 + 页面可见性 | `NlpExtract.vue:770` | `loadBatchList` 降到 10s；`watch(activeTab)` 停轮询；`visibilitychange` 暂停 | 请求量 −80% |
| **P4.9** | 对比度不达标 | `MainLayout.vue:231`、`Review.vue:786` 的 `#a09c90`（2.6:1）；`Qc.vue:742` 临界 | 换 `--text-sub`（4.64:1） | 达 WCAG AA |
| **P4.10** | 骨架屏用了渐变（**违反「禁渐变」**） | `AiInterpretCard.vue:173/186` | 纯色 + `opacity` 呼吸 | 合规 |
| **P4.11** | 评分瀑布最终得分无突出 | `Qc.vue:923-945` `.end .wf-num` | 提到 16~17px bold | 扫读抓重点 |
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
| **P5.1** | 质控重算全局单锁 + TTL 固定 | `QcServiceImpl:63,65` `BATCH_LOCK_KEY` / `LOCK_TTL_SECONDS=900` | 锁 key 加范围维度（或改用任务表防重）；TTL 按上限估算加大 |
| **P5.2** | 静默截断其余 5 处无提示 | 扣分明细前 20 · 趋势 12 个月 · 复核跳过已删病历 · 失败样本前 50 · 任务列表 `LIMIT 50` | 各补 `truncated` 标记 + 界面提示 |
| **P5.3** | 同一数据传两遍 4 处 | `Review.vue` 列表带全文又回查 · `ReviewServiceImpl:102` 每页内联整份 `structuredData` · `ReviewServiceImpl.parse` · `QcCheckDTO` 可同时带 id 与内联 | 列表不内联全文；或详情按需拉 |
| **P5.4** | 资源与并发加固 | 4 处 static 可变 map（`RecordServiceImpl.HEADER_FIELD`、`EntityTypes.BY_KEY` 等）· `LlmClient` 重建锁 · `OperationLogger` 实例锁 | map 改 unmodifiable；锁粒度评估 |
| **P5.5** | 按钮名与实际行为不符 | `NlpExtract.vue` 「重跑」等 | 入口名只描述动作，前置条件与范围写在按钮旁 |
| **P5.6** | 业务文字需过「脱敏」 | 界面泄漏正则 / 英文 key / 内部目录名处 | 换成业务语言 |
| **P5.7** | AI 助手与弹窗层级 | `AiAssistant.vue` vs 模态弹窗 | 定策略：提层级 或 收进弹窗 |
| **P5.8** | 空态与空列无解释 | `Dictionary.vue:35-36`（`:empty-text` 与 `#empty` 插槽并存，前者是死代码）· 「操作对象」恒空 · 别名列整列「无」 | 留着的空态解释「为什么空、怎么才有内容」；删死代码 |
| **P5.9** | 同一事实两个权威 | 架构性：判定结论口径只存一处 | 全局排查「前端重算业务规则」处 |
| **P5.10** | 只写不读剩余 2 处 | `ScoreResultVO.serious`（仅单测读）· `checkedAt`（无读取方） | 接前端展示 或 删除 |
| **P5.11** | `RecordServiceImpl.taskStore` + 死接口 | `RecordServiceImpl:119/317` · `GET /api/records/import/{taskId}/status` | 导入是同步的，`taskStore` 无真实消费者 → 删接口与状态存储（**与多课题组 R2 联动**） |
| **P5.12** | 3.5 万条性能与上限未实测 | `qc.batch.max-records: 30000` vs 实际 35,355 | 建 `data/seed-30k.py`，实测批量重算 ≤3min |
| **P5.13** | 验证类未做 | 前端真机点击 · NLP 推理实测 · 3.5 万条分页 | 起服务实测（需授权） |

---

## 五、明确不做（三份文档的「不做」合并）

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

---

## 六、每阶段闸门

| 阶段 | 闸门 |
|---|---|
| 0 | 文档已纠正；无代码改动 |
| 1 | `mvn -o test` 绿 + `npm run build` 通过；P1.1/P1.2 手工复现确认修复 |
| 2 | 按各批闸门（见多课题组计划 §十一）；终态 `RecordIsolationTest` + 三方一致性脚本 0 差异 |
| 3 | 每批 `mvn -o test` + `npm run build`；P3.4/P3.5 需逐页渲染比对 |
| 4 | 每项在 **1600×900 与 1366×768** 两档实测；P4.1 需回归 `el-table`/`el-dialog`/`el-select` 自动解析 |
| 5 | 逐项复现原缺陷场景 |

**统一命令**（项目硬约束）：
- 后端：`"D:\devSoft\apache-maven-3.9.16\bin\mvn.cmd" -o test`（或 wrapper `C:\Users\xxinj\.m2\wrapper\dists\apache-maven-3.9.15\9925cc1d\bin\mvn.cmd`；**不带 `clean`**，`JAVA_HOME=F:\jdk24`）
- 前端：`npm run build`（`frontend/`）
- 推送：需 Steam++

---

## 七、执行顺序总览

```
阶段0 文档纠错
   ↓
阶段1 正确性缺陷（8 项，不受大改影响）
   ↓
阶段2 多课题组改造 R1→R6（结构大改）
   ↓
阶段3 可读性重构 R1→R6（落在最终逻辑上）
   ↓
阶段4 前端性能与视觉 P0→P3
   ↓
阶段5 加固与收尾
```

**为什么不先做可读性/前端再做大改**：可读性 R4（长函数拆分）、R5（字段合一）、R6（结构调整）与多课题组 R3/R4/R5 **改同一批方法与文件**。先重构再改造 = 同一段代码改两遍；先改造再重构 = 只改一遍。
