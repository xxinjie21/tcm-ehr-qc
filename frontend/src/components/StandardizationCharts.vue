<template>
  <!-- 28.22：质量报告配套图表。
       原先「各类术语归一情况」「未归一实体 TOP」只有 el-table，业务用户要一行行读
       才能看出哪一类是短板、哪几个词最该补。这里把同一份数据画成两张横向条形图，
       放在第一屏（KPI 卡下方），不改动任何数值口径 —— 数据全部由父页传入。

       审查修复（2026-10-08 标准化质量报告前端清单）：
       - P0-1 方案A：左图由「绝对条数堆叠」改为「百分比条」—— 条长 = 归一率，
         与标题声称的指标严格一致；抽取/已归一/未归一移入 tooltip（数值口径不变）。
       - P0-2：两图共用同一份 CHART_BASE（grid / 字号 / 浮层），x=0 起点与右边界对齐。
       - P0-3：y 轴标签按最长标签可用宽度截断（width + overflow: truncate），完整词看 tooltip。
       - P0-4：loading / error / empty 三态互斥，加载与失败不再借用空态文案。 -->
  <PanelCard title="本期图景">
    <div class="sc-grid">
      <div class="sc-cell">
        <div class="sc-hd">
          各类术语归一率
          <span v-if="!loading && !error && covBars.length" class="sc-sub">条长 = 归一率，越短越该先补</span>
        </div>
        <div v-if="loading" class="sc-skel" aria-label="图表加载中" />
        <div v-else-if="error" class="sc-fail">
          <span>报告加载失败，图表暂不可用。</span>
          <el-button size="small" type="primary" @click="emit('retry')">重试</el-button>
        </div>
        <div
          v-else-if="covBars.length"
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
          <!-- P2-4：空态不再渲染「TOP0」 -->
          <span v-if="!loading && !error && topBars.length" class="sc-sub">未归一实体 TOP{{ topBars.length }}（按出现次数）</span>
        </div>
        <div v-if="loading" class="sc-skel" aria-label="图表加载中" />
        <div v-else-if="error" class="sc-fail">
          <span>报告加载失败，图表暂不可用。</span>
          <el-button size="small" type="primary" @click="emit('retry')">重试</el-button>
        </div>
        <div
          v-else-if="topBars.length"
          ref="topRef"
          class="sc-chart"
          role="img"
          :aria-label="topLabel"
        />
        <p v-else class="sc-empty">没有词表缺口 —— 本期未归一里不含「标准词但词表没收录」的条目。</p>
      </div>
    </div>
    <p class="sc-note">
      「未归一」= 抽到了实体但词表里没有对应标准词，其中词表没收录的部分就是
      <b>词表缺口</b>（补词表能直接解决）。逐月对比与完整明细见下方。
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
  unmatched: { type: Object, default: () => ({}) },
  /** P0-4：父页数据加载中（此时不得渲染空态文案） */
  loading: { type: Boolean, default: false },
  /** P0-4：父页接口失败（与 loading、空数据互斥） */
  error: { type: Boolean, default: false }
})

const emit = defineEmits(['retry'])

// ---- 共享图表基线（P0-2）----
// 两图并排同尺寸呈现，grid / 字号 / 浮层必须同源，否则像两张不同规格的图。
// grid.left 取 104：按最长标签（8~9 字）可用 92px + 12px 呼吸位算出，
// 左图标签虽短也用同一个值 —— A3 要求两图 x=0 起点完全对齐。
// grid.bottom 预留 x 轴名称（nameGap 30，名称上下再留余量）。
const CHART_BASE = {
  grid: { left: 104, right: 40, top: 10, bottom: 40, containLabel: false },
  axisFontSize: 13,
  labelColor: '#6b6558',
  labelFontSize: 13,
  // 浮层投影统一成全站的墨绿投影（C3：ECharts 默认是冷灰投影，与页面浮层不是一个色系）
  tooltipCss: 'box-shadow: 0 2px 8px rgba(47,70,57,.12); border-radius: 2px;',
  tooltipFontSize: 13,
  // 条厚占行高比：两图共用。行数不同（左 7 / 右 10）时固定像素会导致
  // 条占行高比相差 19pp（A4 要求 ≤10pp），按比例取厚则两图观感一致。
  barRatio: 0.55
}

/** 按「行高 × 共享比例」算条厚 —— 两图同一公式，条占行高比恒定 */
const rowBarWidth = (chart, rowCount) => {
  if (!chart || !rowCount) return 14
  const plotH = chart.getHeight() - CHART_BASE.grid.top - CHART_BASE.grid.bottom
  const rowH = plotH / rowCount
  return Math.round(rowH * CHART_BASE.barRatio * 10) / 10
}

// ---- 图一：各类型归一率（P0-1 方案A：单一百分比条） ----
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

