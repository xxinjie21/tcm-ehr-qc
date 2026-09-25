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

    <PanelCard title="质控检验（规则证据）">
      <div class="tip">
        把「规则引擎」的判定证据列出来：证候 → 治法/方剂 是否一致、冲突在哪。
        规则表只覆盖少数证候，<b>未覆盖的不判冲突</b>（空白 ≠ 已核对）。
      </div>

      <div v-if="graph.truncated" class="trunc-hint">{{ graph.hint }}</div>

      <!-- 结论条：先给规模与冲突数，再给明细 -->
      <div v-if="graph.nodes.length" class="graph-summary">
        <span class="gsum"><b>{{ graphSummary.records }}</b> 份病历</span>
        <span class="gsum"><b>{{ graphSummary.covered }}</b> 个证候被规则覆盖</span>
        <span class="gsum"><b>{{ graphSummary.rules }}</b> 条规则命中</span>
        <span class="gsum" :class="{ bad: graphSummary.conflicts > 0 }">
          <template v-if="graph.coveredPatterns.length"><b>{{ graphSummary.conflicts }}</b> 处冲突</template>
          <template v-else>本范围无规则可判</template>
        </span>
      </div>

      <div v-loading="graphLoading">
        <template v-if="graph.nodes.length">
          <div class="sub-hd">冲突清单</div>
          <el-table v-if="conflictRows.length" :data="conflictRows" border size="small">
            <el-table-column prop="reason" label="冲突" min-width="220" show-overflow-tooltip />
            <el-table-column prop="source" label="涉及（证候 / 舌脉）" width="190" show-overflow-tooltip />
            <el-table-column prop="target" label="涉及（治法 / 方剂）" width="190" show-overflow-tooltip />
          </el-table>
          <el-empty
            v-else
            :description="graph.coveredPatterns.length ? '未发现冲突' : '本范围无规则可判，未做冲突判定'"
            :image-size="60"
          />

          <div class="sub-hd">规则命中对照（证候 → 合法治法 / 方剂）</div>
          <el-table v-if="ruleRows.length" :data="ruleRows" border size="small" max-height="320">
            <el-table-column prop="pattern" label="证候" width="160" show-overflow-tooltip />
            <el-table-column prop="kind" label="类型" width="80" />
            <el-table-column prop="target" label="合法项" min-width="160" show-overflow-tooltip />
            <el-table-column prop="reason" label="依据" min-width="160" show-overflow-tooltip />
          </el-table>
          <el-empty v-else description="本范围未命中可判规则" :image-size="60" />

          <div class="sub-hd">规则覆盖情况</div>
          <p class="cover-line">
            已覆盖证候：{{ graph.coveredPatterns.length ? graph.coveredPatterns.join('、') : '无' }}
          </p>
          <p v-if="uncoveredPatterns.length" class="cover-line">
            未覆盖证候（不判冲突）：{{ uncoveredPatterns.join('、') }}
          </p>
        </template>
        <el-empty
          v-else-if="!graphLoading"
          description="范围内暂无可展示的质控证据"
          :image-size="90"
        />
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
          <!-- 文案与「病历数据」「结构化解析」两页统一：这张表就是同一套 searchRecords 查询，
               原先只写「无数据」，用户不知道是没查到、还是页面坏了 -->
          <el-empty description="筛选范围内没有病历" :image-size="80" />
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
          <!-- 空结果不能说成「没问题」：规则表只覆盖少数常见证候，未覆盖的证候按设计不判冲突，
               所以这里空白的成因有两种（判过、确实一致 / 根本没判）。本弹窗只有评分接口的
               logicConflicts，拿不到「本病历是否被规则覆盖」，故文案只说「未报冲突」+ 说明边界，
               不做「一致」的断言（图谱侧有 coveredPatterns，可以判得更细）。 -->
          <div v-else class="ok">
            规则引擎未报冲突
            <span class="tip">规则表只覆盖少数证候，未覆盖的不判冲突；空白 ≠ 已核对</span>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="closeDetail">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { getGraph, qcScore } from '@/api/qc'
import { searchRecords } from '@/api/records'
import { fmtDateTime } from '@/utils/format'

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

// coveredPatterns：本次范围内「被规则表覆盖到」的证候名（见后端 GraphVO 注释）。
const graph = reactive({ nodes: [], edges: [], truncated: false, hint: '', coveredPatterns: [] })
const graphLoading = ref(false)

/** 节点 id → 名称 */
const nameById = computed(() => {
  const m = new Map()
  graph.nodes.forEach((n) => m.set(n.id, n.name))
  return m
})

const conflictEdges = computed(() => graph.edges.filter((e) => e.type === 'conflict'))
const ruleEdges = computed(() => graph.edges.filter((e) => e.type === 'rule'))

/** 冲突清单：冲突原因 + 两端名称 */
const conflictRows = computed(() => conflictEdges.value.map((e) => ({
  reason: e.label || '冲突',
  source: nameById.value.get(e.source) || e.source,
  target: nameById.value.get(e.target) || e.target
})))

/** 规则命中对照：证候 → 合法治法/方剂 */
const ruleRows = computed(() => ruleEdges.value.map((e) => ({
  pattern: nameById.value.get(e.source) || e.source,
  kind: (e.label || '').includes('方剂') ? '方剂' : '治法',
  target: nameById.value.get(e.target) || e.target,
  reason: e.label || ''
})))

/** 范围内出现但规则表未覆盖的证候（不判冲突） */
const uncoveredPatterns = computed(() => {
  const covered = new Set(graph.coveredPatterns)
  return graph.nodes
    .filter((n) => n.type === 'pattern' && !covered.has(n.name))
    .map((n) => n.name)
})

const graphSummary = computed(() => ({
  records: graph.nodes.filter((n) => n.type === 'record').length,
  covered: graph.coveredPatterns.length,
  rules: ruleEdges.value.length,
  conflicts: conflictEdges.value.length
}))

const loadGraph = async () => {
  graphLoading.value = true
  try {
    const res = await getGraph(params())
    const d = res.data || {}
    graph.nodes = d.nodes || []
    graph.edges = d.edges || []
    graph.truncated = !!d.truncated
    graph.hint = d.hint || ''
    graph.coveredPatterns = d.coveredPatterns || []
  } catch {
    // 拦截器已提示
  } finally {
    graphLoading.value = false
  }
}

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

onMounted(() => {
  loadGraph()
  loadPrecheck(1)
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
.sub-hd {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: 8px;
}
.cover-line {
  margin: 4px 0;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--text-sub);
}
</style>
