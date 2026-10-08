<template>
  <div class="review-page">
    <!-- 28.18：复核页统计卡 —— 原来这一屏只有「状态下拉」，进来看不出待办总量与超期压力。
         卡片可点：点了直接切到对应筛选（沿用看板「指标即入口」的口径）。 -->
    <div class="rv-stats">
      <!-- note：口径说明，取自本页既有文案/查询语义（不编造业务数字） -->
      <StatCard label="待复核任务" :value="reviewStats.pending" icon="pending" tone="ochre"
        note="由质控评分产生" clickable @click="filterBy('pending')" />
      <StatCard label="超时未复核" :value="reviewStats.overdue" icon="invalid" tone="red"
        note="待复核中已超期" clickable @click="filterBy('overdue')" />
      <StatCard label="已完成复核" :value="reviewStats.done" icon="rate" tone="green"
        note="状态「已完成」" clickable @click="filterBy('done')" />
    </div>
    <!-- ① 待复核任务列表 -->
    <!-- 两个页签：「待复核任务」是复核主入口（原有内容一字未改）；
         「全部病历」让复核员也能像在病历数据页那样浏览全部病历，并展开同一套对照面板。 -->
    <el-tabs v-model="activeTab" class="rv-tabs">
    <el-tab-pane label="待复核任务" name="tasks">
    <PanelCard title="待复核任务列表">
      <div class="rv-bar">
        <!-- 这里的视觉标签是普通 span（不是 <label for>），控件本身没有无障碍名称，
             Chrome 会报「No label associated with a form field」；补 aria-label 即可 -->
        <span>状态</span>
        <el-select v-model="status" size="small" aria-label="状态" style="width: 130px" @change="loadTaskDebounced">
          <el-option label="待复核" value="待复核" />
          <el-option label="已完成" value="已完成" />
        </el-select>
        <el-button size="small" @click="load()">刷新</el-button>
        <span class="tip">超时仅视觉提醒、不自动流转；点击「进入复核」在下方展开对照</span>
        <!-- 批次9：worklist 入口 —— 复核员真正关心的是「现在必须处理哪几条」 -->
        <el-checkbox v-model="overdueOnly" size="small" @change="loadTaskDebounced">只看超期未复核</el-checkbox>
        <FreshnessTag :time="loadedAt" reason="数据为本次页面读取时刻；解析/质控更新后请刷新" />
      </div>

      <el-table
        v-loading="loading"
    element-loading-text="正在读取待复核任务…"
        :data="rows"
        border
        size="small"
        max-height="420"
        :row-class-name="rowClass"
        highlight-current-row
      >
        <el-table-column prop="taskId" label="任务ID" width="180" show-overflow-tooltip />
        <el-table-column prop="recordId" label="病历ID" width="300" show-overflow-tooltip />
        <el-table-column prop="issueType" label="问题类型" min-width="150" show-overflow-tooltip />
        <el-table-column prop="score" label="当前评分" width="90" />
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ fmt(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="截止时间" width="190">
          <template #default="{ row }">
            <span :class="{ overdue: row.overdue }">{{ fmt(row.deadlineTime) }}</span>
            <el-tag v-if="row.overdue" type="danger" size="small" effect="plain" class="od-tag">超时</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="activeTaskId && activeTaskId === row.taskId" link type="warning" disabled>当前</el-button>
            <el-button v-else link type="primary" @click="openReview(row)">进入复核</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <!-- 28.13：空态补行动引导 —— 复核任务不是凭空出现的，说清它从哪来、下一步点哪 -->
          <EmptyState text="暂无复核任务">
            <div class="empty-hint">
              复核任务由质控评分产生：先到「质控校验」页对范围内的病历执行重算，
              分级为「待复核」的任务会自动出现在这里。
            </div>
            <el-button size="small" type="primary" plain @click="$router.push('/qc-check')">去质控校验</el-button>
          </EmptyState>
        </template>
      </el-table>

      <!-- P5.2：关联病历已删的任务会被跳过，条数与总数对不上时给出解释 -->
      <div v-if="skippedMissing > 0" class="skip-hint">
        另有 {{ skippedMissing }} 条任务因关联病历已删除而无法展示
      </div>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="PAGE_SIZES_STANDARD"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        :disabled="loading"
        style="margin-top: var(--sp-3); justify-content: flex-end"
        @current-change="load"
        @size-change="handleSizeChange"
      />
    </PanelCard>
    </el-tab-pane>

    <el-tab-pane label="全部病历" name="records">
      <PanelCard title="全部病历（可浏览并展开对照）">
        <div class="rv-bar">
          <el-button size="small" @click="loadAllRecords()">刷新</el-button>
          <span class="tip">
            点任意一行即可在下方展开与复核完全相同的原文对照 / 人工修正面板。
            该病历若没有待复核任务，则只可查看与修正、不能提交裁决（后端要求任务存在）。
          </span>
        </div>
        <!-- max-height 与病历数据页同口径（420）：每页 10 行直接显示完，
             不再出现「要多滚一格才见底」的内部滚动条 -->
        <RecordTable
          ref="allTableRef"
          :rows="allRows"
          :loading="allLoading"
          @sort-change="onAllSortChange"
          loading-text="正在读取病历…"
          highlight-current
          :max-height="420"
          :action-width="90"
          @row-click="openReviewFromRecords"
        >
          <template #action="{ row }">
            <el-button link type="primary" @click.stop="openReviewFromRecords(row)">查看</el-button>
          </template>
          <template #empty>
            <EmptyState :failed="allFailed" :loading="allLoading" text="没有符合条件的病历"
                        @retry="() => loadAllRecords()" />
          </template>
        </RecordTable>
        <el-pagination
          v-model:current-page="allPage"
          v-model:page-size="allPageSize"
          :page-sizes="PAGE_SIZES_STANDARD"
          :total="allTotal"
          layout="total, sizes, prev, pager, next, jumper"
          :disabled="allLoading"
          style="margin-top: var(--sp-3); justify-content: flex-end"
          @current-change="loadAllRecords"
          @size-change="handleAllSizeChange"
        />
      </PanelCard>
    </el-tab-pane>
    </el-tabs>

    <!-- ②~⑥ 详情区：当前任务卡 / 原文对照 / 左右对比 / 提交反馈 / 底部操作，见 ReviewDetailPanel.vue -->
    <ReviewDetailPanel
      ref="detailRef"
      @opened="onDetailOpened"
      @closed="activeTaskId = null"
      @submitted="onSubmitted"
    />
  </div>
</template>

<script setup>
// 人工复核页：上方是待复核 / 已完成任务列表，点「进入复核」在下方展开该任务的病历原文对照、
// NLP 原始结构化数据（只读）与人工修正表单，左右并排比对。
// 关键取舍：修正提交前只做「已补齐核心字段把对应扣分加回」的本地预估，最终评分与分级以服务端重算为准。
import { ref, reactive, watch, onMounted } from 'vue'
import PanelCard from '@/components/PanelCard.vue'
import { listReviewTasks, getReviewStats } from '@/api/review'
import { usePagedList } from '@/composables/usePagedList'
import { useUrlFilters } from '@/composables/useUrlFilters'
import { fmtDateTime } from '@/utils/format'
import { debounce } from '@/utils/debounce'
import { PAGE_SIZES_STANDARD } from '@/utils/constants'
import RecordTable from '@/components/RecordTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import FreshnessTag from '@/components/FreshnessTag.vue'
import { searchRecords } from '@/api/records'
import ReviewDetailPanel from '@/components/ReviewDetailPanel.vue'
import StatCard from '@/components/StatCard.vue'

// 时间格式化：去掉 T、截到分钟；空值返回「—」，避免列表里出现 Invalid Date
const fmt = (t) => (t ? fmtDateTime(t,'minute') : '—')

// ===== ① 任务列表 =====
const status = ref('待复核')
// 批次9：worklist「只需我处理」—— 只看已超期且仍待复核的任务。过滤在服务端做（overdueOnly），
// 不是在前端筛当前页：否则用户看到一片干净、其它页却还有超期任务，等于状态撒谎。
const overdueOnly = ref(false)
// P5.2：因关联病历已删而跳过的任务数
const skippedMissing = ref(0)
const page = ref(1)
const pageSize = ref(10)
// 列表骨架统一走 usePagedList：本页按原口径「失败只由拦截器提示 —— 不清空已有行、
// 也不置失败标记」，故 clearOnFailure / trackFailure 都关掉
const { list: rows, total, loading, load: loadTasks } = usePagedList({
  fetcher: (signal) => listReviewTasks({ page: page.value, pageSize: pageSize.value, status: status.value, overdueOnly: overdueOnly.value }, { signal }),
  extract: (res) => ({ list: res.data?.tasks, total: res.data?.total }),
  onLoaded: (res) => { skippedMissing.value = res.data?.skippedMissing || 0 },
  clearOnFailure: false,
  trackFailure: false
})

// ===== 页签 =====
const activeTab = ref('tasks')

// 对标 E5「可分享视图」：这一步必须放在页签声明之后、onMounted 之前。
// 本页的筛选是两个独立 ref（status / overdueOnly），用 reactive 包一层得到组合式期望的
// 「状态对象」—— Vue 的 reactive 会自动解包 ref 属性，写入会回到原 ref，
// 因此这两个 ref 的既有用法一行都不用改。分享场景很典型：
// 「把『只看超期』这个队列发给同事」，打开就是同一个视图。
const urlFilters = reactive({ status, overdueOnly })
useUrlFilters(urlFilters, page, pageSize)
// P2-9 data freshness: page read time (honest, no backend field, no guessing load internals)
const loadedAt = ref(new Date().toLocaleString())

// ===== 「全部病历」列表 =====
const allPage = ref(1)
const allPageSize = ref(10)
const allTableRef = ref(null)
// B3 列头排序（评分 / 接诊时间）：本页 allPage 本来就不入 URL，sort 同口径不入 URL
const allSort = reactive({ sortBy: '', sortOrder: '' })
// 「全部病历」列表：本页原本没有取号（后到的旧响应会覆盖新结果）—— 按验收「行为不变」
// 的要求保留这一现状，故 race: false；失败要清空并标记，走默认
const {
  list: allRows, total: allTotal, loading: allLoading, failed: allFailed, load: loadAllList
} = usePagedList({
  fetcher: (signal) => searchRecords({
    page: allPage.value, pageSize: allPageSize.value,
    sortBy: allSort.sortBy, sortOrder: allSort.sortOrder
  }, { signal }),
  extract: (res) => ({ list: res.data?.records, total: res.data?.total }),
  race: false
})

/**
 * 拉「全部病历」列表。
 *
 * <p>数据域仍由后端按登录者身份过滤（管理员看全部 / 其余看本组织），
 * 这里不自己拼组织条件 —— 前端拼的条件改一次就得全页同步，漏一处就是越权。</p>
 */
const loadAllRecords = async (p) => {
  if (p) allPage.value = p
  await loadAllList()
}

// B3 列头排序变更：写排序回第 1 页重查（排序变了旧页码没有意义）
const onAllSortChange = ({ sortBy, sortOrder }) => {
  allSort.sortBy = sortBy
  allSort.sortOrder = sortOrder
  loadAllRecords(1)
}

const handleAllSizeChange = (sz) => {
  allPageSize.value = sz
  loadAllRecords(1)
}
// 查询复核任务列表：传数字即跳到该页（翻页与重试共用同一入口）
const load = async (p) => {
  if (typeof p === 'number') page.value = p
  await loadTasks()
}

// 状态/超期开关的连发防抖（性能审查 P1-5 收尾 / B5）：300ms 内连点只发最后一次查询。
// AbortController 兜底「已发出的旧请求」，这里负责「干脆别发出」；按钮语义不变。
const loadTaskDebounced = debounce(() => load(1), 300)

// 每页条数变化：回到第 1 页再查，防止页码越界后拿到空列表
const handleSizeChange = () => {
  page.value = 1
  load()
}

// 超时任务整行标红（样式见 .row-overdue），只做视觉提醒、不自动流转
const rowClass = ({ row }) => (row.overdue ? 'row-overdue' : '')

// ===== 28.18 复核概览统计 =====
// 三个数由专用 /count 端点一次返回（只 COUNT 不 SELECT）；原先三次 pageSize=1 的
// 列表查询每次都会附带一次全表排序（性能审查 P1-5）。
const reviewStats = reactive({ pending: 0, done: 0, overdue: 0 })
const loadReviewStats = async () => {
  try {
    const res = await getReviewStats()
    reviewStats.pending = res.data?.pending ?? 0
    reviewStats.done = res.data?.done ?? 0
    reviewStats.overdue = res.data?.overdue ?? 0
  } catch { /* 拦截器已提示；统计失败不影响任务列表本身 */ }
}

// 点统计卡 = 切到任务页签 + 套用对应筛选（与下拉框同一套状态，URL 分享仍然有效）
const filterBy = (kind) => {
  activeTab.value = 'tasks'
  if (kind === 'done') { status.value = '已完成'; overdueOnly.value = false }
  else if (kind === 'overdue') { status.value = '待复核'; overdueOnly.value = true }
  else { status.value = '待复核'; overdueOnly.value = false }
  load(1)
}

// ===== 详情面板桥接（复核详情已抽为 ReviewDetailPanel）=====
const detailRef = ref(null)
const activeTaskId = ref(null)
const openReview = (row) => detailRef.value?.open(row)
const onDetailOpened = (row) => { activeTaskId.value = row?.taskId || null }

/**
 * 从「全部病历」进入复核。
 *
 * <p>病历列表行的主键叫 {@code id}，复核任务行叫 {@code recordId}；
 * 这里补齐成 openReview 期望的形状。<b>刻意不带 taskId</b> ——
 * 后端 submitReview 要求该病历存在待复核任务，从「全部病历」进入的病历可能没有，
 * 提交按钮据此禁用（见模板），而不是让用户填完表才被后端拒绝。</p>
 */
const openReviewFromRecords = (row) => {
  openReview({ recordId: row.id, score: row.score, taskId: null, fromRecords: true })
}

// 提交裁决后：任务列表与概览统计都要重取（状态可能由待复核变已完成）
const onSubmitted = () => {
  load()
  loadReviewStats()
}

// 切到「全部病历」时才加载：进页面就查会白付一次请求（大多数人先看任务）
watch(activeTab, (tab) => {
  if (tab === 'records' && allRows.value.length === 0) {
    loadAllRecords()
  }
})

onMounted(() => {
  load(1)
  loadReviewStats()
})
</script>

<style scoped>
.rv-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--sp-3);
}
/* 28.18：三张概览卡等宽排一行（窄屏换行） */
.rv-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--sp-3);
  margin-bottom: 10px;
}
@media (max-width: 900px) {
  .rv-stats { grid-template-columns: 1fr; }
}
.rv-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: var(--sp-3);
  flex-wrap: wrap;
}
.rv-bar > span:first-child {
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
}
.overdue {
  color: var(--danger);
  font-weight: bold;
}
.od-tag {
  margin-left: 6px;
}
:deep(.row-overdue) {
  background: var(--danger-surface);
}

.review-page {
  display: flex;
  flex-direction: column;
  /* 与 theme.css 的 .page-fill 同口径：页面根与 main 之间隔着 Transition 包裹的
     无类名 div（height:auto），100% 解析不到，改用视口推算（顶栏 52 + main 内边距
     16/84 + 面包屑 ≈34）。 */
  min-height: calc(100vh - 186px);
}
/* P5.2：跳过已删病历的提示 */
.skip-hint { margin-top: var(--sp-2); font-size: var(--fs-xs); color: var(--text-sub-strong); }
/* 28.13：空态引导文案，居中并限制行宽 */
.empty-hint { max-width: 380px; margin: 0 auto var(--sp-2); font-size: var(--fs-xs); color: var(--text-sub-strong); line-height: 1.8; }
</style>
