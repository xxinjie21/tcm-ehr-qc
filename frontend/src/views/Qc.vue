<template>
  <div>
    <!-- 范围查询提升为整页生效（第八轮）：此前 RangeFilter 只喂图谱，
         而「AI 预检列表」另挂一个独立的分级下拉，同一页存在两个互不相干的范围口径 -->
    <PanelCard title="范围查询">
      <div class="filter-bar">
        <RangeFilter v-model="filters" />
        <el-button type="primary" size="small" :loading="queryLoading" @click="applyFilters">查 询</el-button>
        <el-button size="small" :disabled="queryLoading" @click="resetFilters">重置</el-button>
        <span class="tip">范围对本页两块同时生效：质控检验图谱 + AI 预检列表</span>
      </div>
    </PanelCard>

    <PanelCard title="质控检验图谱">
      <div class="qc-filter">
        <div class="qc-actions">
          <!-- 默认画全量关系图（UX-72）：演示数据的证候都不在 LogicChecker.RULES 覆盖范围内，
               规则边与冲突边恒为 0，默认「冲突定位」会让画布与空态都不渲染 -->
          <el-radio-group v-model="graphMode" size="small">
            <el-radio-button value="full">全量关系</el-radio-button>
            <el-radio-button value="conflict">冲突定位</el-radio-button>
          </el-radio-group>
          <!-- 说明「这张图回答什么问题」，而不只是「怎么算的」（UX-53） -->
          <span class="tip">全量关系图看整体；切到「冲突定位」只留红色虚线（证候与治法/方剂不一致）</span>
        </div>
      </div>

      <div v-if="graph.truncated" class="trunc-hint">{{ graph.hint }}</div>

      <!-- 冲突定位模式下无冲突：直接给结论，不画一张空图 -->
      <div
        v-if="graphMode === 'conflict' && !graphLoading && graph.nodes.length && !conflictEdges.length"
        class="no-conflict"
      >
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
          v-else-if="!graphLoading"
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
        <span class="tip">点击行查看规则扣分明细；范围沿用上方「范围查询」，不再单独设分级</span>
      </div>

      <el-table v-loading="precheckLoading" :data="precheckRows" border size="small" max-height="360">
        <el-table-column prop="id" label="病历ID" width="320" show-overflow-tooltip />
        <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
        <el-table-column prop="grade" label="分级" width="90" />
        <!-- 接诊时间（第八轮）：常态只到日，悬停给秒级原值。
             只到日是有意的 —— 演示数据的时间分量是脱敏噪声（57% 落在非门诊时段，
             会出现凌晨 2 点接诊），常态展示等于把噪声摆在列表上；hover 保留完整精度用于核对 -->
        <el-table-column label="接诊时间" width="110">
          <template #default="{ row }">
            <el-tooltip :content="fmtDateTime(row.visitTime, 'second')" placement="top">
              <span>{{ fmtDateTime(row.visitTime, 'date') }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <!-- 年龄/性别（第八轮）：单块自包含，需回滚时整块删掉即可 ——
             后端两字段是追加、向后兼容，回滚不需要动后端 -->
        <el-table-column label="年龄/性别" width="110">
          <template #default="{ row }">
            <span>{{ [row.age ? row.age + '岁' : '', row.gender].filter(Boolean).join(' / ') || '—' }}</span>
          </template>
        </el-table-column>
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

    <!-- 扣分明细改回弹窗（UX-71）：用户第六轮明确指定用弹窗，属 UX-66 的例外。
         弹窗内改左右两栏（UX-65 第七轮）：原先评分 + 扣分表 + 逻辑冲突纵向叠，
         扣分项一多就要下拉；现左＝评分/分级 + 扣分明细，右＝逻辑冲突，一屏看完 -->
    <el-dialog
      v-model="detailVisible"
      title="规则预检单（扣分明细）"
      width="min(1080px, 94vw)"
      top="7vh"
    >
      <div v-if="detail" class="ded-2col">
        <div class="ded-col">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="评分">{{ detail.score }}</el-descriptions-item>
            <el-descriptions-item label="分级">{{ detail.grade }}</el-descriptions-item>
          </el-descriptions>
          <div class="sd-title">扣分明细（合计 -{{ detailTotal }} 分）</div>
          <el-table :data="detail.deductions" border size="small" max-height="340">
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column prop="item" label="项" width="90" />
            <el-table-column prop="points" label="扣分" width="70" />
            <el-table-column prop="reason" label="原因" show-overflow-tooltip />
            <template #empty><div class="ok">无扣分项</div></template>
          </el-table>
        </div>
        <div class="ded-col">
          <div class="sd-title">逻辑冲突</div>
          <ul v-if="detail.logicConflicts && detail.logicConflicts.length" class="conflicts">
            <li v-for="c in detail.logicConflicts" :key="c">{{ c }}</li>
          </ul>
          <div v-else class="ok">未发现逻辑冲突</div>
        </div>
      </div>
      <template #footer>
        <el-button @click="closeDetail">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import echarts from '@/utils/echarts'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { getGraph, qcScore } from '@/api/qc'
import { searchRecords } from '@/api/records'
import { fmtDateTime } from '@/utils/format'

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

/** 视图模式：全量关系（默认，UX-72）/ 冲突定位（UX-53） */
const graphMode = ref('full')

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
// 分级不再单独持有：统一由上方「范围查询」的 filters.grade 驱动，
// 否则同一页会出现两个互不相干的分级口径（第八轮）
const precheckRows = ref([])
const precheckTotal = ref(0)
const precheckPage = ref(1)
const precheckSize = ref(10)
const precheckLoading = ref(false)

const loadPrecheck = async (p) => {
  if (typeof p === 'number') precheckPage.value = p
  precheckLoading.value = true
  try {
    const res = await searchRecords({ ...filters, page: precheckPage.value, pageSize: precheckSize.value })
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

/** 范围查询是整页口径：刷新时图谱与预检列表必须一起走，不能只刷其中一块 */
const queryLoading = computed(() => graphLoading.value || precheckLoading.value)
const applyFilters = () => {
  loadGraph()
  loadPrecheck(1)
}
const resetFilters = () => {
  filters.department = ''
  filters.dateRange = null
  filters.pattern = ''
  filters.grade = ''
  applyFilters()
}

// 扣分明细改回弹窗（UX-71）；关闭时只收起、不清数据，避免关闭动画期间内容闪空
const detail = ref(null)
const detailVisible = ref(false)

/** 扣分合计：两栏弹窗里直接给出，省得用户在长表里自己加（UX-65 第七轮） */
const detailTotal = computed(() =>
  (detail.value?.deductions || []).reduce((s, d) => s + (d.points || 0), 0)
)

const openDetail = async (recordId) => {
  try {
    const res = await qcScore({ recordId })
    detail.value = res.data
    detailVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

const closeDetail = () => {
  detailVisible.value = false
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
/* 页面级范围查询条（第八轮）：与 RangeFilter 同一行，控件底对齐 */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.qc-filter {
  margin-bottom: 10px;
}
.qc-actions {
  display: flex;
  align-items: center;
  gap: 10px;
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
/* 扣分明细弹窗改左右两栏（UX-65 第七轮）：扣分项一多，纵向叠放就要下拉；
   右栏顶部标题与左栏「扣分明细」对齐，两栏各自独立、互不撑高 */
.ded-2col {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.ded-col {
  min-width: 0;
}
.ded-col .sd-title:first-child {
  margin-top: 0;
}
.ded-col + .ded-col {
  border-left: 1px solid var(--line);
  padding-left: 20px;
}
@media (max-width: 900px) {
  .ded-2col {
    grid-template-columns: 1fr;
  }
  .ded-col + .ded-col {
    border-left: 0;
    padding-left: 0;
  }
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
