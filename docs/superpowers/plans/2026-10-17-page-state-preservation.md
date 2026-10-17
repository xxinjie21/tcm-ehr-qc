# 页面状态保持（切换页面后复原）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 应用内切换页面（侧栏 / 通知跳转）后，切回来时页面回到离开时的完全状态——筛选/范围、分页、已加载列表、滚动位置、勾选、半填表单、打开中的弹窗——且不重新请求。

**Architecture:** 用 Vue 内置 `<KeepAlive>` 缓存 `MainLayout` 嵌套 `router-view` 的页面组件实例，替换当前「路径一变就重挂载」的行为；配套做滚动位置复原，并把「跨页且带 query 的跳转」当作显式指令（清空缓存让目标页按 query 重挂载）。

**Tech Stack:** Vue 3.5.13、vue-router 4.5、Element Plus、Vite 6。

## Global Constraints

- **无前端测试框架**：`frontend/package.json` 没有 `test` 脚本（无 vitest/jest）。验收 = `npm run build` 通过 + 浏览器手测（Task 3）。
- **不提交**：工作区有大量与本特性无关的未提交改动。除用户明确要求外，**不要 `git commit`**；Task 内「Commit」步骤默认跳过。
- **保持现状风格**：沿用现有中文注释习惯；改动尽量小、聚焦。
- 关键路径：`frontend/src/layouts/MainLayout.vue`、`frontend/src/views/Dashboard.vue`、`frontend/src/components/StandardizationCharts.vue`。
- 设计依据：`docs/superpowers/specs/2026-10-17-page-state-preservation-design.md`。

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `frontend/src/layouts/MainLayout.vue` | 嵌套 `router-view` 的缓存、滚动复原、query 清缓存守卫 | 修改 |
| `frontend/src/views/Dashboard.vue` | 看板两个 ECharts 实例，复原时重绘 | 修改 |
| `frontend/src/components/StandardizationCharts.vue` | 标准化报告两个 ECharts 实例，复原时重绘 | 修改 |

---

### Task 1: MainLayout — 缓存页面 + 滚动复原 + query 清缓存

**Files:**
- Modify: `frontend/src/layouts/MainLayout.vue`（模板 `:157-159`、注释 `:150-156`、`<main>` `:138`、script `:172`、`:181-194`）

**Interfaces:**
- Consumes: 无（首个任务）。
- Produces:
  - 变量 `cacheVersion: Ref<number>`（模板绑定 `<KeepAlive :key="cacheVersion">`）
  - 函数 `onMainScroll(): void`（`<main>` 的 `@scroll` 监听）
  - `Map<string, number> scrollPos`（按 `route.path` 记滚动位置）
  - `router.beforeEach` 守卫：`to.path !== from.path && to.query 非空` 时 `cacheVersion++` 且 `scrollPos.clear()`，`onBeforeUnmount` 注销。

- [x] **Step 1: 改模板——`router-view` 外包 `KeepAlive`，`<main>` 加滚动监听**

把 `frontend/src/layouts/MainLayout.vue` 的 `<main>` 开标签：

```vue
      <main id="main-content" ref="mainRef">
```

改为：

```vue
      <main id="main-content" ref="mainRef" @scroll="onMainScroll">
```

把 `<router-view v-slot=...>` 块（含其上方注释，`:150-159`）整体替换为：

```vue
        <!-- 内层路由切换：应用内子页面切换的主战场（顶层 App.vue 只管登录/注册 ↔ 外壳）。
             用 <KeepAlive> 缓存每个页面组件实例 —— 切走再切回时，筛选/范围、分页、已加载列表、
             滚动位置、勾选、半填表单、打开中的弹窗全部保持离开时原样，且不重新请求。
             `:key="r.path"` 保留但含义已变：从「强制重挂载」变成**缓存键**（每页一份实例）。
             `:key="cacheVersion"` 用于「跨页且带 query 的跳转」时整份清空（见 script 里的守卫）。
             刻意**不用** <Transition mode="out-in">（Vue 3.5 下对不同组件类型的分支会卡死，
             详见 theme.css ①b）；也刻意不加 include（本 router-view 的子路由即全部业务页）。
             滚动复原见下方 watch(route.path)。 -->
        <router-view v-slot="{ Component, route: r }">
          <KeepAlive :key="cacheVersion">
            <component :is="Component" :key="r.path" class="page-enter" />
          </KeepAlive>
        </router-view>
```

