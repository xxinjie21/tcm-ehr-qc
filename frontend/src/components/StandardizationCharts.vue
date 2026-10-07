<template>
  <!-- 28.22：质量报告配套图表。
       原先「各类术语归一情况」「未归一实体 TOP」只有 el-table，业务用户要一行行读
       才能看出哪一类是短板、哪几个词最该补。这里把同一份数据画成两张横向条形图，
       放在第一屏（KPI 卡下方），不改动任何数值口径 —— 数据全部由父页传入。 -->
  <PanelCard title="本期图景">
    <div class="sc-grid">
      <div class="sc-cell">
        <div class="sc-hd">
          各类术语归一率
          <span class="sc-sub">绿=已归一，灰=未归一</span>
        </div>
        <div
          v-if="covBars.length"
          ref="covRef"
          class="sc-chart"
          role="img"
          :aria-label="covLabel"
        />
        <p v-else class="sc-empty">本期没有抽到任何实体，暂无可比数据。</p>
      </div>

      <div class="sc-cell">
        <div class="sc-hd">
          最该先补的词
          <span class="sc-sub">未归一实体 TOP{{ topBars.length }}（按出现次数）</span>
        </div>
        <div
          v-if="topBars.length"
          ref="topRef"
          class="sc-chart"
          role="img"
          :aria-label="topLabel"
        />
        <p v-else class="sc-empty">没有词表缺口 —— 本期未归一里不含「标准词但词表没收录」的条目。</p>
      </div>
    </div>
    <p class="sc-note">
      「未归一」= 抽到了实体但词表里没有对应标准词，其中属于标准词却没收录的部分就是
      <b>词表缺口</b>（补词表能直接解决）。各类型的逐月对比与完整明细见下方「各类术语归一情况」。
    </p>
  </PanelCard>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import PanelCard from '@/components/PanelCard.vue'
import echarts from '@/utils/echarts'

const props = defineProps({
  /** 父页 coverageRows：[{ label, total, normalized, termCount, stale }] */
  coverage: { type: Array, default: () => [] },
  /** 后端 unmatched 口径：{ total, dictionaryGap, top: { 词: 次数 } } */
  unmatched: { type: Object, default: () => ({}) }
})

// ---- 图一：各类型「已归一 / 未归一」堆叠条 ----
// 只画抽到过实体的类型（total > 0）：方剂、治法这类没数据的类型画出来只会占位。
const covBars = computed(() =>
  (props.coverage || [])
    .filter((c) => c.total > 0)
    .map((c) => ({
      label: c.label,
      normalized: c.normalized,
      missing: Math.max(c.total - c.normalized, 0),
      total: c.total,
      rate: c.total ? c.normalized / c.total : 0
    }))
    // 归一率升序：最差的类型排在最上面，一眼看到短板
    .sort((a, b) => a.rate - b.rate)
)

