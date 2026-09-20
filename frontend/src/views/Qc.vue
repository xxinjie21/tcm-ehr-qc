<template>
  <div>
    <PanelCard title="质控检验图谱">
      <div class="qc-filter">
        <RangeFilter v-model="filters" />
        <div class="qc-actions">
          <el-button type="primary" size="small" :loading="graphLoading" @click="loadGraph">刷新图谱</el-button>
          <!-- 默认只画冲突定位子图：全量关系图节点数百、散乱难读（UX-53） -->
          <el-radio-group v-model="graphMode" size="small">
            <el-radio-button value="conflict">冲突定位</el-radio-button>
            <el-radio-button value="full">全量关系</el-radio-button>
          </el-radio-group>
          <!-- 说明「这张图回答什么问题」，而不只是「怎么算的」（UX-53） -->
          <span class="tip">用来定位规则冲突：红色虚线为证候与治法/方剂不一致</span>
        </div>
      </div>

      <div v-if="graph.truncated" class="trunc-hint">{{ graph.hint }}</div>

      <!-- 冲突定位模式下无冲突：直接给结论，不画一张空图 -->
      <div v-if="graphMode === 'conflict' && !graphLoading && !conflictEdges.length" class="no-conflict">
        范围内未发现规则冲突（证候与治法 / 方剂一致）。
        <el-button link type="primary" @click="graphMode = 'full'">查看全量关系图</el-button>
      </div>

      <!-- 结论条：直接回答「这张图发现了什么」，而不是只描述怎么算的（UX-53） -->
      <div v-if="visibleGraph.nodes.length" class="graph-summary">
        <span class="gsum"><b>{{ graphSummary.records }}</b> 份病历涉及冲突</span>
        <span class="gsum"><b>{{ graphSummary.nodes }}</b> 个实体节点</span>
        <span class="gsum" :class="{ bad: graphSummary.conflicts > 0 }">
          <b>{{ graphSummary.conflicts }}</b> 处规则冲突
        </span>
        <span class="gsum-hint">
          {{ graphMode === 'full'
            ? '全量关系图用于总览，节点较多；定位冲突请切回「冲突定位」'
            : '红色虚线条就是冲突所在，放大后可看清涉及的证候与治法/方剂' }}
        </span>
      </div>

      <div v-loading="graphLoading" class="graph-wrap">
        <div
          v-if="visibleGraph.nodes.length"
          ref="graphRef"
          class="graph"
          role="img"
          :aria-label="graphLabel"
        />
        <el-empty
          v-else-if="!graphLoading && (graphMode === 'full' || conflictEdges.length)"
          description="范围内暂无可展示的质控图谱"
          :image-size="90"
        />
      </div>

      <div v-if="visibleGraph.nodes.length" class="graph-legend">
        <span v-for="c in visibleCategories" :key="c.name" class="lg">
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

    <!-- 扣分明细：同页展开（UX-66），不再用弹窗遮住上下文 -->
    <PanelCard v-if="detail" ref="detailRef" title="规则预检单（扣分明细）" class="detail-panel">
      <template #header>
        <span>规则预检单（扣分明细）</span>
        <el-button link class="hd-close" @click="closeDetail">关闭详情</el-button>
      </template>
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
    </PanelCard>
  </div>
</template>

