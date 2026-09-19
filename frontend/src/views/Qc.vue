<template>
  <div>
    <PanelCard title="质控检验图谱">
      <div class="qc-filter">
        <RangeFilter v-model="filters" />
        <div class="qc-actions">
          <el-button type="primary" size="small" :loading="graphLoading" @click="loadGraph">刷新图谱</el-button>
          <!-- 说明「这张图回答什么问题」，而不只是「怎么算的」（UX-53） -->
          <span class="tip">用来定位规则冲突：红色虚线为证候与治法/方剂不一致；按实体类型分扇区，同色为同类</span>
        </div>
      </div>

      <div v-if="graph.truncated" class="trunc-hint">{{ graph.hint }}</div>

      <!-- 结论条：直接回答「这张图发现了什么」，而不是只描述怎么算的（UX-53） -->
      <div v-if="graph.nodes.length" class="graph-summary">
        <span class="gsum"><b>{{ graphSummary.records }}</b> 份病历</span>
        <span class="gsum"><b>{{ graphSummary.nodes }}</b> 个实体节点</span>
        <span class="gsum" :class="{ bad: graphSummary.conflicts > 0 }">
          <b>{{ graphSummary.conflicts }}</b> 处规则冲突
        </span>
        <span class="gsum-hint">
          {{ graphSummary.conflicts
            ? '红色虚线条就是冲突所在，放大后可看清涉及的证候与治法/方剂'
            : '未发现规则冲突；节点大小代表出现频次，放大可查看实体与病历的关联' }}
        </span>
      </div>

      <div v-loading="graphLoading" class="graph-wrap">
        <div
          v-if="graph.nodes.length"
          ref="graphRef"
          class="graph"
          role="img"
          :aria-label="graphLabel"
        />
        <el-empty v-else-if="!graphLoading" description="范围内暂无可展示的质控图谱" :image-size="90" />
      </div>

      <div v-if="graph.nodes.length" class="graph-legend">
        <span v-for="c in categories" :key="c.name" class="lg">
          <i :style="{ background: c.color }" />{{ c.label }}
        </span>
        <span v-for="l in edgeLegend" :key="l.type" class="lg">
          <i class="line" :class="l.cls" />{{ l.label }}
        </span>
      </div>
    </PanelCard>

    <PanelCard title="AI 预检列表 / 扣分明细">
      <div class="precheck-bar">
        <span>分级</span>
        <el-select v-model="grade" size="small" style="width: 130px" @change="loadPrecheck(1)">
          <el-option label="待复核" value="待复核" />
          <el-option label="无效" value="无效" />
          <el-option label="合格" value="合格" />
        </el-select>
        <span class="tip">点击行查看规则扣分明细</span>
      </div>

      <el-table v-loading="precheckLoading" :data="precheckRows" border size="small" max-height="360">
        <el-table-column prop="id" label="病历ID" width="320" show-overflow-tooltip />
        <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">扣分明细</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="无数据" :image-size="80" />
        </template>
      </el-table>
      <el-pagination
        v-model:current-page="precheckPage"
        v-model:page-size="precheckSize"
        :page-sizes="[10, 20, 50]"
        :total="precheckTotal"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="loadPrecheck"
        @size-change="handleSizeChange"
      />
    </PanelCard>

    <el-dialog v-model="detailVisible" title="规则预检单（扣分明细）" width="min(620px, 92vw)">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="评分">{{ detail.score }}</el-descriptions-item>
          <el-descriptions-item label="分级">{{ detail.grade }}</el-descriptions-item>
        </el-descriptions>
        <div class="sd-title">扣分明细</div>
        <el-table :data="detail.deductions" border size="small" max-height="300">
          <el-table-column prop="type" label="类型" width="110" />
          <el-table-column prop="item" label="项" width="90" />
          <el-table-column prop="points" label="扣分" width="70" />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <template #empty><div class="ok">无扣分项</div></template>
        </el-table>
        <template v-if="detail.logicConflicts && detail.logicConflicts.length">
          <div class="sd-title">逻辑冲突</div>
          <ul class="conflicts">
            <li v-for="c in detail.logicConflicts" :key="c">{{ c }}</li>
          </ul>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import echarts from '@/utils/echarts'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { getGraph, qcScore } from '@/api/qc'
import { searchRecords } from '@/api/records'

