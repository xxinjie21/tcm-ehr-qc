<template>
  <div>
    <!-- 范围查询提升为整页生效（第八轮）：此前 RangeFilter 只喂图谱，
         而「AI 预检列表」另挂一个独立的分级下拉，同一页存在两个互不相干的范围口径 -->
    <PanelCard title="范围查询">
      <div class="filter-bar">
        <RangeFilter v-model="filters" />
        <el-button type="primary" size="small" :loading="queryLoading" @click="applyFilters">查 询</el-button>
        <el-button size="small" :disabled="queryLoading" @click="resetFilters">重置</el-button>
        <el-button type="warning" size="small" :loading="recomputing" @click="handleRecompute">
          {{ recomputing ? '重算执行中…' : '质控评分计算' }}
        </el-button>
        <span class="tip">范围对本页各块同时生效；「质控评分计算」按当前范围重算评分与分级</span>
      </div>
    </PanelCard>

    <!-- 质控评分标准（批R）：直接展示自然语言描述（与规则同源） -->
    <PanelCard title="质控评分标准">
      <template #header>
        <span>质控评分标准</span>
        <el-button v-if="isAdmin" link type="primary" class="hd-action" @click="openRules">规则配置</el-button>
      </template>
      <div v-if="rules" class="std-grid">
        <div class="st">
          <span class="st-k">病历应包含</span>
          <span class="st-v">
            <span v-for="e in rules.completeness.elements" :key="e.name" class="chip">{{ e.name }}</span>
          </span>
        </div>
        <div class="st">
          <span class="st-k">缺失扣分</span>
          <span class="st-v">完全缺失 -{{ rules.completeness.elements[0]?.weightFull ?? 12 }} ／ 未结构化 -{{ rules.completeness.elements[0]?.weightPartial ?? 6 }}</span>
        </div>
        <div class="st">
          <span class="st-k">格式</span>
          <span class="st-v">{{ rules.format.map((f) => f.label || f.field).join('、') || '未配置' }}</span>
        </div>
        <div class="st">
          <span class="st-k">一致性</span>
          <span class="st-v">{{ (rules.consistency || []).length }} 条（证候 → 中药 / 舌象 / 脉象）</span>
        </div>
        <div class="st">
          <span class="st-k">术语标准化</span>
          <span class="st-v">{{ rules.standardization.enabled ? ('开 · 每个 -' + rules.standardization.weightEach + ' 上限 -' + rules.standardization.cap) : '已关闭' }}</span>
        </div>
        <div class="st">
          <span class="st-k">重复</span>
          <span class="st-v">-{{ rules.duplicateWeight }}</span>
        </div>
        <div class="st">
          <span class="st-k">分级</span>
          <span class="st-v">合格 ≥{{ rules.thresholds.qualified }} ／ 无效 &lt;{{ rules.thresholds.invalid }} 或真缺失 ≥{{ rules.thresholds.seriousFullMissing }}</span>
        </div>
      </div>
      <el-collapse v-if="descriptions.length" class="std-detail">
        <el-collapse-item title="查看完整规则说明" name="d">
          <div v-for="(l, i) in descriptions" :key="i" class="std-desc">· {{ l }}</div>
        </el-collapse-item>
      </el-collapse>
      <div v-if="ruleWarnings.length" class="trunc-hint">规则告警：{{ ruleWarnings.join('；') }}</div>
      <el-empty v-if="!rules" description="标准加载中…" :image-size="60" />
    </PanelCard>

    <!-- 规则配置（仅管理员）：句子清单 + 就地编辑，保存即生效 -->
    <el-dialog v-model="rulesVisible" title="规则配置（改完点保存即生效）" width="min(1000px, 96vw)" top="4vh">
      <div v-if="form.rules" class="rc">
        <div class="rc-tip">下面就是当前生效的规则，直接在句子里改即可。</div>

        <div class="rc-hd">① 病历应有这些要素（完整性）</div>
        <div class="rc-line">
          病历应有
          <el-select v-model="form.elementNames" multiple collapse-tags size="small" style="min-width: 320px">
            <el-option v-for="e in catalogElements" :key="e.name" :label="e.name" :value="e.name" />
          </el-select>
          ；完全缺失每项扣
          <el-input-number v-model="form.fullWeight" size="small" :min="0" :controls="false" />
          分，仅有原始记录每项扣
          <el-input-number v-model="form.partialWeight" size="small" :min="0" :controls="false" />
          分。
        </div>

        <div class="rc-hd">② 格式检查（勾选即可，无需填写规则）</div>
        <div class="rc-flow">
          <div v-for="t in catalogFormats" :key="t.field" class="rc-fmt" :class="{ on: !!fmtOf(t.field) }">
            <el-checkbox :model-value="!!fmtOf(t.field)" @change="(v) => toggleFormat(t, v)" />
            <span class="rc-fmt-l">{{ t.label }}</span>
            <template v-if="fmtOf(t.field)">
              不合规扣
              <el-input-number
                :model-value="fmtOf(t.field).weight"
                size="small"
                :min="0"
                :controls="false"
                @update:model-value="(v) => setFmtWeight(t.field, v)"
              />
              分
            </template>
          </div>
        </div>
        <div v-for="(f, i) in customFormats" :key="i" class="rc-line">
          【{{ f.label || f.field }}】不合规扣
          <el-input-number v-model="f.weight" size="small" :min="0" :controls="false" />
          分
          <el-button link type="danger" @click="removeCustomFormat(i)">删</el-button>
        </div>

        <div class="rc-hd">③ 一致性规则（触发类型 → 期望类型，期望值取自词典）</div>
        <div v-for="(c, i) in form.consistency" :key="i" class="rc-block">
          <div class="rc-line">
            若
            <el-select v-model="c.triggerType" size="small" style="width: 120px">
              <el-option v-for="t in catalogElements" :key="t.typeKey || t.source" :label="t.name" :value="t.typeKey || t.source" />
            </el-select>
            含
            <el-select v-model="c.triggerValues" multiple filterable allow-create collapse-tags size="small" style="min-width: 220px">
              <el-option v-for="v in termsOf(c.triggerType)" :key="v" :label="v" :value="v" />
            </el-select>
          </div>
          <div class="rc-line">
            则
            <el-select v-model="c.expectType" size="small" style="width: 120px">
              <el-option v-for="t in catalogElements" :key="t.typeKey || t.source" :label="t.name" :value="t.typeKey || t.source" />
            </el-select>
            应为
            <el-select v-model="c.expectValues" multiple filterable collapse-tags size="small" style="min-width: 220px">
              <el-option v-for="v in termsOf(c.expectType)" :key="v" :label="v" :value="v" />
            </el-select>
            冲突扣
            <el-input-number v-model="c.weight" size="small" :min="0" :controls="false" />
            分
            <el-button link type="danger" @click="form.consistency.splice(i, 1)">删</el-button>
          </div>
        </div>
        <el-button size="small" @click="addConsistency">+ 添加一致性规则</el-button>

        <div class="rc-hd">④ 其它</div>
        <div class="rc-line">
          <el-switch v-model="form.rules.standardization.enabled" />
          术语标准化：未命中词典的每个扣
          <el-input-number v-model="form.rules.standardization.weightEach" size="small" :min="0" :controls="false" />
          分，最多扣
          <el-input-number v-model="form.rules.standardization.cap" size="small" :min="0" :controls="false" />
          分。
        </div>
        <div class="rc-line">
          重复病历扣
          <el-input-number v-model="form.rules.duplicateWeight" size="small" :min="0" :controls="false" />
          分。
        </div>
        <div class="rc-line">
          合格线
          <el-input-number v-model="form.rules.thresholds.qualified" size="small" :min="0" :max="100" :controls="false" />
          分；无效线
          <el-input-number v-model="form.rules.thresholds.invalid" size="small" :min="0" :max="100" :controls="false" />
          分；核心真缺失
          <el-input-number v-model="form.rules.thresholds.seriousFullMissing" size="small" :min="1" :controls="false" />
          项判无效。
        </div>
      </div>
      <template #footer>
        <el-button @click="resetRules">恢复默认</el-button>
        <el-button @click="rulesVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRules" @click="saveRules">保存并生效</el-button>
      </template>
    </el-dialog>

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

    <!-- 扣分明细弹窗（UX-71）：评分/分级 + 评分构成瀑布 + 扣分明细表 -->
    <el-dialog
      v-model="detailVisible"
      title="规则预检单（扣分明细）"
      width="min(1080px, 94vw)"
      top="7vh"
    >
      <div v-if="detail">
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
      </div>
      <template #footer>
        <el-button @click="closeDetail">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { qcScore, getQcRules, getDeductionStats, updateQcRules, resetQcRules, recomputeQc } from '@/api/qc'