<script setup>
import { reactive, ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
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

/** 视图模式：冲突定位（默认）/ 全量关系（UX-53） */
const graphMode = ref('conflict')

const conflictEdges = computed(() => graph.edges.filter((e) => e.type === 'conflict'))

/** 冲突边两端的实体节点 id */
const conflictNodeIds = computed(() => {
  const s = new Set()
  conflictEdges.value.forEach((e) => {
    s.add(e.source)
    s.add(e.target)
  })
  return s
})

/**
 * 实际绘制的图（UX-53）。冲突定位模式只保留「冲突边 + 两端实体」——
 * 节点数从数百降到十余个，图形立刻可读；全量关系图收进「全量关系」二级入口。
 */
const visibleGraph = computed(() => {
  if (graphMode.value === 'full') {
    return { nodes: graph.nodes, edges: graph.edges }
  }
  const ids = conflictNodeIds.value
  if (!ids.size) return { nodes: [], edges: [] }
  return {
    nodes: graph.nodes.filter((n) => ids.has(n.id)),
    edges: graph.edges.filter((e) => ids.has(e.source) && ids.has(e.target))
  }
})

const nodeType = computed(() => {
  const m = new Map()
  graph.nodes.forEach((n) => m.set(n.id, n.type || 'record'))
  return m
})

/** 受冲突影响的病历数：只报数、不画进子图（画进去节点会翻十倍） */
const affectedRecords = computed(() => {
  const ids = conflictNodeIds.value
  if (!ids.size) return 0
  const types = nodeType.value
  const recs = new Set()
  graph.edges.forEach((e) => {
    const ts = types.get(e.source)
    const tt = types.get(e.target)
    if (ts === 'record' && ids.has(e.target)) recs.add(e.source)
    else if (tt === 'record' && ids.has(e.source)) recs.add(e.target)
  })
  return recs.size
})

const visibleCategories = computed(() => {
  const present = new Set(visibleGraph.value.nodes.map((n) => n.type || 'record'))
  return categories.filter((c) => present.has(c.name))
})

const edgeLegend = computed(() => {
  const present = new Set(visibleGraph.value.edges.map((e) => e.type || 'record'))
  return EDGE_LEGENDS.filter((l) => present.has(l.type))
})

// 图谱的文本替代：把关键结论（节点数 / 冲突数）讲成一句话（UX-35）
const graphLabel = computed(() => {
  const v = visibleGraph.value
  if (!v.nodes.length) return '质控图谱，暂无数据'
  const conflicts = v.edges.filter((e) => e.type === 'conflict').length
  return `质控关系图谱：${v.nodes.length} 个节点、${v.edges.length} 条关系，其中冲突 ${conflicts} 条`
})

/** 结论条数据：把图里的规模与冲突数直接摆出来（UX-53） */
const graphSummary = computed(() => ({
  records: affectedRecords.value,
  nodes: visibleGraph.value.nodes.length,
  conflicts: visibleGraph.value.edges.filter((e) => e.type === 'conflict').length
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
  const nodes = visibleGraph.value.nodes
  const byType = new Map()
  nodes.forEach((n) => {
    const t = n.type || 'record'
    if (!byType.has(t)) byType.set(t, [])
    byType.get(t).push(n)
  })
  const sector = (2 * Math.PI) / categories.length
  const R_INNER = 80
  const R_OUTER = 320
  return nodes.map((n) => {
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
  const links = visibleGraph.value.edges.map((e) => ({
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

// 切换视图模式只重画，不必重新请求（同一份数据两种呈现）
watch(graphMode, () => nextTick(renderGraph))

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

// 扣分明细改为同页展开（UX-66）
const detail = ref(null)
const detailRef = ref(null)

const openDetail = async (recordId) => {
  try {
    const res = await qcScore({ recordId })
    detail.value = res.data
    await nextTick()
    detailRef.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch {
    // 拦截器已提示
  }
}

const closeDetail = () => {
  detail.value = null
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
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12.5px;
  margin-bottom: 8px;
}
/* 冲突定位模式下无冲突：直接给结论（UX-53） */
.no-conflict {
  background: var(--ink-light);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px 18px;
  font-size: 13px;
  color: var(--ink);
  margin-bottom: 10px;
}
/* 图谱结论条（UX-53） */
.graph-summary {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
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
/* 详情同页展开（UX-66）：标题吸顶，长内容滚动时关闭入口始终可见 */
.detail-panel :deep(.panel-hd) {
  position: sticky;
  top: 0;
  background: #fff;
  z-index: 2;
}
.hd-close {
  margin-left: auto;
  font-size: 13px;
}
</style>
