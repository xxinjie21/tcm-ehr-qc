<template>
  <!-- 质控页：范围查询（整页口径）+ 评分标准 + 规则配置（仅管理员）+ 扣分构成 + AI 预检列表 + 扣分明细弹窗 -->
  <div>
    <!-- 范围查询提升为整页生效：此前 RangeFilter 只喂图谱，
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

    <!-- 质控评分标准：直接展示自然语言描述（与规则同源） -->
    <PanelCard title="质控评分标准">
      <template #header>
        <span>质控评分标准</span>
        <el-button v-if="isAdmin" link type="primary" class="hd-action" @click="openRules">规则配置</el-button>
      </template>
      <!-- 标准摘要：把当前生效的规则用自然语言摊开，改规则即随之变化（与规则同源） -->
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
          <span class="st-v">{{ rules.consistencySummary || '—' }}</span>
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
      <!-- 完整规则说明收进折叠区：默认只看摘要，需要细节时再展开 -->
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
      <!-- 句子式编辑器：每段就是一句可读的话，直接在句子里改数字 / 选项，不暴露 JSON -->
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

    <!-- 本范围扣分构成：范围内各病历扣分明细聚合 -->
    <PanelCard title="本范围扣分构成">
      <div v-loading="dedLoading">
        <template v-if="dedStats">
          <!-- 按扣分类型聚合：横条长度按最大扣分点数归一（见 barWidth） -->
          <div v-if="dedStats.byType.length" class="dist">
            <div v-for="t in dedStats.byType" :key="t.type" class="dist-row">
              <span class="dr-l">{{ t.type }}</span>
              <div class="dr-bar"><i :style="{ width: barWidth(t.points) }"></i></div>
              <span class="dr-v">{{ t.count }} 份 · -{{ t.points }}</span>
            </div>
          </div>
          <div v-else class="ok">本范围内没有扣分项（全部病历未触发任何扣分规则）</div>

          <!-- 扣分项明细：受影响病历数 + 合计扣分，用于定位主要扣分来源 -->
          <template v-if="dedStats.byItem.length">
            <div class="sub-hd">Top 扣分项</div>
            <el-table :data="dedStats.byItem" border size="small" max-height="240">
              <el-table-column prop="type" label="类型" width="130" />
              <el-table-column prop="item" label="项" width="110" />
              <el-table-column prop="count" label="受影响病历数" width="110" />
              <el-table-column prop="points" label="合计扣分" width="100" />
            </el-table>
          </template>

          <!-- 分级分布；扫描份数可能被上限截断，截断时下方另有提示 -->
          <div class="sub-hd">分级分布（扫描 {{ dedStats.scanned }} 份）</div>
          <div class="grade-chips">
            <span v-for="(v, k) in dedStats.gradeDist" :key="k" class="gc">{{ k }} {{ v }}</span>
          </div>
          <div v-if="dedStats.truncated" class="trunc-hint">超出扫描上限，仅统计前 {{ dedStats.scanned }} 份</div>
        </template>
        <el-empty v-else-if="!dedLoading" description="点击上方「查询」查看本范围扣分构成" :image-size="70" />
      </div>
    </PanelCard>

    <!-- AI 预检列表：与「病历数据」共用同一套 searchRecords 查询，扣分范围沿用上方筛选 -->
    <PanelCard title="AI 预检列表 / 扣分明细">
      <div class="precheck-bar">
        <span class="tip">点击行查看规则扣分明细；扣分范围沿用上方「范围查询」</span>
      </div>

      <!-- max-height 360：表头 32 + 10 行 × 32 + 余量，表格内部滚动 -->
      <el-table v-loading="precheckLoading" :data="precheckRows" border size="small" max-height="360">
        <el-table-column prop="id" label="病历ID" width="320" show-overflow-tooltip />
        <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
        <el-table-column prop="grade" label="分级" width="90" />
        <!-- 接诊时间：常态只到日，悬停给秒级原值。
             只到日是有意的 —— 演示数据的时间分量是脱敏噪声（57% 落在非门诊时段，
             会出现凌晨 2 点接诊），常态展示等于把噪声摆在列表上；hover 保留完整精度用于核对 -->
        <el-table-column label="接诊时间" width="110">
          <template #default="{ row }">
            <el-tooltip :content="fmtDateTime(row.visitTime, 'second')" placement="top">
              <span>{{ fmtDateTime(row.visitTime, 'date') }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <!-- 年龄/性别：单块自包含，需回滚时整块删掉即可 ——
             后端两字段是追加、向后兼容，回滚不需要动后端 -->
        <el-table-column label="年龄/性别" width="110">
          <template #default="{ row }">
            <span>{{ [row.age ? row.age + '岁' : '', row.gender].filter(Boolean).join(' / ') || '—' }}</span>
          </template>
        </el-table-column>
        <!-- 操作列固定在右侧：表格横向滚动时始终可见 -->
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">扣分明细</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <!-- 文案与「病历数据」「结构化解析」两页统一：这张表就是同一套 searchRecords 查询，
               原先只写「无数据」，用户不知道是没查到、还是页面坏了 -->
          <EmptyState :failed="precheckFailed" :loading="precheckLoading"
            text="筛选范围内没有病历" @retry="() => loadPrecheck(1)" />
        </template>
      </el-table>
      <!-- 分页：切换每页条数时回到第 1 页（见 handleSizeChange） -->
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

    <!-- 扣分明细弹窗：评分/分级 + 评分构成瀑布 + 扣分明细表 -->
    <el-dialog
      v-model="detailVisible"
      title="规则预检单（扣分明细）"
      width="min(1080px, 94vw)"
      top="7vh"
    >
      <!-- 弹窗内容单栏：评分 / 分级 → 评分构成瀑布 → 扣分明细表 -->
      <div v-if="detail">
        <div class="ded-col">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="评分">{{ detail.score }}</el-descriptions-item>
            <el-descriptions-item label="分级">{{ detail.grade }}</el-descriptions-item>
          </el-descriptions>

          <!-- 评分构成瀑布：100 分起逐项扣到最终分，一眼看分扣在哪 -->
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
            <el-table-column prop="points" label="扣分" width="70">
              <template #default="{ row }">-{{ row.points }}</template>
            </el-table-column>
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
// 质控页：范围查询是整页口径 —— 一次「查询」同时刷新「扣分构成」与「AI 预检列表」两块。
// 管理员另有「规则配置」弹窗，保存后规则立即生效，无需重启后端。
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { qcScore, getQcRules, getDeductionStats, updateQcRules, resetQcRules, recomputeQc } from '@/api/qc'
import { searchRecords } from '@/api/records'
import { getTerms } from '@/api/dictionary'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'

// 仅管理员可改规则（与后端 @RequireRole 一致）
const userStore = useUserStore()
// 规则配置入口与后端写接口都要求管理员，前端据此隐藏无效入口
const isAdmin = computed(() => userStore.role === '管理员')

// 整页共用的筛选条件，由上方 RangeFilter 通过 v-model 维护
const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })

