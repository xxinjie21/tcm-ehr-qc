# 页面状态保持（切换页面后复原）设计

日期：2026-10-17
状态：已确认，待实现

## 1. 背景

应用内切换页面后，原页面的状态会全部丢失：侧栏或通知跳转到一个新路由，会**重新挂载**目标页面组件。

根因：
- `frontend/src/layouts/MainLayout.vue:157-159` 用 `<component :is="Component" :key="r.path" />`
  渲染嵌套 `router-view`，`:key="r.path"` 让**路径一变就重挂载**，页面组件内的一切状态（筛选、
  分页、已加载列表、勾选、半填表单、打开中的弹窗）随之销毁。
- `frontend/src/layouts/MainLayout.vue:188-194` 在 `route.path` 变化时把 `<main>` 的 `scrollTop` 归零。
- 筛选/分页只存在于页面组件内存里；`useUrlFilters` 只解决「同一页只改 query」时的还原
  （切页时 URL 被新页替换，回来时 URL 已无这些参数）。

## 2. 目标与已确认决策

| 维度 | 决策 |
|---|---|
| 复原程度 | **完全复原**：筛选/范围、页码/每页条数、已加载列表与滚动位置、勾选、半填表单、**打开中的弹窗**都保持离开时原样，**不重新请求** |
| 抗刷新 | **不抗**：只覆盖应用内切换页面；F5 / 重登后回默认（与现状一致） |
| 范围 | **全部业务页**（`MainLayout` 下的所有子路由） |
| 弹窗 | 离开时开着，切回来仍开着 |

## 3. 方案

用 Vue 内置 **`<KeepAlive>`** 缓存页面组件实例，取代「路径一变就重挂载」的行为。选它是因为
它天然满足「完全复原且不重新请求」，且改动集中在 `MainLayout.vue`，各业务页基本不动。

（对比方案：每页状态存 Pinia + 返回时重查 —— 数据要重拉、会闪 loading，且弹窗/半填表单需逐页
手工接线，与「完全复原」不符，不采用。）

## 4. 机制与改动

### 4.1 缓存页面（`MainLayout.vue`）

```vue
<router-view v-slot="{ Component, route: r }">
  <KeepAlive :key="cacheVersion">
    <component :is="Component" :key="r.path" class="page-enter" />
  </KeepAlive>
</router-view>
```

- `:key="r.path"` 保留，含义从「强制重挂载」变为**缓存键**（每个路径一份实例）。
- 不传 `include`：`MainLayout` 的子路由即全部业务页，全缓存。登录/注册在顶层 `App.vue`，天然不在其中。
- `cacheVersion`：见 4.3。

### 4.2 滚动复原（`MainLayout.vue`）

- 新增按 path 记录 `<main>` `scrollTop` 的 Map；给 `<main>` 挂 `scroll` 监听写入当前 path 的记录。
- `route.path` 的 watcher 改为：`mainRef.scrollTop = saved.get(newPath) ?? 0`（保持 `flush:'post'`，
  复原时缓存页 DOM 已是完整高度，写回有效；新页取 0）。
- 移除「无条件归零」的旧行为。

### 4.3 带 query 的跳转 = 外部指令（`MainLayout.vue`）

页面自身会用 `useUrlFilters` 把筛选写进 URL，因此「带 query 的导航」分两种：

- **纯路径导航**（侧栏 `router-link` 无 query）→ **复原**（用缓存）。
- **跨页且带 query 的导航**（通知跳 `/records?grade=无效`、跨页带 `recordId`）→ 视为显式指令：
  **清空整份缓存**（`cacheVersion++` + 清空滚动 Map），让目标页按 query **重新挂载**。
  否则缓存实例会无视 query（回归 bug）。
- **同页改 query 不清缓存**：`useUrlFilters` 用 `router.replace` 把筛选写进当前页 URL，这也会
  触发导航守卫。若按「带 query 就清」处理，每改一次筛选就会把当前页卸掉重挂 —— 灾难。

判定规则因此是：**`to.path !== from.path && to.query 非空`** 才清。

实现：`MainLayout` 内注册 `router.beforeEach((to, from) => { if (to.path !== from.path && Object.keys(to.query || {}).length) { cacheVersion.value++; scrollPos.clear() } })`，
并在 `onBeforeUnmount` 注销该守卫（`beforeEach` 返回注销函数）。

### 4.4 图表页复原后重绘

ECharts 页（`Dashboard.vue`、`StandardizationReport.vue`）在缓存复原后可能不重绘，改为在
`onActivated` 里对已建实例调 `resize()`（各页按其 chart 引用逐个处理）。列为验证项。

### 4.5 天然清空时机

- **退出登录**：`MainLayout` 卸载 → `KeepAlive` 缓存销毁。
- **切换组织**：`stores/user.js:switchOrg` 走 `location.reload()` → 整应用重启 → 缓存与滚动 Map 全清。

二者都不需要额外代码，且保证不跨用户 / 跨组织显示旧数据。

### 4.6 注释与文档同步

`MainLayout.vue` 内关于「`:key 强制重建 + 淡入」的注释需改写为新行为；进入动画只在**首次挂载**
播放，复原时不再播（更贴近「没离开过」）。

## 5. 边界与取舍

- **URL 与地址栏**：切回来时地址栏是当前路径（可能不含筛选），因为离开时 URL 已被新页替换。
  这与「不抗刷新」一致 —— 刷新即回默认，不做额外处理。
- **内存**：缓存所有访问过的业务页；单页数据量小（分页 20 行级），可接受，暂不加淘汰上限，
  除非 bump `cacheVersion`。
- **清缓存的波及面**：带 query 的**跨页**跳转会清空**整份**缓存，因此其它页原先保留的状态也会
  一起丢。这是可接受的取舍 —— 这类跳转是显式指令（通知 / 跨页查看某条病历），且不常见；用整份
  清空换取「不必把路由名映射到组件名」的简单实现。
- **同页改 query**：沿用现状（不重挂载），页面自己用 watcher 响应（如「复核」页的 `recordId`）。

## 6. 验收

1. 病历数据：设筛选 + 翻页 + 滚动 → 切「质控校验」→ 切回：原样，无 loading。
2. 范围查询页：设「就诊时间」范围（如病历数据 / 标准化报告）→ 切走切回：范围仍在。
3. 打开「病历详情」弹窗 → 切走切回：弹窗仍开。
4. 通知点「无效数据」→ `/records?grade=无效`：能按无效过滤（不被缓存挡住）。
5. 看板 / 标准化报告：切走切回图表正常显示（`onActivated` resize 生效）。
6. 退出登录、切换组织：不残留上一用户 / 上一组织的页面状态。

## 7. 不做（Out of scope）

- 抗浏览器刷新（F5）的持久化。
- 标签页 / 多实例缓存（本应用单路由，不需要）。
- 缓存淘汰策略（LRU / 上限）。
