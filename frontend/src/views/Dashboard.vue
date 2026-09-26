<template>
  <!-- 首页看板（UX-60）：只留 4 块 —— 待办快捷条 + 4 张指标卡 + 质控趋势 + 评分分布/科室合格率 -->
  <div>
    <StatsFilter :model="filter" :departments="departments" @search="loadAll" @reset="resetFilter" />

    <!-- 待办快捷条；无权限的卡片置灰并标注，避免点了才被 403 弹回（UX-02）
         用 button 而非 div：天然可聚焦、支持 Enter/Space（UX-18） -->
    <section class="todo-bar">
      <button
        type="button"
        class="todo"
        :class="{ warn: overview.pendingReviewCount > 0 }"
        @click="go('/review', '人工复核')"
      >
        <span class="todo-num">{{ overview.pendingReviewCount }}</span>
        <span class="todo-lbl">待复核 ›</span>
      </button>
      <button
        type="button"
        class="todo"
        :class="{ warn: govern.pendingGovern > 0, readonly: !canVisit('清洗与导出') }"
        @click="go('/governance', '清洗与导出')"
      >
        <span class="todo-num">{{ govern.pendingGovern }}</span>
        <span class="todo-lbl">
          待清洗 <span v-if="canVisit('清洗与导出')">›</span>
          <span v-else class="todo-lock">仅管理员</span>
        </span>
      </button>
      <!-- 「病历总数」原来也在这里占一张可点卡片，但它没有动作语义（点进去只是跳质控页），
           而且与下方指标卡的同一个数字重复。待办条只留动作型入口，数字看指标卡。 -->
    </section>

    <!-- 只遮数据区：筛选条保持可交互，避免整页白屏 -->
    <div v-loading="loading" element-loading-text="数据加载中…">
      <!-- 4 张指标卡；tone 决定数字配色（green 达标 / ochre 待办 / red 异常） -->
      <div class="stats">
        <StatCard label="病历总数" :value="overview.totalRecords" icon="record" />
        <StatCard label="质控合格率" :value="overview.qualifiedRate" tone="green" suffix="%" icon="rate" />
        <StatCard label="待复核" :value="overview.pendingReviewCount" tone="ochre" icon="pending" />
        <StatCard label="无效数据" :value="overview.invalidCount" tone="red" icon="invalid" />
      </div>

      <!-- 质控趋势（跨整行） -->
      <PanelCard title="质控趋势（按月）" class="mb">
        <div
          v-if="extra.trend.length"
          ref="trendRef"
          class="chart-tall"
          role="img"
          :aria-label="trendLabel"
        />
        <EmptyState v-else :failed="failed" :loading="loading" text="暂无趋势数据" @retry="loadAll" />
      </PanelCard>

      <div class="grid-2 mb">
        <PanelCard title="评分分布">
          <div
            v-if="hasScores"
            ref="distRef"
            class="chart"
            role="img"
            :aria-label="distLabel"
          />
          <EmptyState v-else :failed="failed" :loading="loading" text="暂无评分数据" @retry="loadAll" />
        </PanelCard>
        <PanelCard title="科室合格率">
          <!-- 纯 CSS 进度条列表：避免为一个小占比图再起一个 ECharts 实例 -->
          <div v-if="extra.departmentRates.length" class="rate-list">
            <div v-for="d in extra.departmentRates" :key="d.department" class="rate-item">
              <span class="rate-name" :title="d.department">{{ d.department }}</span>
              <div class="rate-track">
                <div class="rate-fill" :style="{ width: d.qualifiedRate + '%' }" />
              </div>
              <span class="rate-val">{{ d.qualifiedRate }}%</span>
              <span class="rate-sub">合格 {{ d.qualified }} / 共 {{ d.total }}</span>
            </div>
          </div>
          <EmptyState
            v-else
            :failed="failed"
            :loading="loading"
            text="暂无科室数据"
            @retry="loadAll"
          />
        </PanelCard>
      </div>
    </div>
  </div>
</template>