// ===== 评分标准 / 扣分构成=====
// 评分标准：rules 为当前生效规则，descriptions 为自然语言说明，catalog* 为可选项目录
const rules = ref(null)
const ruleWarnings = ref([])
const descriptions = ref([])
const catalogElements = ref([])
const catalogFormats = ref([])
const dedStats = ref(null)
const dedLoading = ref(false)
// 词典类型固定五类，供一致性规则的「期望值」下拉使用
const TERM_TYPES = ['disease', 'pattern', 'symptom', 'herb', 'formula']

// 读取当前生效规则与说明文案
const loadRules = async () => {
  try {
    // 1. 拉取当前生效的规则、说明文案与可选项目录
    const res = await getQcRules()
    // 2. 回填规则本体与自然语言说明
    rules.value = res.data?.rules || null
    descriptions.value = res.data?.descriptions || []
    // 3. 回填要素/格式目录与规则告警（供规则配置弹窗使用）
    catalogElements.value = res.data?.catalogElements || []
    catalogFormats.value = res.data?.catalogFormats || []
    ruleWarnings.value = res.data?.warnings || []
  } catch {
    // 拦截器已提示
  }
}

/** 标准里的合格线（未加载时退回 90） */
const qualified = computed(() => rules.value?.thresholds?.qualified ?? 90)
// 分级 → 样式类：合格 / 无效各一色，其余（待复核）走中性色
const gradeClass = (g) => (g === '合格' ? 'is-ok' : g === '无效' ? 'is-bad' : 'is-mid')

