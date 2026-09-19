<template>
  <div>
    <PanelCard title="质控检验图谱">
      <div class="qc-filter">
        <RangeFilter v-model="filters" />
        <div class="qc-actions">
          <el-button type="primary" size="small" :loading="graphLoading" @click="loadGraph">刷新图谱</el-button>
          <span class="tip">全库 / 范围内聚合；冲突边为红色虚线</span>
        </div>
      </div>

      <div v-if="graph.truncated" class="trunc-hint">{{ graph.hint }}</div>

      <div v-loading="graphLoading" class="graph-wrap">
        <div v-if="graph.nodes.length" ref="graphRef" class="graph" />
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
import * as echarts from 'echarts'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { getGraph, qcScore } from '@/api/qc'
import { searchRecords } from '@/api/records'

// 9 类实体 + 病历；与后端 GraphVO.type 对齐
const categories = [
  { name: 'record', label: '病历', color: '#2f4639' },
  { name: 'disease', label: '疾病', color: '#3d5a4c' },
  { name: 'pattern', label: '证候', color: '#96714f' },
  { name: 'symptom', label: '症状', color: '#b39a77' },
  { name: 'tongue', label: '舌象', color: '#7a9184' },
  { name: 'pulse', label: '脉象', color: '#8fa0a8' },
  { name: 'formula', label: '方剂', color: '#6b8a79' },
  { name: 'herb', label: '中药', color: '#cdc6b6' },
  { name: 'cause', label: '病因', color: '#a04335' },
  { name: 'treatment', label: '治法', color: '#4d6b58' }
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
  const data = graph.nodes.map((n) => ({
    id: n.id,
    name: n.name,
    category: CAT_INDEX[n.type] ?? 0,
    symbolSize: n.type === 'record' ? 10 : Math.min(28, 7 + (n.size || 1) * 1.6),
    value: n.size
  }))
  const links = graph.edges.map((e) => ({
    source: e.source,
    target: e.target,
    value: e.label || '',
    lineStyle: e.type === 'conflict'
      ? { color: '#c0392b', type: 'dashed', width: 1.6 }
      : e.type === 'rule'
        ? { color: '#96714f', type: 'dotted', width: 1.2 }
        : { color: '#cfd6cf', width: 0.8, curveness: 0.06 }
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
          layout: 'force',
          roam: true,
          draggable: true,
          focusNodeAdjacency: true,
          emphasis: { focus: 'adjacency', label: { show: true } },
          categories: categories.map((c) => ({ name: c.label })),
          label: { show: true, fontSize: 10, position: 'right', color: '#55534c' },
          force: { repulsion: 140, edgeLength: 46, gravity: 0.08 },
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
