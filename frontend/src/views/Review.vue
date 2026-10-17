<template>
  <div class="review-page">
    <!-- 范围查询：科室 / 就诊时间 / 导入时间，与病历数据、质控等页同一套 RangeFilter。
         默认「不限」：复核任务是跨导入日期的积压队列，预收窄会把历史积压藏起来。
         范围对统计卡与任务列表同时生效 —— 卡片是「点进去看到的队列」的入口，
         数字必须与点击后的列表一致（「指标即入口」口径）。 -->
    <div class="rv-tools">
      <RangeFilter v-model="urlFilters" :show-grade="false" />
      <el-button type="primary" size="small" :loading="loading || statsLoading" @click="applyFilters">查 询</el-button>
      <el-button size="small" :disabled="loading || statsLoading" @click="resetFilters">重置</el-button>
      <span class="tip">范围对统计卡与任务列表同时生效</span>
    </div>
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
    <!-- 原「全部病历」页签已删：复核页只留任务队列；要浏览全部病历请用「病历数据」页。
         范围查询在页首（RangeFilter），对统计卡与本列表同时生效。 -->
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
import { ref, reactive, onMounted } from 'vue'
import PanelCard from '@/components/PanelCard.vue'
import { listReviewTasks, getReviewStats } from '@/api/review'
import { usePagedList } from '@/composables/usePagedList'
import { useUrlFilters } from '@/composables/useUrlFilters'
import { fmtDateTime } from '@/utils/format'
import { debounce } from '@/utils/debounce'
import { PAGE_SIZES_STANDARD } from '@/utils/constants'
import EmptyState from '@/components/EmptyState.vue'
import FreshnessTag from '@/components/FreshnessTag.vue'
import ReviewDetailPanel from '@/components/ReviewDetailPanel.vue'
import RangeFilter from '@/components/RangeFilter.vue'
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
// ===== 筛选状态（URL 可分享）=====
// 对标 E5「可分享视图」：这一步必须放在筛选状态声明之后、onMounted 之前。
// 本页的筛选 = 两个独立 ref（status / overdueOnly）+ 范围三键（department / dateRange /
// importRange），用 reactive 包一层得到组合式期望的「状态对象」—— Vue 的 reactive 会
// 自动解包 ref 属性，写入会回到原 ref，因此这两个 ref 的既有用法一行都不用改。
// 范围三键由页首 RangeFilter 就地维护（组件 v-model 就地改本对象，与病历数据 / 质控页
// 同口径），随本对象一起入 URL：刷新 / 分享「某科室的待复核队列」，打开就是同一个视图。
const urlFilters = reactive({ status, overdueOnly, department: '', dateRange: null, importRange: null })

// 组装范围参数：dateRange / importRange 是 [起, 止] 两元素数组，缺任一端都视为未选 ——
// 与后端 filters()「两端齐全才生效」同一口径，半截区间不发出去
const rangeParams = () => {
  const d = urlFilters.dateRange
  const ir = urlFilters.importRange
  return {
    department: urlFilters.department || '',
    start: d && d.length === 2 ? d[0] : '',
    end: d && d.length === 2 ? d[1] : '',
    importStart: ir && ir.length === 2 ? ir[0] : '',
    importEnd: ir && ir.length === 2 ? ir[1] : ''
  }
}

useUrlFilters(urlFilters, page, pageSize)
// P2-9 data freshness: page read time (honest, no backend field, no guessing load internals)
const loadedAt = ref(new Date().toLocaleString())

// 列表骨架统一走 usePagedList：本页按原口径「失败只由拦截器提示 —— 不清空已有行、
// 也不置失败标记」，故 clearOnFailure / trackFailure 都关掉
const { list: rows, total, loading, load: loadTasks } = usePagedList({
  fetcher: (signal) => listReviewTasks({ page: page.value, pageSize: pageSize.value, status: status.value, overdueOnly: overdueOnly.value, ...rangeParams() }, { signal }),
  extract: (res) => ({ list: res.data?.tasks, total: res.data?.total }),
  onLoaded: (res) => { skippedMissing.value = res.data?.skippedMissing || 0 },
  clearOnFailure: false,
  trackFailure: false
})
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
// 收与任务列表同一套范围参数 —— 卡片是「点进去看到的那个队列」的入口，数字与列表一致
const reviewStats = reactive({ pending: 0, done: 0, overdue: 0 })
// 统计加载态：查 询 按钮要同时等列表与计数两个请求回来，先到的那个解锁后还能再点
const statsLoading = ref(false)
const loadReviewStats = async () => {
  statsLoading.value = true
  try {
    const res = await getReviewStats(rangeParams())
    reviewStats.pending = res.data?.pending ?? 0
    reviewStats.done = res.data?.done ?? 0
    reviewStats.overdue = res.data?.overdue ?? 0
  } catch { /* 拦截器已提示；统计失败不影响任务列表本身 */ }
  finally { statsLoading.value = false }
}

// 范围查询：统计卡与任务列表一起刷新（整页口径，与质控页 applyFilters 同形状）
const applyFilters = () => {
  load(1)
  loadReviewStats()
}
// 重置范围并立即重查：本页默认口径就是「不限」（跨导入日期的积压队列），重置 = 清空全部范围条件
const resetFilters = () => {
  urlFilters.department = ''
  urlFilters.dateRange = null
  urlFilters.importRange = null
  applyFilters()
}

// 点统计卡 = 套用对应筛选（与下拉框同一套状态，URL 分享仍然有效）
const filterBy = (kind) => {
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

// 提交裁决后：任务列表与概览统计都要重取（状态可能由待复核变已完成）
const onSubmitted = () => {
  load()
  loadReviewStats()
}

onMounted(() => {
  load(1)
  loadReviewStats()
})
</script>

<style scoped>
/* 页首范围查询条：与 RangeFilter 同一行，控件底对齐（与质控页 .filter-bar 同口径） */
.rv-tools {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: 10px;
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
