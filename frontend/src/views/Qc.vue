<template>
  <div>
    <!-- 范围查询提升为整页生效（第八轮）：此前 RangeFilter 只喂图谱，
         而「AI 预检列表」另挂一个独立的分级下拉，同一页存在两个互不相干的范围口径 -->
    <PanelCard title="范围查询">
      <div class="filter-bar">
        <RangeFilter v-model="filters" />
        <el-button type="primary" size="small" :loading="queryLoading" @click="applyFilters">查 询</el-button>
        <el-button size="small" :disabled="queryLoading" @click="resetFilters">重置</el-button>
        <span class="tip">范围对本页各块同时生效：评分标准 / 扣分构成 / 规则证据 / AI 预检列表</span>
      </div>
    </PanelCard>

    <!-- 质控评分标准（批P）：把写死的口径可视化，先让用户懂"标准" -->
    <PanelCard title="质控评分标准">
      <el-collapse v-model="standardOpen">
        <el-collapse-item name="std">
          <template #title>
            <span class="std-title">评分口径 · 分级线 · 逻辑规则</span>
          </template>
          <div v-if="qcRules" class="std-body">
            <div class="std-cols">
              <div class="std-col">
                <div class="sub-hd">评分构成（满分 100，逐项扣分）</div>
                <div class="std-dim">
                  <span class="sd-name">核心要素缺失</span>
                  <span class="sd-desc">症状 / 证候 / 舌象 / 脉象 / 中药</span>
                  <span class="sd-w">真缺失 -{{ qcRules.weights.fullMissing }} ／ 漏抽 -{{ qcRules.weights.partialMissing }}</span>
                </div>
                <div class="std-dim">
                  <span class="sd-name">逻辑冲突</span>
                  <span class="sd-desc">证候-治法 / 证候-方剂 / 舌脉 不一致</span>
                  <span class="sd-w">每条 -{{ qcRules.weights.logicConflict }}</span>
                </div>
                <div class="std-dim">
                  <span class="sd-name">格式错误</span>
                  <span class="sd-desc">年龄非数字 / 性别非男女</span>
                  <span class="sd-w">每项 -{{ qcRules.weights.format }}</span>
                </div>
                <div class="std-dim">
                  <span class="sd-name">重复数据</span>
                  <span class="sd-desc">与已有病历内容完全一致</span>
                  <span class="sd-w">-{{ qcRules.weights.duplicate }}</span>
                </div>
              </div>
              <div class="std-col">
                <div class="sub-hd">分级线</div>
                <div class="std-grade ok">合格：无逻辑冲突且 ≥ {{ qcRules.thresholds.qualified }} 分</div>
                <div class="std-grade mid">待复核：有逻辑冲突，或 {{ qcRules.thresholds.qualified }} 分以下且 ≥ {{ qcRules.thresholds.invalid }} 分</div>
                <div class="std-grade bad">无效：&lt; {{ qcRules.thresholds.invalid }} 分，或核心要素真缺失 ≥ {{ qcRules.thresholds.seriousFullMissing }} 项</div>
                <div class="sub-hd">逻辑规则（可判范围）</div>
                <div v-for="r in qcRules.logicRules" :key="r.pattern" class="rule-card">
                  <b>{{ r.pattern }}</b>
                  <span>治法：{{ r.treatments.join(' / ') }}</span>
                  <span>方剂：{{ r.formulas.join(' / ') }}</span>
                </div>
                <div class="rule-card tongue">
                  <b>舌脉冲突</b>
                  <span v-for="tp in qcRules.tonguePulseConflicts" :key="tp.tongue + tp.pulse">{{ tp.tongue }} × {{ tp.pulse }}</span>
                </div>
                <div class="tip">规则表只覆盖少数证候，未覆盖的不判冲突（空白 ≠ 已核对）。</div>
              </div>
            </div>
          </div>
          <el-empty v-else description="标准加载中…" :image-size="60" />
        </el-collapse-item>
      </el-collapse>
    </PanelCard>

    <!-- 本范围扣分构成（批P）：范围内各病历扣分明细聚合 -->
    <PanelCard title="本范围扣分构成">
      <div v-loading="dedLoading">
        <template v-if="dedStats">
          <div v-if="dedStats.byType.length" class="dist">
            <div v-for="t in dedStats.byType" :key="t.type" class="dist-row">
              <span class="dr-l">{{ t.type }}</span>
              <div class="dr-bar"><i :style="{ width: barWidth(t.points) }"></i></div>
              <span class="dr-v">{{ t.count }} 次 · -{{ t.points }}</span>
            </div>
          </div>
          <div v-else class="ok">本范围内没有扣分项（全部病历未触发任何扣分规则）</div>

          <template v-if="dedStats.byItem.length">
            <div class="sub-hd">Top 扣分项</div>
            <el-table :data="dedStats.byItem" border size="small" max-height="240">
              <el-table-column prop="type" label="类型" width="130" />
              <el-table-column prop="item" label="项" width="110" />
              <el-table-column prop="count" label="次数" width="80" />
              <el-table-column prop="points" label="合计扣分" width="100" />
            </el-table>
          </template>

          <div class="sub-hd">分级分布（扫描 {{ dedStats.scanned }} 份）</div>
          <div class="grade-chips">
            <span v-for="(v, k) in dedStats.gradeDist" :key="k" class="gc">{{ k }} {{ v }}</span>
          </div>
          <div v-if="dedStats.truncated" class="trunc-hint">超出扫描上限，仅统计前 {{ dedStats.scanned }} 份</div>
        </template>
        <el-empty v-else-if="!dedLoading" description="点击上方「查询」查看本范围扣分构成" :image-size="70" />
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

          <!-- 评分构成瀑布（批P）：100 分起逐项扣到最终分，一眼看分扣在哪 -->
          <div class="sd-title">评分构成（100 分起，逐项扣）</div>
          <div class="wf">
            <div class="wf-item"><span class="wf-l">总分</span><b class="wf-num">100</b></div>
            <div v-for="(d, i) in detail.deductions" :key="i" class="wf-item">
              <span class="wf-l">{{ d.type }} · {{ d.item }}</span>
              <b class="wf-num neg">-{{ d.points }}</b>
            </div>
            <div class="wf-item end">
              <span class="wf-l">最终得分</span>
              <b class="wf-num">{{ detail.score }}</b>
              <span class="wf-grade" :class="gradeClass(detail.grade)">{{ detail.grade }}</span>
            </div>
          </div>
          <div class="wf-note">
            合格线 {{ qualified }} 分
            <template v-if="detail.score < qualified">，距合格线还差 {{ qualified - detail.score }} 分</template>
          </div>

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
import { qcScore, getQcRules, getDeductionStats } from '@/api/qc'
import { searchRecords } from '@/api/records'
import { fmtDateTime } from '@/utils/format'

