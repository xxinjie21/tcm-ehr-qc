<template>
  <div>
    <StatsFilter :model="filter" :departments="departments" @search="loadAll" @reset="resetFilter" />

    <!-- 待办快捷条（点击跳转） -->
    <section class="todo-bar">
      <div class="todo" :class="{ warn: overview.pendingReviewCount > 0 }" @click="$router.push('/review')">
        <div class="todo-num">{{ overview.pendingReviewCount }}</div>
        <div class="todo-lbl">待复核 ›</div>
      </div>
      <div class="todo" :class="{ warn: govern.pendingGovern > 0 }" @click="$router.push('/governance')">
        <div class="todo-num">{{ govern.pendingGovern }}</div>
        <div class="todo-lbl">待治理 ›</div>
      </div>
      <div class="todo" @click="$router.push('/qc-check')">
        <div class="todo-num">{{ overview.totalRecords }}</div>
        <div class="todo-lbl">病历总数 ›</div>
      </div>
    </section>

    <!-- 只遮数据区：筛选条保持可交互，避免整页白屏 -->
    <div v-loading="loading" element-loading-text="数据加载中…">
      <div class="stats">
        <StatCard label="病历总数" :value="overview.totalRecords" icon="record" />
        <StatCard label="质控合格率" :value="overview.qualifiedRate" tone="green" suffix="%" icon="rate" />
        <StatCard label="待复核" :value="overview.pendingReviewCount" tone="ochre" icon="pending" />
        <StatCard label="无效数据" :value="overview.invalidCount" tone="red" icon="invalid" />
      </div>

      <!-- 质控趋势（跨整行） -->
      <PanelCard title="质控趋势（按月）" class="mb">
        <div v-if="extra.trend.length" ref="trendRef" class="chart-tall" />
        <el-empty v-else-if="!loading" description="暂无趋势数据" :image-size="80" />
      </PanelCard>

      <div class="grid-2 mb">
        <PanelCard title="评分分布">
          <div v-if="hasScores" ref="distRef" class="chart" />
          <el-empty v-else-if="!loading" description="暂无评分数据" :image-size="80" />
        </PanelCard>
        <PanelCard title="科室合格率">
          <div v-if="extra.departmentRates.length" class="rate-list">
            <div v-for="d in extra.departmentRates" :key="d.department" class="rate-item">
              <span class="rate-name" :title="d.department">{{ d.department }}</span>
              <div class="rate-track">
                <div class="rate-fill" :style="{ width: d.qualifiedRate + '%' }" />
              </div>
              <span class="rate-val">{{ d.qualifiedRate }}%</span>
              <span class="rate-sub">/{{ d.total }}</span>
            </div>
          </div>
          <el-empty v-else-if="!loading" description="暂无科室数据" :image-size="80" />
        </PanelCard>
      </div>

      <div class="grid-2 mb">
        <PanelCard title="疾病频次 TOP10">
          <BarList :items="diseaseStats.map((s) => ({ name: s.disease, value: s.count }))" />
        </PanelCard>
        <PanelCard title="症状频次 TOP10">
          <BarList :items="symptomStats.map((s) => ({ name: s.symptom, value: s.count }))" />
        </PanelCard>
      </div>

      <div class="grid-2 mb">
        <PanelCard title="证候分布">
          <div v-if="patternDist.length" ref="pieRef" class="chart" />
          <el-empty v-else-if="!loading" description="暂无证候分布数据" :image-size="80" />
        </PanelCard>
        <PanelCard title="方剂 / 中药频次 TOP5">
          <div class="dual">
            <div class="dual-col">
              <div class="dual-hd">方剂</div>
              <BarList :items="formulaStats.map((s) => ({ name: s.formula, value: s.count }))" color="var(--ochre)" />
            </div>
            <div class="dual-col">
              <div class="dual-hd">中药</div>
              <BarList :items="herbStats.map((s) => ({ name: s.herb, value: s.count }))" />
            </div>
          </div>
        </PanelCard>
      </div>

      <!-- 词典规模卡 -->
      <PanelCard title="术语词典规模">
        <div class="dict-grid">
          <div v-for="d in DICT_ITEMS" :key="d.key" class="dict-item">
            <div class="dict-num">{{ extra.dictionary[d.key] ?? 0 }}</div>
            <div class="dict-lbl">{{ d.label }}</div>
          </div>
        </div>
      </PanelCard>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import StatsFilter from '@/components/StatsFilter.vue'
import StatCard from '@/components/StatCard.vue'
import BarList from '@/components/BarList.vue'
import PanelCard from '@/components/PanelCard.vue'
import { getAllStats, getExtraStats } from '@/api/stats'
import { governanceStats } from '@/api/governance'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const departments = ['内科', '外科', '儿科', '针灸科']

const filter = reactive({ department: '', start: '', end: '' })
const loading = ref(false)

const overview = ref({
  totalRecords: 0,
  qualifiedRate: 0,
  pendingReviewCount: 0,
  invalidCount: 0
})
const govern = reactive({ pendingGovern: 0 })
const diseaseStats = ref([])
const symptomStats = ref([])
const patternDist = ref([])
const formulaStats = ref([])
const herbStats = ref([])
const extra = ref({ trend: [], departmentRates: [], scoreDistribution: [], dictionary: {} })

const DICT_ITEMS = [
  { key: 'disease', label: '疾病' },
  { key: 'symptom', label: '症状' },
  { key: 'pattern', label: '证候' },
  { key: 'herb', label: '中药' },
  { key: 'formula', label: '方剂' }
]