- [x] **Step 2: 改 script import，加入 `onBeforeUnmount`**

把 `:172`：

```js
import { computed, onMounted, ref, watch } from 'vue'
```

改为：

```js
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
```

- [x] **Step 3: 用滚动复原替换「切页归零」**

把 `:181-194` 这段（`mainRef` 声明 + 注释 + `watch(route.path, ...归零...)`）：

```js
const mainRef = ref(null)
watch(
  () => route.path,
  () => {
    if (mainRef.value) mainRef.value.scrollTop = 0
  },
  { flush: 'post' }
)
```

替换为：

```js
const mainRef = ref(null)
// 按 path 记住每个页面在主滚动容器里的滚动位置：切回缓存页时写回。
// 旧行为是「切页无条件归零」——那会把缓存页的滚动也一起清掉，与「完全复原」相悖。
// 滚动过程中持续记录（@scroll），所以离开时 map 里已是最后一次滚动位置。
const scrollPos = new Map()
const onMainScroll = () => {
  if (mainRef.value) scrollPos.set(route.path, mainRef.value.scrollTop)
}
// flush:'post' —— 等新页挂上、DOM 到位再写回；缓存页已是完整高度，写回有效；新页取 0。
watch(
  () => route.path,
  (to) => {
    if (mainRef.value) mainRef.value.scrollTop = scrollPos.get(to) ?? 0
  },
  { flush: 'post' }
)

// 跨页且带 query 的跳转 = 显式指令（如通知跳 /records?grade=无效、带 recordId 的跨页查看）：
// 清空整份缓存，让目标页按 query 重新挂载 —— 否则 KeepAlive 会复用旧实例、无视 query。
// ⚠️ 必须限定 to.path !== from.path：useUrlFilters 用 router.replace 把筛选写进当前页 URL，
//    这也会走守卫；若按「带 query 就清」处理，每改一次筛选就把当前页卸掉重挂 —— 灾难。
const cacheVersion = ref(0)
const removeQueryGuard = router.beforeEach((to, from) => {
  if (to.path !== from.path && Object.keys(to.query || {}).length) {
    cacheVersion.value++
    scrollPos.clear()
  }
})
onBeforeUnmount(removeQueryGuard)
```

- [x] **Step 4: 构建校验** ✅ `built in 7.53s`，无 error

Run（在 `frontend` 目录）：

```
npm run build
```

Expected: 构建成功、无报错（模板里 `cacheVersion` / `onMainScroll` 均已定义）。

- [ ] **Step 5: 提交（按需，默认跳过）**

除非用户明确要求，否则**跳过**。用户同意后：

```bash
git add frontend/src/layouts/MainLayout.vue
git commit -m "feat(nav): 用 KeepAlive 保持页面状态并复原滚动位置"
```

---

### Task 2: 图表页复原时重绘

```KeepAlive 复原是把 DOM 从缓存里重新插入，ECharts 实例的容器被移出又插回 —— 需在 `onActivated` 里重画，否则可能空白/尺寸错。
```

**Files:**
- Modify: `frontend/src/views/Dashboard.vue`（import `:126`；在 `onMounted` 之后、`handleResize` 之前插入钩子，约 `:389`）
- Modify: `frontend/src/components/StandardizationCharts.vue`（import `:70`；在 `onMounted` 之后插入钩子，约 `:286`）

**Interfaces:**
- Consumes: Task 1 的 KeepAlive 缓存（`onActivated` 才可能被调用）。
- Produces: 无（仅副作用）。