const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })

// ===== 评分标准 / 扣分构成（批P） =====
const qcRules = ref(null)
const standardOpen = ref(['std']) // 默认展开：先让用户看到"标准"
const dedStats = ref(null)
const dedLoading = ref(false)

const loadRules = async () => {
  try {
    const res = await getQcRules()
    qcRules.value = res.data
  } catch {
    // 拦截器已提示
  }
}

/** 标准里的合格线（未加载时退回 90） */
const qualified = computed(() => qcRules.value?.thresholds?.qualified ?? 90)
const gradeClass = (g) => (g === '合格' ? 'is-ok' : g === '无效' ? 'is-bad' : 'is-mid')

const loadDedStats = async () => {
  dedLoading.value = true
  try {
    const res = await getDeductionStats(params())
    dedStats.value = res.data
  } catch {
    // 拦截器已提示
  } finally {
    dedLoading.value = false
  }
}

/** 条形宽度：按最大扣分点数归一 */
const barWidth = (points) => {
  const max = Math.max(1, ...(dedStats.value?.byType || []).map((t) => t.points))
  return Math.round((points / max) * 100) + '%'
}
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

/** 范围查询是整页口径：刷新时各块必须一起走 */
const queryLoading = computed(() => precheckLoading.value || dedLoading.value)
const applyFilters = () => {
  loadPrecheck(1)
  loadDedStats()
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
  loadRules()
  loadPrecheck(1)
  loadDedStats()
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

/* ===== 评分标准面板（批P） ===== */
.std-title {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
}
.std-body {
  padding-top: 4px;
}
.std-cols {
  display: flex;
  gap: 28px;
  align-items: flex-start;
}
.std-col {
  flex: 1;
  min-width: 0;
}
.std-dim {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--line);
  font-size: 12.5px;
}
.std-dim .sd-name {
  font-weight: bold;
  color: var(--ink);
  flex: 0 0 96px;
}
.std-dim .sd-desc {
  flex: 1 1 auto;
  color: var(--text-sub);
}
.std-dim .sd-w {
  flex: 0 0 auto;
  color: var(--ochre);
  font-weight: bold;
}
.std-grade {
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12.5px;
  margin-bottom: 6px;
}
.std-grade.ok {
  background: var(--ink-light);
  color: var(--ink);
}
.std-grade.mid {
  background: var(--ochre-light);
  color: #8a6a44;
}
.std-grade.bad {
  background: #fdf3f1;
  color: #8a3d33;
}
.rule-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 10px;
  margin-bottom: 6px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--ink-mid);
  border-radius: 4px;
  background: var(--paper);
  font-size: 12.5px;
  color: var(--ink);
}
.rule-card.tongue {
  border-left-color: var(--danger);
}

/* ===== 本范围扣分构成 ===== */
.dist {
  margin-bottom: 8px;
}
.dist-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
  font-size: 12.5px;
}
.dist-row .dr-l {
  flex: 0 0 130px;
  color: var(--ink);
}
.dist-row .dr-bar {
  flex: 1 1 auto;
  height: 12px;
  background: var(--paper);
  border-radius: 6px;
  overflow: hidden;
}
.dist-row .dr-bar i {
  display: block;
  height: 100%;
  background: var(--ochre);
}
.dist-row .dr-v {
  flex: 0 0 130px;
  text-align: right;
  color: var(--text-sub);
}
.grade-chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.grade-chips .gc {
  font-size: 12.5px;
  padding: 3px 10px;
  border: 1px solid var(--line);
  border-radius: 12px;
  color: var(--ink);
}

/* ===== 评分构成瀑布（弹窗内） ===== */
.wf {
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 6px 10px;
  margin-bottom: 8px;
  background: var(--paper);
}
.wf-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 3px 0;
  font-size: 12.5px;
}
.wf-item .wf-l {
  flex: 1 1 auto;
  color: var(--ink);
}
.wf-item .wf-num {
  flex: 0 0 auto;
  color: var(--ink);
}
.wf-item .wf-num.neg {
  color: var(--danger);
}
.wf-item.end {
  border-top: 1px solid var(--line);
  margin-top: 4px;
  padding-top: 6px;
}
.wf-grade {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
}
.wf-grade.is-ok {
  background: var(--ink-light);
  color: var(--ink);
}
.wf-grade.is-mid {
  background: var(--ochre-light);
  color: #8a6a44;
}
.wf-grade.is-bad {
  background: #fdf3f1;
  color: #8a3d33;
}
.wf-note {
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 10px;
}
</style>