<script setup>
// 首页看板：待办快捷条 + 指标卡 + 两张图（趋势折线、评分分布柱状）。
// 数据来自 /stats/overview（指标卡）与 /stats/extra（图表），筛选条件只影响后者。
import { reactive, ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import echarts from '@/utils/echarts'
import StatsFilter from '@/components/StatsFilter.vue'
import StatCard from '@/components/StatCard.vue'
import PanelCard from '@/components/PanelCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getOverview, getExtraStats, getDepartments } from '@/api/stats'
import { governanceStats } from '@/api/governance'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

// 科室选项取自后端，与站内其他筛选器同一数据源（UX-03）
const departments = ref([])
// 拉取科室下拉选项；失败退化为空列表，筛选器仍可用日期区间查询
const loadDepartments = async () => {
  try {
    const res = await getDepartments()
    departments.value = res.data || []
  } catch {
    // 拉取失败退化为空列表，筛选器仍可用日期区间查询
    departments.value = []
  }
}

// 待办卡片按登录返回的菜单判断可达性；无权限时置灰并说明原因（UX-02）
const canVisit = (menuTitle) => (userStore.menus || []).includes(menuTitle)
// 待办卡片跳转：先按菜单判断可达性，无权限时只提示不跳转（避免点了才被 403 弹回）
const go = (path, menuTitle) => {
  if (!canVisit(menuTitle)) {
    ElMessage.info(`「${menuTitle}」仅管理员可访问`)
    return
  }
  router.push(path)
}

// 筛选条件：科室 + 就诊日期区间；只作用于图表接口
const filter = reactive({ department: '', start: '', end: '' })
const loading = ref(false)
// 区分「加载失败」与「确实为空」（UX-05）
const failed = ref(false)

// 指标卡数据（无参接口，不受筛选影响）
const overview = ref({
  totalRecords: 0,
  qualifiedRate: 0,
  pendingReviewCount: 0,
  invalidCount: 0
})
// 待清洗数：仅管理员可读，非管理员恒为 0（卡片置灰）
const govern = reactive({ pendingGovern: 0 })
const extra = ref({ trend: [], departmentRates: [], scoreDistribution: [] })

// ECharts 实例：惰性创建、按需重建（见 renderTrend / renderDist）
const trendRef = ref(null)
const distRef = ref(null)
let trendChart = null
let distChart = null

// 评分分布全为 0 时不画图（否则是一根空柱）
const hasScores = computed(() => (extra.value.scoreDistribution || []).some((b) => b.count > 0))

// 图表的文本替代：给屏幕阅读器与无法看图的环境提供关键结论（UX-35）
const trendLabel = computed(() => {
  const t = extra.value.trend || []
  if (!t.length) return '质控趋势图，暂无数据'
  const last = t[t.length - 1]
  return `质控趋势折线图，共 ${t.length} 个月；最新 ${last.month} 合格率 ${last.qualifiedRate}%，待复核 ${last.pendingReview} 条`
})

// 评分分布图的文本替代：只列非空分档，供屏幕阅读器与无法看图的环境使用
const distLabel = computed(() => {
  const d = (extra.value.scoreDistribution || []).filter((b) => b.count > 0)
  if (!d.length) return '评分分布图，暂无数据'
  return `评分分布柱状图：${d.map((b) => `${b.bucket} 分 ${b.count} 条`).join('，')}`
})

// 渲染趋势图。容器变化时先 dispose 旧实例再重建，避免 ECharts 挂在已卸载的 DOM 上
const renderTrend = () => {
  // 1. 空态占位时容器不存在，直接返回（等有数据再画）
  if (!trendRef.value) return
  // 2. 实例复用：容器换了先销毁旧实例再重建，避免 ECharts 挂在已卸载的 DOM 上
  if (!trendChart || trendChart.getDom() !== trendRef.value) {
    if (trendChart) trendChart.dispose()
    trendChart = echarts.init(trendRef.value)
  }
  // 3. 取趋势数据（按月）
  const t = extra.value.trend
  // 4. 组装配置并渲染：双 Y 轴 —— 左轴合格率、右轴待复核条数
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['合格率', '待复核'], right: 10, top: 0, textStyle: { fontSize: 12 } },
    grid: { left: 44, right: 48, top: 34, bottom: 28 },
    xAxis: { type: 'category', data: t.map((p) => p.month), axisLine: { lineStyle: { color: '#d8d2c4' } } },
    yAxis: [
      { type: 'value', name: '合格率%', max: 100, axisLabel: { formatter: '{value}' }, splitLine: { lineStyle: { color: '#efebe1' } } },
      { type: 'value', splitLine: { show: false } }
    ],
    series: [
      {
        name: '合格率', type: 'line', smooth: true, yAxisIndex: 0,
        data: t.map((p) => p.qualifiedRate),
        itemStyle: { color: '#3d5a4c' }, areaStyle: { color: 'rgba(61,90,76,0.10)' }
      },
      {
        name: '待复核', type: 'line', smooth: true, yAxisIndex: 1,
        data: t.map((p) => p.pendingReview),
        itemStyle: { color: '#96714f' }, lineStyle: { type: 'dashed' }
      }
    ]
  })
}