const PIE_COLORS = ['#3d5a4c', '#96714f', '#b39a77', '#7a9184', '#8fa0a8', '#cdc6b6']

const trendRef = ref(null)
const distRef = ref(null)
const pieRef = ref(null)
let trendChart = null
let distChart = null
let pieChart = null

const hasScores = computed(() => (extra.value.scoreDistribution || []).some((b) => b.count > 0))

const renderTrend = () => {
  if (!trendRef.value) return
  if (!trendChart || trendChart.getDom() !== trendRef.value) {
    if (trendChart) trendChart.dispose()
    trendChart = echarts.init(trendRef.value)
  }
  const t = extra.value.trend
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['合格率', '待复核数'], right: 10, top: 0, textStyle: { fontSize: 12 } },
    grid: { left: 44, right: 48, top: 34, bottom: 28 },
    xAxis: { type: 'category', data: t.map((p) => p.month), axisLine: { lineStyle: { color: '#d8d2c4' } } },
    yAxis: [
      { type: 'value', name: '合格率%', max: 100, axisLabel: { formatter: '{value}' }, splitLine: { lineStyle: { color: '#efebe1' } } },
      { type: 'value', name: '待复核', splitLine: { show: false } }
    ],
    series: [
      {
        name: '合格率', type: 'line', smooth: true, yAxisIndex: 0,
        data: t.map((p) => p.qualifiedRate),
        itemStyle: { color: '#3d5a4c' }, areaStyle: { color: 'rgba(61,90,76,0.10)' }
      },
      {
        name: '待复核数', type: 'line', smooth: true, yAxisIndex: 1,
        data: t.map((p) => p.pendingReview),
        itemStyle: { color: '#96714f' }, lineStyle: { type: 'dashed' }
      }
    ]
  })
}

const renderDist = () => {
  if (!distRef.value) return
  if (!distChart || distChart.getDom() !== distRef.value) {
    if (distChart) distChart.dispose()
    distChart = echarts.init(distRef.value)
  }
  const d = extra.value.scoreDistribution
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

const renderPie = () => {
  if (!pieRef.value) return
  if (!pieChart || pieChart.getDom() !== pieRef.value) {
    if (pieChart) pieChart.dispose()
    pieChart = echarts.init(pieRef.value)
  }
  pieChart.setOption({
    color: PIE_COLORS,
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { orient: 'vertical', right: 10, top: 'center', itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 13 } },
    series: [
      {
        type: 'pie',
        radius: ['52%', '78%'],
        center: ['40%', '50%'],
        label: { show: false },
        data: patternDist.value.map((s) => ({ name: s.pattern, value: s.count }))
      }
    ]
  })
}

const params = () => ({
  department: filter.department || '',
  start: filter.start || '',
  end: filter.end || '',
  pattern: ''
})

const loadAll = async () => {
  loading.value = true
  try {
    const [all, ex] = await Promise.all([getAllStats(params()), getExtraStats(params())])
    overview.value = all.data.overview
    diseaseStats.value = (all.data.disease.statistics || []).slice(0, 10)
    symptomStats.value = (all.data.symptom.statistics || []).slice(0, 10)
    patternDist.value = (all.data.pattern.distribution || []).slice(0, 6)
    formulaStats.value = (all.data.prescription.formulaStats || []).slice(0, 5)
    herbStats.value = (all.data.prescription.herbStats || []).slice(0, 5)
    extra.value = ex.data

    // 待治理（仅管理员可读治理统计）
    if (userStore.role === '管理员') {
      try {
        const g = await governanceStats()
        govern.pendingGovern = g.data.pendingGovern ?? 0
      } catch {
        govern.pendingGovern = 0
      }
    }
  } catch {
    // 拦截器已提示，这里只保证 loading 收口
  } finally {
    await nextTick()
    renderTrend()
    renderDist()
    renderPie()
    loading.value = false
  }
}

const resetFilter = () => {
  filter.department = ''
  filter.start = ''
  filter.end = ''
  loadAll()
}

onMounted(() => {
  loadAll()
  window.addEventListener('resize', handleResize)
})

const handleResize = () => {
  if (trendChart) trendChart.resize()
  if (distChart) distChart.resize()
  if (pieChart) pieChart.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  ;[trendChart, distChart, pieChart].forEach((c) => c && c.dispose())
  trendChart = distChart = pieChart = null
})
</script>

<style scoped>
.todo-bar {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
.todo {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 12px 18px;
  cursor: pointer;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}
.todo:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(47, 70, 57, 0.1);
}
.todo-num {
  font-size: 22px;
  font-weight: bold;
  color: var(--ink);
  line-height: 1.2;
}
.todo.warn .todo-num { color: var(--ochre); }
.todo-lbl {
  font-size: 12.5px;
  color: var(--text-sub);
  margin-top: 2px;
}
.stats {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.grid-2 :deep(.panel) {
  margin-bottom: 0;
}
.mb {
  margin-bottom: 14px;
}
.chart {
  width: 100%;
  height: 260px;
}
.chart-tall {
  width: 100%;
  height: 280px;
}
.dual {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.dual-hd {
  font-size: 12.5px;
  color: var(--text-sub);
  margin-bottom: 8px;
}
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
.rate-name {
  width: 72px;
  text-align: right;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
}
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
.dict-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
}
.dict-item {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 12px;
  text-align: center;
}
.dict-num {
  font-size: 22px;
  font-weight: bold;
  color: var(--ink);
}
.dict-lbl {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 4px;
}
@media (max-width: 1200px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
  .todo-bar {
    grid-template-columns: 1fr;
  }
  .dict-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