- [x] **Step 1: Dashboard.vue —— 引入并新增 `onActivated`**

把 `:126`：

```js
import { reactive, ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
```

改为：

```js
import { reactive, ref, computed, onMounted, onBeforeUnmount, nextTick, onActivated } from 'vue'
```

在 `onMounted(() => { ... })` 块（`:385-389`）之后插入：

```js
// KeepAlive 复原（切走再切回）时重画两张图：图表容器被移出又插回 DOM，
// 复用同一份已加载数据重绘即可（renderTrend/renderDist 内部仅在容器变化时才 dispose 重建）。
onActivated(async () => {
  await nextTick()
  renderTrend()
  renderDist()
})
```

- [x] **Step 2: StandardizationCharts.vue —— 引入并新增 `onActivated`**

把 `:70`：

```js
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
```

改为：

```js
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
```

在 `onMounted(() => { ... })` 块（`:283-286`）之后、`onBeforeUnmount(...)` 之前插入：

```js
// KeepAlive 复原时重画两张图（容器被移出又插回 DOM）；数据未变，用现有 props 重绘即可。
onActivated(() => nextTick(renderAll))
```

- [x] **Step 3: 构建校验** ✅ 同 Task 1 Step 4（一次性构建通过）

Run（在 `frontend` 目录）：

```
npm run build
```

Expected: 构建成功、无报错。

- [ ] **Step 4: 提交（按需，默认跳过）**

用户同意后：

```bash
git add frontend/src/views/Dashboard.vue frontend/src/components/StandardizationCharts.vue
git commit -m "fix(charts): KeepAlive 复原后重绘 ECharts"
```

---

### Task 3: 端到端验收（浏览器）

**Files:** 无（仅验证）。

**Interfaces:**
- Consumes: Task 1、Task 2 的改动。
- Produces: 验收结论。

前置：前端已跑（`npm run dev`，默认 `:3000`），已登录。

- [x] **Step 1: 列表页完全复原**

1. 进入「病历数据」，设一个筛选（如科室）、翻到第 2 页、（如可）滚动若干。
2. 点侧栏「质控校验」。
3. 点侧栏回到「病历数据」。
Expected: 筛选、页码、列表内容、滚动位置与离开时一致；**无 loading、无重新请求**。

- [x] **Step 2: 范围查询复原**

1. 进入「病历数据」或「标准化质量报告」，设「就诊时间」为一个区间。
2. 切到别的页，再切回。
Expected: 就诊时间范围仍在。

- [~] **Step 3: 弹窗保持打开**（部分：结构上由 KeepAlive 天然覆盖；手测受「模态遮罩拦住侧栏」所限，见下方验收结果）

1. 在「病历数据」打开某条病历的「详情」弹窗（不关闭）。
2. 切到别的页，再切回。
Expected: 详情弹窗仍开着、内容仍在。

- [x] **Step 4: 带 query 的跨页跳转不被缓存挡住**

1. 顶栏通知铃铛点「无效数据」项（会跳 `/records?grade=无效`）。
Expected: 进入病历数据页且**按「无效」过滤**（若从别页跳来，缓存被清、按 query 重挂）。

- [x] **Step 5: 图表复原正常**

1. 「首页看板」看图正常 → 切到别的页 → 切回看板。
2. 「标准化质量报告」同上。
Expected: 两处图表均正常显示（非空白、尺寸正确）。

- [ ] **Step 6: 不跨用户 / 不跨组织残留**

1. 退出登录 → 用另一账号登录：不应看到上一账号的页面状态。
2. （多组织账号）切换组织：各页按新组织重新加载，无旧组织残留。
Expected: 缓存随 `MainLayout` 卸载 / `location.reload()` 清空。

- [x] **Step 7: 同页改筛选不重挂（防回归）**

1. 在「病历数据」上快速连续改筛选/翻页。
Expected: 页面**不整页重挂**（不闪、不回到第一页顶部）；正常按新条件请求。

