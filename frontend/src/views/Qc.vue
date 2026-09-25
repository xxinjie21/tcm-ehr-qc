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

    <!-- 质控评分标准（批P/Q）：按可配置规则渲染，先让用户懂"标准" -->
    <PanelCard title="质控评分标准">
      <template #header>
        <span>质控评分标准</span>
        <el-button v-if="isAdmin" link type="primary" class="hd-action" @click="openRules">规则配置</el-button>
      </template>

      <el-collapse v-model="standardOpen">
        <el-collapse-item v-if="rules" name="std">
          <template #title>
            <span class="std-title">评分口径 · 分级线 · 规则</span>
          </template>
          <div class="std-body">
            <div class="std-cols">
              <div class="std-col">
                <div class="sub-hd">完整性（真缺失 / 漏抽）</div>
                <div v-for="e in rules.completeness.elements" :key="e.name" class="std-dim">
                  <span class="sd-name">{{ e.name }}</span>
                  <span class="sd-desc">
                    结构化：{{ e.source }}
                    <template v-if="e.fallback && e.fallback.length"> ｜ 回退：{{ e.fallback.join('/') }}</template>
                  </span>
                  <span class="sd-w">-{{ e.weightFull }} / -{{ e.weightPartial }}</span>
                </div>
                <div class="sub-hd">格式</div>
                <div v-for="f in rules.format" :key="f.field" class="std-dim">
                  <span class="sd-name">{{ f.label || f.field }}</span>
                  <span class="sd-desc">{{ f.type === 'enum' ? ('须为 ' + (f.values || []).join('/')) : ('匹配 ' + f.expr) }}</span>
                  <span class="sd-w">-{{ f.weight }}</span>
                </div>
                <div class="std-dim">
                  <span class="sd-name">重复</span>
                  <span class="sd-desc">与已有病历内容完全一致</span>
                  <span class="sd-w">-{{ rules.duplicateWeight }}</span>
                </div>
                <div class="std-dim">
                  <span class="sd-name">术语标准化</span>
                  <span class="sd-desc">
                    {{ rules.standardization.enabled
                      ? ('未命中词典的 ' + (rules.standardization.elementTypes || []).join('/') + ' 实体')
                      : '已关闭（不参与评分）' }}
                  </span>
                  <span class="sd-w">
                    {{ rules.standardization.enabled
                      ? ('每个 -' + rules.standardization.weightEach + '，上限 -' + rules.standardization.cap)
                      : '—' }}
                  </span>
                </div>
              </div>
              <div class="std-col">
                <div class="sub-hd">分级线</div>
                <div class="std-grade ok">合格：无逻辑冲突且 ≥ {{ rules.thresholds.qualified }} 分</div>
                <div class="std-grade mid">待复核：有逻辑冲突，或 {{ rules.thresholds.invalid }}~{{ rules.thresholds.qualified - 1 }} 分</div>
                <div class="std-grade bad">无效：&lt; {{ rules.thresholds.invalid }} 分，或真缺失 ≥ {{ rules.thresholds.seriousFullMissing }} 项</div>

                <div class="sub-hd">一致性规则（{{ (rules.consistency || []).length }} 条）</div>
                <template v-if="(rules.consistency || []).length">
                  <div v-for="r in rules.consistency" :key="r.name" class="rule-card">
                    <b>{{ r.name }}（-{{ r.weight }}）</b>
                    <span>证候含：{{ (r.patternAny || []).join('/') }}</span>
                    <span v-if="r.expectHerbs && r.expectHerbs.length">期望中药：{{ r.expectHerbs.join('/') }}</span>
                    <span v-if="r.expectTongue && r.expectTongue.length">期望舌象：{{ r.expectTongue.join('/') }}</span>
                    <span v-if="r.expectPulse && r.expectPulse.length">期望脉象：{{ r.expectPulse.join('/') }}</span>
                  </div>
                </template>
                <div v-else class="tip">未配置一致性规则（不判冲突）</div>
                <div class="tip">规则依赖的要素在数据中缺失时自动「不适用」，不会误判。</div>
              </div>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
      <el-empty v-if="!rules" description="标准加载中…" :image-size="60" />
      <div v-if="ruleWarnings.length" class="trunc-hint">规则告警：{{ ruleWarnings.join('；') }}</div>
    </PanelCard>

    <!-- 规则配置（仅管理员）：全量编辑，保存即生效 -->
    <el-dialog v-model="rulesVisible" title="质控规则配置（保存即生效）" width="min(1040px, 96vw)" top="4vh">
      <div v-if="form.rules" class="rc">
        <div class="rc-hd">完整性要素</div>
        <el-table :data="form.rules.completeness.elements" border size="small">
          <el-table-column label="名称" width="90"><template #default="{ row }"><el-input v-model="row.name" size="small" /></template></el-table-column>
          <el-table-column label="结构化key" width="130"><template #default="{ row }"><el-input v-model="row.source" size="small" /></template></el-table-column>
          <el-table-column label="回退字段（逗号分隔）"><template #default="{ row }"><el-input v-model="row.fallbackText" size="small" /></template></el-table-column>
          <el-table-column label="真缺失" width="90"><template #default="{ row }"><el-input-number v-model="row.weightFull" size="small" :min="0" :controls="false" /></template></el-table-column>
          <el-table-column label="漏抽" width="90"><template #default="{ row }"><el-input-number v-model="row.weightPartial" size="small" :min="0" :controls="false" /></template></el-table-column>
          <el-table-column width="54"><template #default="{ $index }"><el-button link type="danger" @click="form.rules.completeness.elements.splice($index, 1)">删</el-button></template></el-table-column>
        </el-table>
        <el-button size="small" @click="addElement">+ 要素</el-button>

        <div class="rc-hd">格式规则</div>
        <el-table :data="form.rules.format" border size="small">
          <el-table-column label="字段" width="110"><template #default="{ row }"><el-input v-model="row.field" size="small" /></template></el-table-column>
          <el-table-column label="类型" width="100"><template #default="{ row }"><el-select v-model="row.type" size="small"><el-option label="regex" value="regex" /><el-option label="enum" value="enum" /></el-select></template></el-table-column>
          <el-table-column label="表达式/枚举（枚举用逗号）"><template #default="{ row }"><el-input v-model="row.ruleText" size="small" /></template></el-table-column>
          <el-table-column label="展示名" width="100"><template #default="{ row }"><el-input v-model="row.label" size="small" /></template></el-table-column>
          <el-table-column label="扣分" width="80"><template #default="{ row }"><el-input-number v-model="row.weight" size="small" :min="0" :controls="false" /></template></el-table-column>
          <el-table-column width="54"><template #default="{ $index }"><el-button link type="danger" @click="form.rules.format.splice($index, 1)">删</el-button></template></el-table-column>
        </el-table>
        <el-button size="small" @click="addFormat">+ 格式规则</el-button>

        <div class="rc-hd">一致性规则（证候命中 → 期望中药/舌象/脉象）</div>
        <el-table :data="form.rules.consistency" border size="small">
          <el-table-column label="规则名" width="150"><template #default="{ row }"><el-input v-model="row.name" size="small" /></template></el-table-column>
          <el-table-column label="证候关键词（逗号）"><template #default="{ row }"><el-input v-model="row.patternText" size="small" /></template></el-table-column>
          <el-table-column label="期望中药（逗号）"><template #default="{ row }"><el-input v-model="row.herbsText" size="small" /></template></el-table-column>
          <el-table-column label="期望舌象（逗号）"><template #default="{ row }"><el-input v-model="row.tongueText" size="small" /></template></el-table-column>
          <el-table-column label="期望脉象（逗号）"><template #default="{ row }"><el-input v-model="row.pulseText" size="small" /></template></el-table-column>
          <el-table-column label="扣分" width="80"><template #default="{ row }"><el-input-number v-model="row.weight" size="small" :min="0" :controls="false" /></template></el-table-column>
          <el-table-column width="54"><template #default="{ $index }"><el-button link type="danger" @click="form.rules.consistency.splice($index, 1)">删</el-button></template></el-table-column>
        </el-table>
        <el-button size="small" @click="addConsistency">+ 一致性规则</el-button>

        <div class="rc-hd">术语标准化 / 其他</div>
        <div class="rc-row">
          <span>启用标准化</span>
          <el-switch v-model="form.rules.standardization.enabled" />
          <span>每条扣分</span>
          <el-input-number v-model="form.rules.standardization.weightEach" size="small" :min="0" :controls="false" />
          <span>上限</span>
          <el-input-number v-model="form.rules.standardization.cap" size="small" :min="0" :controls="false" />
        </div>
        <div class="rc-row">
          <span>标准化类型</span>
          <el-select v-model="form.rules.standardization.elementTypes" multiple size="small" style="min-width: 260px">
            <el-option v-for="t in TERM_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </div>
        <div class="rc-row">
          <span>重复扣分</span>
          <el-input-number v-model="form.rules.duplicateWeight" size="small" :min="0" :controls="false" />
          <span>合格线</span>
          <el-input-number v-model="form.rules.thresholds.qualified" size="small" :min="0" :max="100" :controls="false" />
          <span>无效线</span>
          <el-input-number v-model="form.rules.thresholds.invalid" size="small" :min="0" :max="100" :controls="false" />
          <span>真缺失判严重</span>
          <el-input-number v-model="form.rules.thresholds.seriousFullMissing" size="small" :min="1" :controls="false" />
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
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { qcScore, getQcRules, getDeductionStats, updateQcRules, resetQcRules } from '@/api/qc'
import { searchRecords } from '@/api/records'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.role === '管理员')