import { searchRecords } from '@/api/records'
import { getTerms } from '@/api/dictionary'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.role === '管理员')

const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })

// ===== 评分标准 / 扣分构成（批P/Q） =====
const rules = ref(null)
const ruleWarnings = ref([])
const descriptions = ref([])
const catalogElements = ref([])
const catalogFormats = ref([])
const standardOpen = ref(['std']) // 默认展开：先让用户看到"标准"
const dedStats = ref(null)
const dedLoading = ref(false)
const TERM_TYPES = ['disease', 'pattern', 'symptom', 'herb', 'formula']

const loadRules = async () => {
  try {
    const res = await getQcRules()
    rules.value = res.data?.rules || null
    descriptions.value = res.data?.descriptions || []
    catalogElements.value = res.data?.catalogElements || []
    catalogFormats.value = res.data?.catalogFormats || []
    ruleWarnings.value = res.data?.warnings || []
  } catch {
    // 拦截器已提示
  }
}

/** 标准里的合格线（未加载时退回 90） */
const qualified = computed(() => rules.value?.thresholds?.qualified ?? 90)
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

// ===== 规则配置（管理员）：句子清单 + 就地编辑 =====
const rulesVisible = ref(false)
const savingRules = ref(false)
/** 各词典类型的标准词（供一致性"期望值"下拉，仅从词典选） */
const dictTerms = ref({})
const form = reactive({
  rules: null,
  elementNames: [],
  fullWeight: 12,
  partialWeight: 6,
  format: [],
  consistency: []
})
const clone = (o) => JSON.parse(JSON.stringify(o))
const termsOf = (type) => dictTerms.value[type] || []