// ---- 图二：未归一实体 TOP-N ----
// 后端给的是 { 实体原文: 次数 }，取前 10。横轴是次数，纵向排列。
const topBars = computed(() =>
  Object.entries(props.unmatched?.top || {})
    .map(([word, count]) => ({ word, count: Number(count) || 0 }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 10)
    .reverse() // 横向条形图从下往上画，反转让次数最多的落在最上面
)

// 图的文本替代：屏幕阅读器与「无法看图」的环境靠它理解图的内容
const covLabel = computed(() =>
  '各类术语归一率条形图：' + covBars.value
    .map((c) => `${c.label} 归一率 ${Math.round(c.rate * 100)}%（已归一 ${c.normalized} / 抽取 ${c.total}）`)
    .join('；')
)
const topLabel = computed(() =>
  '未归一实体条形图：' + [...topBars.value].reverse().map((t) => `${t.word} ${t.count} 次`).join('，')
)

const covRef = ref(null)
const topRef = ref(null)
let covChart = null
let topChart = null

// 渲染归一率堆叠条。容器换了先 dispose 再重建，避免 ECharts 挂在已卸载的 DOM 上
// （与 Dashboard 的 renderTrend/renderDist 同一套策略）。
const renderCov = () => {
  if (!covRef.value) return
  if (!covChart || covChart.getDom() !== covRef.value) {
    if (covChart) covChart.dispose()
    covChart = echarts.init(covRef.value)
  }
  const rows = covBars.value
  covChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      // 浮层投影统一成全站的墨绿投影（C3：ECharts 默认是冷灰投影，与页面浮层不是一个色系）
      extraCssText: 'box-shadow: 0 2px 8px rgba(47,70,57,.12); border-radius: 2px;',
      formatter: (ps) => {
        const r = rows[ps[0].dataIndex]
        const miss = r.total - r.normalized
        return `${r.label}<br/>抽取 ${r.total} 条<br/>已归一 ${r.normalized} 条<br/>未归一 ${miss} 条（${Math.round((miss / r.total) * 100)}%）<br/>归一率 ${Math.round(r.rate * 100)}%`
      }
    },
    // 图例/轴标签 12 → 13，与 --fs-xs 上调到 13 同步（F6：图内文字不再比页面最小字号还小）
    legend: { data: ['已归一', '未归一'], right: 0, top: 0, textStyle: { fontSize: 13 } },
    grid: { left: 56, right: 30, top: 28, bottom: 6, containLabel: false },
    xAxis: { type: 'value', axisLine: { lineStyle: { color: '#d8d2c4' } }, splitLine: { lineStyle: { color: '#efebe1' } } },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.label),
      axisLine: { lineStyle: { color: '#d8d2c4' } },
      axisLabel: { color: '#4a4438', fontSize: 13 }
    },
    series: [
      {
        name: '已归一', type: 'bar', stack: 'c', barWidth: 14,
        itemStyle: { color: '#4d6b58' },
        label: {
          show: true, position: 'insideRight', color: '#fff', fontSize: 12, fontWeight: 600,
          formatter: (p) => (rows[p.dataIndex].rate >= 0.18 ? `${Math.round(rows[p.dataIndex].rate * 100)}%` : '')
        },
        data: rows.map((r) => r.normalized)
      },
      {
        name: '未归一', type: 'bar', stack: 'c', barWidth: 14,
        itemStyle: { color: '#cbb89a' },
        label: {
          // 旧次文本色 #726d63 白底 5.14:1，同色相降明度到 #6b6558 → 5.79:1
          show: true, position: 'right', color: '#6b6558', fontSize: 12, fontWeight: 600,
          formatter: (p) => (rows[p.dataIndex].normalized === 0 ? `${Math.round(rows[p.dataIndex].rate * 100)}%` : '')
        },
        data: rows.map((r) => r.missing)
      }
    ]
  })
}

// 渲染未归一实体 TOP-N：单条横向柱
const renderTop = () => {
  if (!topRef.value) return
  if (!topChart || topChart.getDom() !== topRef.value) {
    if (topChart) topChart.dispose()
    topChart = echarts.init(topRef.value)
  }
  const rows = topBars.value
  topChart.setOption({
    // 与图一同一套浮层投影（C3）
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      extraCssText: 'box-shadow: 0 2px 8px rgba(47,70,57,.12); border-radius: 2px;'
    },
    grid: { left: 76, right: 34, top: 8, bottom: 6 },
    xAxis: { type: 'value', axisLine: { lineStyle: { color: '#d8d2c4' } }, splitLine: { lineStyle: { color: '#efebe1' } } },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.word),
      axisLine: { lineStyle: { color: '#d8d2c4' } },
      axisLabel: { color: '#4a4438', fontSize: 13 }
    },
    series: [{
      type: 'bar', barWidth: 14,
      itemStyle: { color: '#96714f', borderRadius: [0, 3, 3, 0] },
      // 次文本色同步 #726d63 → #6b6558（同上，白底对比度 5.14 → 5.79）
      label: { show: true, position: 'right', color: '#6b6558', fontSize: 12, fontWeight: 600 },
      data: rows.map((r) => r.count)
    }]
  })
}

const renderAll = () => {
  renderCov()
  renderTop()
}

const handleResize = () => {
  if (covChart) covChart.resize()
  if (topChart) topChart.resize()
}

// 数据是异步到的：先 nextTick 等 v-if 的容器挂上再画
watch(() => [props.coverage, props.unmatched], () => nextTick(renderAll), { deep: true })
onMounted(() => {
  nextTick(renderAll)
  window.addEventListener('resize', handleResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (covChart) covChart.dispose()
  if (topChart) topChart.dispose()
  covChart = null
  topChart = null
})
</script>

<style scoped>
.sc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: var(--sp-4);
}
.sc-hd { font-size: var(--fs-base); font-weight: 600; color: var(--ink); }
.sc-sub { margin-left: var(--sp-2); font-size: var(--fs-xs); color: var(--text-sub-strong); font-weight: 400; }
.sc-chart { width: 100%; height: 260px; margin-top: var(--sp-2); }
.sc-empty {
  margin: var(--sp-4) 0;
  text-align: center;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.sc-note {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
.sc-note b { color: var(--ink); }
</style>