// 9 类实体 + 病历；与后端 GraphVO.type 对齐。
// 配色按色相拉开：原方案里 4 类墨绿 + 2 类浅褐，实际只有约 6 种可辨色（UX-53）
const categories = [
  { name: 'record', label: '病历', color: '#2f4639' },
  { name: 'disease', label: '疾病', color: '#6aa84f' },
  { name: 'pattern', label: '证候', color: '#96714f' },
  { name: 'symptom', label: '症状', color: '#e0b44a' },
  { name: 'tongue', label: '舌象', color: '#a04335' },
  { name: 'pulse', label: '脉象', color: '#4a7c9e' },
  { name: 'formula', label: '方剂', color: '#8e6ea8' },
  { name: 'herb', label: '中药', color: '#4f9d8f' },
  { name: 'cause', label: '病因', color: '#b5651d' },
  { name: 'treatment', label: '治法', color: '#7a7a7a' }
]
const CAT_INDEX = categories.reduce((m, c, i) => ({ ...m, [c.name]: i }), {})

/** 边类型图例：只在当前数据里真的出现过的边类型才渲染，避免展示不存在的样式 */
const EDGE_LEGENDS = [
  { type: 'record', cls: '', label: '病历-实体' },
  { type: 'rule', cls: 'rule', label: '证候-治法/方剂' },
  { type: 'conflict', cls: 'conflict', label: '冲突' }
]

const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })
const params = () => {
  const d = filters.dateRange
  return {
    department: filters.department || '',
    start: d && d.length === 2 ? d[0] : '',
    end: d && d.length === 2 ? d[1] : '',
    pattern: filters.pattern || '',
    grade: filters.grade || ''
  }
}

const graph = reactive({ nodes: [], edges: [], truncated: false, hint: '' })
const graphLoading = ref(false)

const edgeLegend = computed(() => {
  const present = new Set(graph.edges.map((e) => e.type || 'record'))
  return EDGE_LEGENDS.filter((l) => present.has(l.type))
})

// 图谱的文本替代：把关键结论（节点数 / 冲突数）讲成一句话（UX-35）
const graphLabel = computed(() => {
  if (!graph.nodes.length) return '质控图谱，暂无数据'
  const conflicts = graph.edges.filter((e) => e.type === 'conflict').length
  return `质控关系图谱：${graph.nodes.length} 个节点、${graph.edges.length} 条关系，其中冲突 ${conflicts} 条`
})

/** 结论条数据：把图里的规模与冲突数直接摆出来（UX-53） */
const graphSummary = computed(() => ({
  records: graph.nodes.filter((n) => n.type === 'record').length,
  nodes: graph.nodes.length,
  conflicts: graph.edges.filter((e) => e.type === 'conflict').length
}))
const graphRef = ref(null)
let chart = null

const loadGraph = async () => {
  graphLoading.value = true
  try {
    const res = await getGraph(params())
    const d = res.data || {}
    graph.nodes = d.nodes || []
    graph.edges = d.edges || []
    graph.truncated = !!d.truncated
    graph.hint = d.hint || ''
  } catch {
    // 拦截器已提示
  } finally {
    await nextTick()
    renderGraph()
    graphLoading.value = false
  }
}

/**
 * 按实体类型固定分区的环形布局（UX-53）。
 *
 * <p>每类实体占一个扇区、同类型节点在扇区内铺开，替换原来的 `layout:'force'` ——
 * 力导向每次刷新图形都不同，既无法对比也无法截图留档，且 50+ 节点时会散成一团。</p>
 *
 * <p>半径用黄金比 `idx * 0.618 % 1` 错开：截断后单类可达 60+ 节点，
 * 固定 3 环会把同扇区节点挤在一起重叠，黄金比能在连续半径上均匀铺开且无需分环。</p>
 */
const layoutNodes = () => {
  const byType = new Map()
  graph.nodes.forEach((n) => {
    const t = n.type || 'record'
    if (!byType.has(t)) byType.set(t, [])
    byType.get(t).push(n)
  })
  const sector = (2 * Math.PI) / categories.length
  const R_INNER = 80
  const R_OUTER = 320
  return graph.nodes.map((n) => {
    const ci = CAT_INDEX[n.type] ?? 0
    const list = byType.get(n.type) || [n]
    const idx = list.indexOf(n)
    const total = list.length
    const ratio = total <= 1 ? 0.5 : idx / (total - 1)
    const angle = ci * sector + 0.08 * sector + ratio * 0.84 * sector
    const r = R_INNER + ((idx * 0.618) % 1) * (R_OUTER - R_INNER)
    return {
      id: n.id,
      name: n.name,
      category: ci,
      symbolSize: n.type === 'record' ? 12 : Math.min(36, 10 + (n.size || 1) * 2.4),
      value: n.size,
      x: Math.cos(angle) * r,
      y: Math.sin(angle) * r
    }
  })
}