// 拉取当前范围的扣分聚合（按类型、按项、分级分布）
const loadDedStats = async () => {
  // 1. 置加载态：本范围扣分构成整块进入 loading
  dedLoading.value = true
  try {
    // 2. 按当前范围拉取扣分聚合（按类型 / 按项 / 分级分布）
    const res = await getDeductionStats(params())
    // 3. 回填聚合结果，供横条图与明细表渲染
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

// 组装接口参数：dateRange 是 [起, 止] 两元素数组，缺任一个都视为未选
const params = () => {
  // 1. 取日期区间（RangeFilter 给出的是 [起, 止] 两元素数组）
  const d = filters.dateRange
  // 2. 组装接口参数：起止缺任一端都按未选处理，空串交给后端忽略
  return {
    department: filters.department || '',
    start: d && d.length === 2 ? d[0] : '',
    end: d && d.length === 2 ? d[1] : '',
    pattern: filters.pattern || '',
    grade: filters.grade || ''
  }
}

// ===== 规则配置（管理员）：句子清单 + 就地编辑 =====
// 规则配置弹窗状态
const rulesVisible = ref(false)
const savingRules = ref(false)
/** 各词典类型的标准词（供一致性"期望值"下拉，仅从词典选） */
const dictTerms = ref({})
// 编辑用的表单副本：由 rules 克隆而来，保存时才组装回写服务端
const form = reactive({
  rules: null,
  elementNames: [],
  fullWeight: 12,
  partialWeight: 6,
  format: [],
  consistency: []
})
// 深拷贝：避免编辑时直接改动 rules（取消后 rules 必须保持原样）
const clone = (o) => JSON.parse(JSON.stringify(o))
// 取某词典类型的标准词列表，供一致性规则的「期望值」下拉；未加载时返回空数组
const termsOf = (type) => dictTerms.value[type] || []

// 拉取五类词典的标准词，供一致性规则的「期望值」下拉（已取过的不重复请求）
const loadDictTerms = async () => {
  // 1. 需要候选的词典类型固定五类
  const types = ['disease', 'pattern', 'symptom', 'herb', 'formula']
  // 2. 并发拉取；已取过的类型直接跳过，避免重复请求
  await Promise.all(types.map(async (type) => {
    if (dictTerms.value[type]) return
    try {
      // 3. 只留标准词，供一致性规则的「期望值」下拉
      const res = await getTerms({ type })
      dictTerms.value[type] = (res.data?.terms || []).map((t) => t.standardTerm).filter(Boolean)
    } catch {
      // 单类词典拉取失败 → 该类候选退化为空数组，不影响其余类型
      dictTerms.value[type] = []
    }
  }))
}

// 打开规则配置：把当前规则克隆进表单，并确保词典候选已就绪
const openRules = async () => {
  // 1. 规则尚未加载时先补拉一次，保证弹窗有内容可编辑
  if (!rules.value) {
    await loadRules()
  }
  // 2. 深拷贝一份进表单：编辑期间不动生效中的 rules，取消即可原样丢弃
  const r = clone(rules.value || {})
  // 3. 回填完整性要素：名称清单 + 完全缺失 / 未结构化两档权重
  const els = r.completeness?.elements || []
  form.elementNames = els.map((e) => e.name)
  form.fullWeight = els[0]?.weightFull ?? 12
  form.partialWeight = els[0]?.weightPartial ?? 6
  // 4. 回填格式规则，补齐字段默认值以便直接编辑
  form.format = (r.format || []).map((f) => ({
    field: f.field, type: f.type || 'regex', expr: f.expr || '', values: f.values || [],
    label: f.label, weight: f.weight ?? 5, reason: f.reason
  }))
  // 5. 回填一致性规则，同样补齐默认值
  form.consistency = (r.consistency || []).map((c) => ({
    name: c.name,
    triggerType: c.triggerType || 'pattern',
    triggerValues: c.triggerValues || [],
    expectType: c.expectType || 'herb',
    expectValues: c.expectValues || [],
    weight: c.weight ?? 10
  }))
  // 6. 回填标准化 / 重复扣分 / 分级阈值
  form.rules = {
    standardization: r.standardization || { enabled: true, elementTypes: TERM_TYPES, weightEach: 1, cap: 5 },
    duplicateWeight: r.duplicateWeight ?? 5,
    thresholds: r.thresholds || { qualified: 90, invalid: 60, seriousFullMissing: 3 }
  }
  // 7. 打开弹窗，并预热词典候选（供「期望值」下拉）
  rulesVisible.value = true
  loadDictTerms()
}

// 按 field 查一条格式规则
/** 格式：模板勾选即用（无需写正则）；非模板项作为历史自定义规则展示 */
const fmtOf = (field) => form.format.find((f) => f.field === field)
// 非模板格式规则（历史遗留或手工添加）：不在 catalogFormats 目录里的项，单独列出供编辑
const customFormats = computed(() => form.format.filter((f) => !catalogFormats.value.some((t) => t.field === f.field)))
// 勾选 / 取消格式模板：勾选即按模板补一条规则，取消则移除
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
// 修改某条格式规则的扣分权重
const setFmtWeight = (field, v) => {
  const f = fmtOf(field)
  if (f) f.weight = v
}
// 删除非模板的历史自定义格式规则（入参是 customFormats 的下标，需换算回 form.format）
const removeCustomFormat = (i) => {
  const target = customFormats.value[i]
  const idx = form.format.indexOf(target)
  if (idx >= 0) form.format.splice(idx, 1)
}
// 新增一条空白的一致性规则
const addConsistency = () => {
  form.consistency.push({
    name: '自定义规则', triggerType: 'pattern', triggerValues: [],
    expectType: 'herb', expectValues: [], weight: 10
  })
}

// 保存规则：把表单组装成后端结构后提交，成功后就地刷新页面上的标准与说明
const saveRules = async () => {
  // 1. 置保存态：按钮转圈，避免重复提交
  savingRules.value = true
  try {
    // 2. 组装完整性要素：按目录补齐来源与兜底别名，权重取表单统一值
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
    // 3. 组装一致性规则：起止值没填全的整条丢弃，不提交半成品
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
    // 4. 组装完整规则对象（完整性 / 格式 / 一致性 / 标准化 / 重复 / 阈值）
    const payload = {
      completeness: { elements },
      format: clone(form.format),
      consistency,
      standardization: clone(form.rules.standardization),
      duplicateWeight: form.rules.duplicateWeight,
      thresholds: clone(form.rules.thresholds)
    }
    // 5. 提交后端，成功后就地刷新标准与说明，无需重进页面
    const res = await updateQcRules(payload)
    rules.value = res.data?.rules || rules.value
    descriptions.value = res.data?.descriptions || descriptions.value
    ruleWarnings.value = res.data?.warnings || []
    // 6. 提示并收起弹窗
    ElMessage.success('规则已保存并生效')
    rulesVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    // 无论成败都复位保存态，否则按钮会一直转圈
    savingRules.value = false
  }
}

// 恢复默认规则：二次确认后调后端重置
const resetRules = async () => {
  try {
    // 1. 二次确认：重置会覆盖当前自定义规则，取消即整体中止
    await ElMessageBox.confirm('确定恢复默认质控规则吗？当前自定义规则将被覆盖。', '恢复默认', { type: 'warning' })
  } catch {
    // 用户点取消 → 直接返回，不发重置请求
    return
  }
  try {
    // 2. 调后端恢复默认规则
    const res = await resetQcRules()
    // 3. 就地刷新规则、说明与告警
    rules.value = res.data?.rules || null
    descriptions.value = res.data?.descriptions || []
    ruleWarnings.value = res.data?.warnings || []
    // 4. 提示成功并收起弹窗
    ElMessage.success('已恢复默认规则')
    rulesVisible.value = false
  } catch {
    // 拦截器已提示
  }
}

// ===== 预检列表 / 扣分明细 =====
// 分级不再单独持有：统一由上方「范围查询」的 filters.grade 驱动，
// 否则同一页会出现两个互不相干的分级口径
// 预检列表状态
const precheckRows = ref([])
const precheckTotal = ref(0)
const precheckPage = ref(1)
const precheckSize = ref(10)
const precheckLoading = ref(false)
/** 预检列表加载失败：与「范围内确实没有病历」区分开（三态统一） */
const precheckFailed = ref(false)

// 加载预检列表；传数字即跳到该页
const loadPrecheck = async (p) => {
  // 1. 入参是页码数字时先跳页（分页组件切换时会带上页码）
  if (typeof p === 'number') precheckPage.value = p
  // 2. 置加载态，并清掉上一次的失败标记
  precheckLoading.value = true
  precheckFailed.value = false
  try {
    // 3. 按当前范围 + 分页参数拉取预检列表
    const res = await searchRecords({ ...filters, page: precheckPage.value, pageSize: precheckSize.value })
    // 4. 回填列表与总数
    precheckRows.value = res.data?.records || []
    precheckTotal.value = res.data?.total || 0
  } catch {
    // 失败态与「范围内确实没有病历」区分开，空态据此给重试入口
    precheckFailed.value = true
    // 拦截器已提示
  } finally {
    // 无论成败都复位加载态
    precheckLoading.value = false
  }
}

// 每页条数变化回到第 1 页
const handleSizeChange = () => {
  precheckPage.value = 1
  loadPrecheck()
}

/** 范围查询是整页口径：刷新时各块必须一起走 */
// 两块数据任意一块在加载，查询按钮就处于 loading
const queryLoading = computed(() => precheckLoading.value || dedLoading.value)
// 应用筛选：两块一起刷新（整页口径）
const applyFilters = () => {
  loadPrecheck(1)
  loadDedStats()
}
// 重置筛选并立即重新查询
const resetFilters = () => {
  filters.department = ''
  filters.dateRange = null
  filters.pattern = ''
  filters.grade = ''
  applyFilters()
}

// 质控评分计算：按当前范围重算评分与分级
const recomputing = ref(false)
// 质控评分计算：二次确认后按当前范围重算评分与分级，完成后刷新两块数据
const handleRecompute = async () => {
  try {
    // 1. 二次确认：重算会覆盖现有分数，取消即整体中止
    await ElMessageBox.confirm(
      '将按质控规则重算当前筛选范围内病历的评分与分级（覆盖现有分数），确认？',
      '质控评分计算',
      { type: 'warning', confirmButtonText: '确认重算', cancelButtonText: '取消' }
    )
  } catch {
    // 用户点取消 → 直接返回，不触发重算
    return
  }
  // 2. 置重算态：按钮进入 loading，避免重复触发
  recomputing.value = true
  try {
    // 3. 按当前范围提交重算
    const res = await recomputeQc({ filters: { ...filters } })
    // 4. 取结果数并一次性告知用户（合格 / 待复核 / 无效 / 失败）
    const d = res.data || {}
    ElMessage.success(`重算完成：合格 ${d.qualified}，待复核 ${d.pendingReview}，无效 ${d.invalid}，失败 ${d.failed}`)
    // 5. 评分已变 → 两块数据同步刷新（整页口径）
    loadPrecheck(1)
    loadDedStats()
  } catch {
    // 拦截器已提示
  } finally {
    // 无论成败都复位，避免按钮卡在 loading
    recomputing.value = false
  }
}

// 扣分明细改回弹窗；关闭时只收起、不清数据，避免关闭动画期间内容闪空
const detail = ref(null)
const detailVisible = ref(false)

// 扣分合计：弹窗里直接给出，省得用户在长表里自己加
/** 扣分合计：两栏弹窗里直接给出，省得用户在长表里自己加*/
const detailTotal = computed(() =>
  (detail.value?.deductions || []).reduce((s, d) => s + (d.points || 0), 0)
)

// 打开扣分明细：按病历 ID 单独取一次评分结果
const openDetail = async (recordId) => {
  try {
    // 1. 按病历 ID 单独取一次评分结果
    const res = await qcScore({ recordId })
    // 2. 回填明细数据（分数 / 分级 / 扣分列表）
    detail.value = res.data
    // 3. 打开弹窗
    detailVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

// 只收起弹窗、不清 detail，避免关闭动画期间内容闪空
const closeDetail = () => {
  detailVisible.value = false
}

// 进页面：规则 + 预检列表 + 扣分构成并行拉取
onMounted(() => {
  loadRules()
  loadPrecheck(1)
  loadDedStats()
})
</script>

<style scoped>
/* 页面级范围查询条：与 RangeFilter 同一行，控件底对齐 */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
/* 次级说明文字 */
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
}
/* 截断 / 告警提示条：浅黄底，与错误红区分 */
.trunc-hint {
  background: #fdf6e8;
  border: 1px solid #ecd9b0;
  color: #96714f;
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12.5px;
  margin-bottom: 8px;
}
/* 预检列表上方说明行 */
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
/* 弹窗内的小节标题 */
.sd-title {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  margin: 14px 0 8px;
}
/* 扣分明细弹窗的内容列 */
.ded-col {
  min-width: 0;
}
.ded-col .sd-title:first-child {
  margin-top: 0;
}
/* 「无扣分项」等正向文案 */
.ok {
  padding: 8px;
  color: var(--ink-mid);
  font-size: 12.5px;
}
/* 面板内二级标题（左竖线） */
.sub-hd {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: 8px;
}

/* ===== 评分标准面板===== */
/* 以下 .std-title / .std-body / .std-cols / .std-col / .std-dim / .std-grade / .rule-card
   为旧版标准面板样式；当前模板已改用 .std-grid / .st / .chip，这些类暂无引用（保留待清理） */
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
/* 按类型的扣分分布 */
.dist {
  margin-bottom: 8px;
}
/* 单行：类型名 + 横条 + 数值 */
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
/* 分级分布：胶囊标签 */
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
/* 瀑布单行：标签 + 数值（扣分用 danger） */
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
/* 最终得分旁的分级胶囊 */
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
/* 合格线说明 */
.wf-note {
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 10px;
}

/* ===== 规则配置弹窗 ===== */
.hd-action {
  margin-left: 12px;
}
/* 折叠区内的单条规则说明 */
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
/* 一行：标签固定宽 + 值自适应 */
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
/* 要素标签（描边胶囊） */
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
/* 完整说明的折叠区 */
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
/* 格式模板胶囊：勾选态（.on）加深边框与底色 */
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
/* 规则配置弹窗主体：超 72vh 内部滚动，页脚按钮始终可见 */
.rc {
  max-height: 72vh;
  overflow-y: auto;
}
/* 弹窗顶部说明 */
.rc-tip {
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 6px;
}
/* 句子式编辑行：文字与控件同行排布，窄屏换行 */
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
/* 一致性规则卡片 */
.rc-block {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 12px;
  margin-bottom: 8px;
  background: var(--paper);
}
/* ①②③④ 分段标题 */
.rc-hd {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: 8px;
}
/* 预留的行式布局：当前模板用的是 .rc-line，本类暂无引用 */
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