const loadDictTerms = async () => {
  const types = ['disease', 'pattern', 'symptom', 'herb', 'formula']
  await Promise.all(types.map(async (type) => {
    if (dictTerms.value[type]) return
    try {
      const res = await getTerms({ type })
      dictTerms.value[type] = (res.data?.terms || []).map((t) => t.standardTerm).filter(Boolean)
    } catch {
      dictTerms.value[type] = []
    }
  }))
}

const openRules = async () => {
  if (!rules.value) {
    await loadRules()
  }
  const r = clone(rules.value || {})
  const els = r.completeness?.elements || []
  form.elementNames = els.map((e) => e.name)
  form.fullWeight = els[0]?.weightFull ?? 12
  form.partialWeight = els[0]?.weightPartial ?? 6
  form.format = (r.format || []).map((f) => ({
    field: f.field, type: f.type || 'regex', expr: f.expr || '', values: f.values || [],
    label: f.label, weight: f.weight ?? 5, reason: f.reason
  }))
  form.consistency = (r.consistency || []).map((c) => ({
    name: c.name,
    triggerType: c.triggerType || 'pattern',
    triggerValues: c.triggerValues || [],
    expectType: c.expectType || 'herb',
    expectValues: c.expectValues || [],
    weight: c.weight ?? 10
  }))
  form.rules = {
    standardization: r.standardization || { enabled: true, elementTypes: TERM_TYPES, weightEach: 1, cap: 5 },
    duplicateWeight: r.duplicateWeight ?? 5,
    thresholds: r.thresholds || { qualified: 90, invalid: 60, seriousFullMissing: 3 }
  }
  rulesVisible.value = true
  loadDictTerms()
}

/** 格式：模板勾选即用（无需写正则）；非模板项作为历史自定义规则展示 */
const fmtOf = (field) => form.format.find((f) => f.field === field)
const customFormats = computed(() => form.format.filter((f) => !catalogFormats.value.some((t) => t.field === f.field)))
const toggleFormat = (t, on) => {
  if (on) {
    if (!fmtOf(t.field)) {
      form.format.push({
        field: t.field, type: t.type || 'regex', expr: t.expr || '', values: clone(t.values || []),
        label: t.label, weight: t.weight ?? 5, reason: t.reason
      })
    }
  } else {
    const i = form.format.findIndex((f) => f.field === t.field)
    if (i >= 0) form.format.splice(i, 1)
  }
}
const setFmtWeight = (field, v) => {
  const f = fmtOf(field)
  if (f) f.weight = v
}
const removeCustomFormat = (i) => {
  const target = customFormats.value[i]
  const idx = form.format.indexOf(target)
  if (idx >= 0) form.format.splice(idx, 1)
}
const addConsistency = () => {
  form.consistency.push({
    name: '自定义规则', triggerType: 'pattern', triggerValues: [],
    expectType: 'herb', expectValues: [], weight: 10
  })
}