const renderGraph = () => {
  if (!graphRef.value) {
    if (chart) {
      chart.dispose()
      chart = null
    }
    return
  }
  if (!chart || chart.getDom() !== graphRef.value) {
    if (chart) chart.dispose()
    chart = echarts.init(graphRef.value)
  }
  const data = layoutNodes()
  const links = graph.edges.map((e) => ({
    source: e.source,
    target: e.target,
    value: e.label || '',
    lineStyle: e.type === 'conflict'
      ? { color: '#c0392b', type: 'dashed', width: 2.6 }
      : e.type === 'rule'
        ? { color: '#96714f', type: 'dotted', width: 1.4 }
        : { color: '#dfe4df', width: 0.8, curveness: 0.06 }
  }))
  chart.setOption(
    {
      color: categories.map((c) => c.color),
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          if (p.dataType === 'edge') {
            return `${p.data.source} → ${p.data.target}${p.data.value ? '<br/>' + p.data.value : ''}`
          }
          return `${p.data.name}${p.data.value ? '（频次 ' + p.data.value + '）' : ''}`
        }
      },
      series: [
        {
          type: 'graph',
          // 坐标由 layoutNodes() 按实体类型预先算好，图形稳定可对比（UX-53）
          layout: 'none',
          roam: true,
          draggable: true,
          focusNodeAdjacency: true,
          emphasis: { focus: 'adjacency', label: { show: true } },
          categories: categories.map((c) => ({ name: c.label })),
          // 标签默认隐藏，hover 或相邻高亮时才出现，避免密集区文字重叠（UX-53）
          label: { show: false, fontSize: 10, position: 'right', color: '#55534c' },
          lineStyle: { color: '#cfd6cf' },
          edgeSymbol: ['none', 'arrow'],
          edgeSymbolSize: 5,
          data,
          links
        }
      ]
    },
    true
  )
}

// ===== 预检列表 / 扣分明细 =====
const grade = ref('待复核')
const precheckRows = ref([])
const precheckTotal = ref(0)
const precheckPage = ref(1)
const precheckSize = ref(10)
const precheckLoading = ref(false)

const loadPrecheck = async (p) => {
  if (typeof p === 'number') precheckPage.value = p
  precheckLoading.value = true
  try {
    const res = await searchRecords({ grade: grade.value, page: precheckPage.value, pageSize: precheckSize.value })
    precheckRows.value = res.data?.records || []
    precheckTotal.value = res.data?.total || 0
  } catch {
    // 拦截器已提示
  } finally {
    precheckLoading.value = false
  }
}

const handleSizeChange = () => {
  precheckPage.value = 1
  loadPrecheck()
}

const detailVisible = ref(false)
const detail = ref(null)
const openDetail = async (recordId) => {
  try {
    const res = await qcScore({ recordId })
    detail.value = res.data
    detailVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

const handleResize = () => chart && chart.resize()

onMounted(() => {
  loadGraph()
  loadPrecheck(1)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.qc-filter {
  margin-bottom: 10px;
}
.qc-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
}
.trunc-hint {
  background: #fdf6e8;
  border: 1px solid #ecd9b0;
  color: #96714f;
  border-radius: 4px;
  padding: 6px 12px;
  font-size: 12.5px;
  margin-bottom: 8px;
}
/* 图谱结论条（UX-53） */
.graph-summary {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 10px 16px;
  margin-bottom: 10px;
}
.gsum {
  font-size: 12.5px;
  color: var(--text-sub);
}
.gsum b {
  font-size: 17px;
  color: var(--ink);
  margin-right: 4px;
}
.gsum.bad b {
  color: var(--danger);
}
.gsum-hint {
  font-size: 12px;
  color: var(--text-sub);
  margin-left: auto;
}
.graph-wrap {
  min-height: 420px;
}
.graph {
  width: 100%;
  height: 520px;
}
.graph-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-sub);
}
.lg {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.lg i {
  display: inline-block;
  width: 11px;
  height: 11px;
  border-radius: 50%;
}
.lg i.line {
  width: 18px;
  height: 0;
  border-top: 2px solid #cfd6cf;
  border-radius: 0;
}
.lg i.line.rule {
  border-top: 2px dotted #96714f;
}
.lg i.line.conflict {
  border-top: 2px dashed #c0392b;
}
.precheck-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.precheck-bar > span:first-child {
  font-size: 13px;
  color: var(--text-sub);
}
.sd-title {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  margin: 14px 0 8px;
}
.conflicts {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--danger);
  line-height: 1.8;
}
.ok {
  padding: 8px;
  color: var(--ink-mid);
  font-size: 12.5px;
}
</style>
