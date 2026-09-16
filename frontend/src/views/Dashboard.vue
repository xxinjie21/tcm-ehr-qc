<template>
  <div v-loading="loading" element-loading-text="数据加载中…">
    <StatsFilter :model="filter" :departments="departments" @search="loadAll" @reset="resetFilter" />

    <div class="stats">
      <StatCard label="病历总数" :value="overview.totalRecords" icon="record" />
      <StatCard label="质控合格率" :value="overview.qualifiedRate" tone="green" suffix="%" icon="rate" />
      <StatCard label="待复核" :value="overview.pendingReviewCount" tone="ochre" icon="pending" />
      <StatCard label="无效数据" :value="overview.invalidCount" tone="red" icon="invalid" />
    </div>

    <div class="grid-2 mb">
      <PanelCard title="疾病频次 TOP10">
        <BarList :items="diseaseStats.map((s) => ({ name: s.disease, value: s.count }))" />
      </PanelCard>
      <PanelCard title="症状频次 TOP10">
        <BarList :items="symptomStats.map((s) => ({ name: s.symptom, value: s.count }))" />
      </PanelCard>
    </div>

    <div class="grid-2">
      <PanelCard title="证候分布">
        <div ref="pieRef" class="pie" />
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
  </div>
</template>

<script setup>
import { reactive, ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import StatsFilter from '@/components/StatsFilter.vue'
import StatCard from '@/components/StatCard.vue'
import BarList from '@/components/BarList.vue'
import PanelCard from '@/components/PanelCard.vue'
import { getOverview, getStats } from '@/api/stats'

const departments = ['内科', '外科', '儿科', '针灸科']

const filter = reactive({ department: '', start: '', end: '' })
const loading = ref(false)

const overview = ref({
  totalRecords: 0,
  qualifiedRate: 0,
  pendingReviewCount: 0,
  invalidCount: 0
})
const diseaseStats = ref([])
const symptomStats = ref([])
const patternDist = ref([])
const formulaStats = ref([])
const herbStats = ref([])

const pieRef = ref(null)
let chart = null

const PIE_COLORS = ['#3d5a4c', '#96714f', '#b39a77', '#7a9184', '#8fa0a8', '#cdc6b6']

const renderPie = () => {
  if (!pieRef.value) return
  if (!chart) {
    chart = echarts.init(pieRef.value)
  }
  chart.setOption({
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

const loadAll = async () => {
  loading.value = true
  try {
    const [ov, dis, sym, pat, pres] = await Promise.all([
      getOverview(),
      getStats({ type: 'disease', filters: buildFilters() }),
      getStats({ type: 'symptom', filters: buildFilters() }),
      getStats({ type: 'pattern', filters: buildFilters() }),
      getStats({ type: 'prescription', filters: buildFilters() })
    ])
    overview.value = ov.data
    diseaseStats.value = (dis.data.statistics || []).slice(0, 10)
    symptomStats.value = (sym.data.statistics || []).slice(0, 10)
    patternDist.value = (pat.data.distribution || []).slice(0, 6)
    formulaStats.value = (pres.data.formulaStats || []).slice(0, 5)
    herbStats.value = (pres.data.herbStats || []).slice(0, 5)
  } finally {
    await nextTick()
    renderPie()
    loading.value = false
  }
}

const buildFilters = () => ({
  department: filter.department || '',
  dateRange: filter.start && filter.end ? [filter.start, filter.end] : [],
  pattern: ''
})

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

const handleResize = () => chart && chart.resize()

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
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
.pie {
  width: 100%;
  height: 260px;
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
</style>