const saveRules = async () => {
  savingRules.value = true
  try {
    const elements = form.elementNames.map((name) => {
      const preset = catalogElements.value.find((e) => e.name === name) || {}
      return {
        name,
        source: preset.source || name,
        fallback: preset.fallback || [],
        weightFull: form.fullWeight,
        weightPartial: form.partialWeight
      }
    })
    const consistency = form.consistency
      .filter((c) => (c.triggerValues || []).length && (c.expectValues || []).length)
      .map((c) => ({
        name: c.name || '自定义规则',
        triggerType: c.triggerType,
        triggerValues: c.triggerValues,
        expectType: c.expectType,
        expectValues: c.expectValues,
        weight: c.weight
      }))
    const payload = {
      completeness: { elements },
      format: clone(form.format),
      consistency,
      standardization: clone(form.rules.standardization),
      duplicateWeight: form.rules.duplicateWeight,
      thresholds: clone(form.rules.thresholds)
    }
    const res = await updateQcRules(payload)
    rules.value = res.data?.rules || rules.value
    descriptions.value = res.data?.descriptions || descriptions.value
    ruleWarnings.value = res.data?.warnings || []
    ElMessage.success('规则已保存并生效')
    rulesVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    savingRules.value = false
  }
}

const resetRules = async () => {
  try {
    await ElMessageBox.confirm('确定恢复默认质控规则吗？当前自定义规则将被覆盖。', '恢复默认', { type: 'warning' })
  } catch {
    return
  }
  try {
    const res = await resetQcRules()
    rules.value = res.data?.rules || null
    descriptions.value = res.data?.descriptions || []
    ruleWarnings.value = res.data?.warnings || []
    ElMessage.success('已恢复默认规则')
    rulesVisible.value = false
  } catch {
    // 拦截器已提示
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

// 质控评分计算（批T：从清洗页移来）：按当前范围重算评分与分级
const recomputing = ref(false)
const handleRecompute = async () => {
  try {
    await ElMessageBox.confirm(
      '将按质控规则重算当前筛选范围内病历的评分与分级（覆盖现有分数），确认？',
      '质控评分计算',
      { type: 'warning', confirmButtonText: '确认重算', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  recomputing.value = true
  try {
    const res = await recomputeQc({ filters: { ...filters } })
    const d = res.data || {}
    ElMessage.success(`重算完成：合格 ${d.qualified}，待复核 ${d.pendingReview}，无效 ${d.invalid}，失败 ${d.failed}`)
    loadPrecheck(1)
    loadDedStats()
  } catch {
    // 拦截器已提示
  } finally {
    recomputing.value = false
  }
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
.ded-col {
  min-width: 0;
}
.ded-col .sd-title:first-child {
  margin-top: 0;
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

/* ===== 规则配置弹窗 ===== */
.hd-action {
  margin-left: 12px;
}
.std-desc {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--ink);
  padding: 2px 0;
}
/* 标准：紧凑一行一项（标签 + 值） */
.std-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.st {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 12.5px;
  line-height: 1.8;
}
.st-k {
  flex: 0 0 110px;
  color: var(--text-sub);
}
.st-v {
  flex: 1 1 auto;
  color: var(--ink);
}
.chip {
  display: inline-block;
  margin: 0 6px 2px 0;
  padding: 0 8px;
  font-size: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--ink-light);
  color: var(--ink);
}
.std-detail {
  margin-top: 8px;
  border-top: 1px dashed var(--line);
}
/* 格式模板勾选 */
.rc-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.rc-fmt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border: 1px solid var(--line);
  border-radius: 16px;
  font-size: 12.5px;
  color: var(--text-sub);
}
.rc-fmt.on {
  border-color: var(--ink-mid);
  background: var(--ink-light);
  color: var(--ink);
}
.rc-fmt-l {
  font-weight: bold;
}
.rc {
  max-height: 72vh;
  overflow-y: auto;
}
.rc-tip {
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 6px;
}
.rc-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 8px 0;
  font-size: 12.5px;
  color: var(--ink);
  line-height: 2;
}
.rc-block {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 12px;
  margin-bottom: 8px;
  background: var(--paper);
}
.rc-hd {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: 8px;
}
.rc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
  font-size: 12.5px;
  color: var(--ink);
}
</style>