const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })

// ===== 评分标准 / 扣分构成（批P/Q） =====
const rules = ref(null)
const ruleWarnings = ref([])
const standardOpen = ref(['std']) // 默认展开：先让用户看到"标准"
const dedStats = ref(null)
const dedLoading = ref(false)
const TERM_TYPES = ['disease', 'pattern', 'symptom', 'herb', 'formula']

const loadRules = async () => {
  try {
    const res = await getQcRules()
    rules.value = res.data?.rules || null
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

// ===== 规则配置（管理员，全量编辑，保存即生效） =====
const rulesVisible = ref(false)
const savingRules = ref(false)
const form = reactive({ rules: null })
const clone = (o) => JSON.parse(JSON.stringify(o))
const split = (s) => String(s || '').split(/[，,;；\s]+/).map((x) => x.trim()).filter(Boolean)

const openRules = async () => {
  if (!rules.value) {
    await loadRules()
  }
  const r = clone(rules.value || { completeness: { elements: [] }, format: [], consistency: [], standardization: {}, thresholds: {} })
  r.completeness = r.completeness || { elements: [] }
  r.completeness.elements = r.completeness.elements || []
  r.format = r.format || []
  r.consistency = r.consistency || []
  r.standardization = r.standardization || { enabled: true, elementTypes: [], weightEach: 1, cap: 5 }
  r.thresholds = r.thresholds || { qualified: 90, invalid: 60, seriousFullMissing: 3 }

  r.completeness.elements.forEach((e) => { e.fallbackText = (e.fallback || []).join(',') })
  r.format.forEach((f) => { f.ruleText = f.type === 'enum' ? (f.values || []).join(',') : (f.expr || '') })
  r.consistency.forEach((c) => {
    c.patternText = (c.patternAny || []).join(',')
    c.herbsText = (c.expectHerbs || []).join(',')
    c.tongueText = (c.expectTongue || []).join(',')
    c.pulseText = (c.expectPulse || []).join(',')
  })
  form.rules = r
  rulesVisible.value = true
}
const addElement = () => form.rules.completeness.elements.push({ name: '', source: '', fallbackText: '', weightFull: 12, weightPartial: 6 })
const addFormat = () => form.rules.format.push({ field: '', type: 'regex', ruleText: '', label: '', weight: 5, reason: '' })
const addConsistency = () => form.rules.consistency.push({ name: '', patternText: '', herbsText: '', tongueText: '', pulseText: '', weight: 10 })

const saveRules = async () => {
  savingRules.value = true
  try {
    const r = clone(form.rules)
    ;(r.completeness.elements || []).forEach((e) => { e.fallback = split(e.fallbackText); delete e.fallbackText })
    ;(r.format || []).forEach((f) => {
      if (f.type === 'enum') { f.values = split(f.ruleText); f.expr = null } else { f.expr = f.ruleText; f.values = [] }
      delete f.ruleText
    })
    ;(r.consistency || []).forEach((c) => {
      c.patternAny = split(c.patternText)
      c.expectHerbs = split(c.herbsText)
      c.expectTongue = split(c.tongueText)
      c.expectPulse = split(c.pulseText)
      delete c.patternText; delete c.herbsText; delete c.tongueText; delete c.pulseText
    })
    const res = await updateQcRules(r)
    rules.value = res.data?.rules || rules.value
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

/* ===== 规则配置弹窗 ===== */
.hd-action {
  margin-left: 12px;
}
.rc {
  max-height: 70vh;
  overflow-y: auto;
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