// 渲染归一率百分比条。容器换了先 dispose 再重建，避免 ECharts 挂在已卸载的 DOM 上
// （与 Dashboard 的 renderTrend/renderDist 同一套策略）。
const renderCov = () => {
  if (!covRef.value) return
  if (!covChart || covChart.getDom() !== covRef.value) {
    if (covChart) covChart.dispose()
    covChart = echarts.init(covRef.value)
  }
  const rows = covBars.value
  covChart.setOption({
    // 两图 tooltip 结构对齐（P1-3）：词 / 值 两段 + marker + 13px（与看板模块同号）
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      textStyle: { fontSize: CHART_BASE.tooltipFontSize },
      extraCssText: CHART_BASE.tooltipCss,
      formatter: (ps) => {
        const r = rows[ps[0].dataIndex]
        const miss = r.total - r.normalized
        // 与右图默认浮层同为「词 / 值」两段结构；抽取、已归一、未归一的绝对数
        // 从图上移入这里 —— 条长只编码归一率（P0-1），口径一个字没改
        return `${r.label}<br/>${ps[0].marker}归一率 ${Math.round(r.rate * 100)}%`
          + `（已归一 ${r.normalized} / 抽取 ${r.total}，未归一 ${miss}）`
      }
    },
    grid: CHART_BASE.grid,
    xAxis: {
      type: 'value',
      max: 100,
      // P0-1：两图都显式给出 x 轴名称与单位
      name: '归一率（%）',
      nameLocation: 'middle',
      nameGap: 30,
      axisLine: { lineStyle: { color: '#d8d2c4' } },
      splitLine: { lineStyle: { color: '#efebe1' } },
      axisLabel: { fontSize: CHART_BASE.axisFontSize }
    },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.label),
      axisLine: { lineStyle: { color: '#d8d2c4' } },
      axisLabel: { color: '#4a4438', fontSize: CHART_BASE.axisFontSize, interval: 0 }
    },
    series: [
      {
        // P0-1 方案A：条长 = 归一率（0~100%）。0% 的「病因」此时是最短的一条，
        // 图形结论与事实一致；绝对条数只在 tooltip 与下方明细表出现。
        name: '归一率', type: 'bar', barWidth: rowBarWidth(covChart, rows.length),
        itemStyle: { color: '#4d6b58' },
        // P1-1：与右图同一套数据标签规则（right / #6b6558 / 13px / 恒显示）
        label: {
          show: true, position: 'right', color: CHART_BASE.labelColor,
          fontSize: CHART_BASE.labelFontSize, fontWeight: 600,
          formatter: (p) => `${Math.round(rows[p.dataIndex].rate * 100)}%`
        },
        data: rows.map((r) => Math.round(r.rate * 100))
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
    // 与图一同一套浮层基线（P1-3）：结构、字号、投影全部同源
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      textStyle: { fontSize: CHART_BASE.tooltipFontSize },
      extraCssText: CHART_BASE.tooltipCss
    },
    grid: CHART_BASE.grid,
    xAxis: {
      type: 'value',
      name: '出现次数（次）',
      nameLocation: 'middle',
      nameGap: 30,
      axisLine: { lineStyle: { color: '#d8d2c4' } },
      splitLine: { lineStyle: { color: '#efebe1' } },
      axisLabel: { fontSize: CHART_BASE.axisFontSize }
    },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.word),
      axisLine: { lineStyle: { color: '#d8d2c4' } },
      // P0-3：grid.left=104 只容得下约 7 个汉字，长词按 92px 截断出省略号，
      // 完整词看悬浮（tooltip 头行 = 类目全名）。interval:0 保证每行都画。
      axisLabel: {
        color: '#4a4438', fontSize: CHART_BASE.axisFontSize, interval: 0,
        width: 92, overflow: 'truncate'
      }
    },
    series: [{
      name: '出现次数', type: 'bar', barWidth: rowBarWidth(topChart, rows.length),
      itemStyle: { color: '#96714f', borderRadius: [0, 3, 3, 0] },
      // P1-1：与左图同一套数据标签规则
      label: {
        show: true, position: 'right', color: CHART_BASE.labelColor,
        fontSize: CHART_BASE.labelFontSize, fontWeight: 600
      },
      data: rows.map((r) => r.count)
    }]
  })
}

const renderAll = () => {
  if (props.loading || props.error) return // 加载 / 失败态不画图，避免把旧数据画在骨架上
  renderCov()
  renderTop()
}

const handleResize = () => {
  if (covChart) covChart.resize()
  if (topChart) topChart.resize()
  // 窄视口断点会改图表高度（P2-7），条厚按行高比例重算
  renderAll()
}

// 数据是异步到的：先 nextTick 等 v-if 的容器挂上再画
watch(() => [props.coverage, props.unmatched, props.loading, props.error],
  () => nextTick(renderAll), { deep: true })
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
/* P0-6：加载态与图表同高同框，数据到达后不产生布局跳动 */
.sc-skel {
  width: 100%;
  height: 260px;
  margin-top: var(--sp-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface-sub);
}
/* P0-4：失败态与空态文案彻底分开，且自带重试出口 */
.sc-fail {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  justify-content: center;
  height: 260px;
  margin-top: var(--sp-2);
  border: 1px dashed var(--line);
  border-radius: 6px;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
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
/* P2-7：窄视口（≤1100px）图表塌为单列后，降高防止页面纵向溢出过长 */
@media (max-width: 1100px) {
  .sc-chart, .sc-skel, .sc-fail { height: 220px; }
}
</style>