// 渲染评分分布柱状图；实例复用策略同 renderTrend
const renderDist = () => {
  // 1. 空态占位时容器不存在，直接返回
  if (!distRef.value) return
  // 2. 实例复用：容器换了先销毁旧实例再重建
  if (!distChart || distChart.getDom() !== distRef.value) {
    if (distChart) distChart.dispose()
    distChart = echarts.init(distRef.value)
  }
  // 3. 取评分分布数据（按分数档）
  const d = extra.value.scoreDistribution
  // 4. 组装配置并渲染：单轴柱状，x 为分数档、y 为条数
  distChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    xAxis: { type: 'category', data: d.map((b) => b.bucket), axisLine: { lineStyle: { color: '#d8d2c4' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#efebe1' } } },
    series: [{
      type: 'bar', barWidth: '46%',
      data: d.map((b) => b.count),
      itemStyle: { color: '#4d6b58', borderRadius: [2, 2, 0, 0] }
    }]
  })
}

// 图表接口的查询参数（指标卡走无参的 /stats/overview，所以这里只喂趋势 / 分布）
const params = () => ({
  department: filter.department || '',
  start: filter.start || '',
  end: filter.end || '',
  pattern: ''
})

// 看板只拉主线口径：指标卡走 /stats/overview（轻量），趋势/分布走 /stats/extra（UX-60）
const loadAll = async () => {
  // 1. 置加载态并清掉上一次的失败标记
  loading.value = true
  failed.value = false
  try {
    // 2. 并行拉取指标卡（无参）与图表数据（带筛选），两块互不依赖
    const [ov, ex] = await Promise.all([getOverview(), getExtraStats(params())])
    // 3. 分别回填指标卡与图表数据
    overview.value = ov.data
    extra.value = ex.data

    // 待清洗（仅管理员可读清洗统计）
    // 4. 管理员追加待清洗数（失败归零，不影响其余看板数据）
    if (userStore.role === '管理员') {
      try {
        const g = await governanceStats()
        govern.pendingGovern = g.data.pendingGovern ?? 0
      } catch {
        // 清洗统计拉取失败 → 归零，不影响其余看板数据
        govern.pendingGovern = 0
      }
    }
  } catch {
    // 拦截器已提示；标记失败态，空态区据此给出重试入口（UX-05）
    failed.value = true
  } finally {
    // 5. 收尾：重画两张图并复位加载态
    // 等 DOM 更新（空态换成图表容器）后再初始化 ECharts，否则拿不到 ref
    await nextTick()
    renderTrend()
    renderDist()
    loading.value = false
  }
}

// 重置筛选条件并重新拉取
const resetFilter = () => {
  filter.department = ''
  filter.start = ''
  filter.end = ''
  loadAll()
}

onMounted(() => {
  loadDepartments()
  loadAll()
  window.addEventListener('resize', handleResize)
})

// 窗口尺寸变化时让图表跟随容器重算
const handleResize = () => {
  if (trendChart) trendChart.resize()
  if (distChart) distChart.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  // 卸载时销毁两个 ECharts 实例，避免残留监听与内存泄漏
  ;[trendChart, distChart].forEach((c) => c && c.dispose())
  trendChart = distChart = null
})
</script>

<style scoped>
/* 待办快捷条：三列等宽网格；当前只放 2 张卡（病历总数卡已移除），第 3 列留空 */
.todo-bar {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 10px;
}
.todo {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 9px 18px;
  cursor: pointer;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
  /* button 元素重置：保持原卡片观感（UX-18） */
  width: 100%;
  text-align: left;
  font-family: inherit;
  font-size: inherit;
  color: inherit;
}
.todo:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(47, 70, 57, 0.1);
}
/* 无权限卡片：置灰、取消悬浮反馈，并标注原因（UX-02） */
.todo.readonly {
  cursor: default;
  opacity: 0.72;
}
.todo.readonly:hover {
  transform: none;
  box-shadow: none;
}
/* 「仅管理员」小标记 */
.todo-lock {
  font-size: 11.5px;
  font-weight: normal;
  color: var(--text-sub);
  border: 1px solid var(--line);
  border-radius: 2px;
  padding: 0 5px;
  margin-left: 4px;
}
/* 待办数字：大号；有待办时（.warn）转 ochre */
.todo-num {
  display: block;
  font-size: 22px;
  font-weight: bold;
  color: var(--ink);
  line-height: 1.2;
}
.todo.warn .todo-num { color: var(--ochre); }
.todo-lbl {
  display: block;
  font-size: 12.5px;
  color: var(--text-sub);
  margin-top: 2px;
}
/* 指标卡一行等宽排列 */
.stats {
  display: flex;
  gap: 12px;
  margin-bottom: 10px;
}
/* 下方两块面板并排（窄屏收单列） */
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
/* 面板自带下边距，网格内用间距代替，避免双重留白 */
.grid-2 :deep(.panel) {
  margin-bottom: 0;
}
.mb {
  margin-bottom: 10px;
}
/* 图表容器固定高度：ECharts 需要确定的尺寸才能初始化 */
.chart {
  width: 100%;
  height: 210px;
}
.chart-tall {
  width: 100%;
  height: 230px;
}
/* 科室合格率列表：超 260px 时内部滚动 */
.rate-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 260px;
  overflow-y: auto;
}
.rate-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}
/* 科室名固定宽度右对齐，过长省略（完整名放 title） */
.rate-name {
  width: 72px;
  text-align: right;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
}
/* 进度条轨道与填充；宽度由行内 style 按百分比给出 */
.rate-track {
  flex: 1;
  height: 13px;
  background: #f0ede4;
  border-radius: 2px;
  overflow: hidden;
}
.rate-fill {
  height: 100%;
  background: var(--ink-mid);
}
.rate-val {
  width: 46px;
  text-align: right;
  color: var(--ink);
}
.rate-sub {
  width: 40px;
  color: var(--text-sub);
}
/* 窄屏（<1200px）：面板与待办条都收成单列 */
@media (max-width: 1200px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
  .todo-bar {
    grid-template-columns: 1fr;
  }
}
</style>