### 验收结果（2026-10-17，浏览器实测）

工具：`agent-browser`（session `statecheck`，headless Chrome，走真实 `:3000` dev server + `:8080` 后端）。
「无重新请求」的判定：注入 `XMLHttpRequest.prototype.open` / `window.fetch` 计数器，读 `window.__reqs`。

| 步骤 | 结果 | 证据 |
| --- | --- | --- |
| Step 1 列表复原 | ✅ | 「病历数据」翻到第 2 页（首行 `c0443624`）→ 切「首页看板」→ 切回：首行仍 `c0443624`、分页仍 2、URL 复原为 `/records?page=2`；返回时 `__reqs == []`（**零请求**）。对照组：看板首次加载确有 `GET /api/governance/stats`、`/api/stats/overview`、`/api/stats/extra`。 |
| Step 2 范围复原 | ✅ | 「病历数据」点「今天」（导入时间 `2026-10-17`）→ 来回切换两轮 → 两个导入时间选择框仍为 `2026-10-17`。 |
| Step 3 弹窗保持 | ⚠️ 结构满足 | 打开「病历详情」弹窗后，el-overlay 遮罩拦截侧栏点击（URL 不变、切不走）—— 模态下经侧栏不可达该场景；但 KeepAlive 下弹窗属页面实例状态，若发生离页（程序化跳转）会随之复原。关闭弹窗后切换正常。 |
| Step 4 query 跨页 | ✅ | `pushstate` 到 `/records?grade=x`：命中守卫（`to.path!==from.path && query 非空`）→ `cacheVersion++` → **不**返回缓存页，目标页重挂并发出 `POST /api/records/search`。 |
| Step 5 图表复原 | ✅ | 「首页看板」2 个 canvas（首个 966×180）→ 切走再切回 → 仍 2 个 canvas 且尺寸不变、`onActivated` 重绘生效；返回时 `__reqs == []`。 |
| Step 6 跨用户/组织 | ⏳ 未实测 | 设计上：登出（`MainLayout` 卸载）与 `switchOrg`→`location.reload()` 都会销毁 KeepAlive 实例，天然清空；未在浏览器跑双账号流程。 |
| Step 7 同页改筛选 | ✅ | 「病历数据」点「今天」触发 `useUrlFilters` 的 `router.replace('?importRange=…')`：页面**未重挂**（筛选控件保留、未回第一页）—— 证明守卫的 `to.path !== from.path` 限定生效。 |

**已知副作用（可接受）**：命中 query 守卫时，`cacheVersion++` 会在离页前把「当前页」也重挂一次（Step 4 实测到 3 个 `/api/stats/*` 请求）—— 当前页马上被切走，可见影响可忽略。若日后嫌浪费，可改在 `router.afterEach` 清缓存（代价：目标页会先短暂复用旧实例再重挂）。

---

## Self-Review

**Spec coverage：**
- 完全复原（不重请求）→ Task 1 Step 1（KeepAlive）。✓
- 全部业务页 → 不传 `include`，缓存 `MainLayout` 全部子路由。✓
- 滚动复原 → Task 1 Step 3。✓
- 弹窗保持 → KeepAlive 天然保留组件本地状态（spec §2）。✓
- 跨页带 query 清缓存 → Task 1 Step 3 守卫。✓
- 图表 `onActivated` resize/重绘 → Task 2。✓
- 退出/切组织天然清空 → Task 3 Step 6 验证（spec §4.5）。✓
- 不抗刷新 / URL 不额外处理 → 不实现（spec §5、§7）。✓

**Placeholder scan：** 无 TBD/TODO；所有代码步骤均给出完整代码与精确路径行号。✓

**Type consistency：** `cacheVersion`（模板 + script）、`onMainScroll`（模板 `@scroll` + script）、`scrollPos`、`removeQueryGuard` 命名前后一致；`onActivated` 与既有 `renderTrend/renderDist/renderAll` 名称一致。✓
