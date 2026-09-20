<template>
  <div class="review-page">
    <!-- ① 待复核任务列表 -->
    <PanelCard title="待复核任务列表">
      <div class="rv-bar">
        <span>状态</span>
        <el-select v-model="status" size="small" style="width: 130px" @change="load(1)">
          <el-option label="待复核" value="待复核" />
          <el-option label="已完成" value="已完成" />
        </el-select>
        <el-button size="small" @click="load()">刷新</el-button>
        <span class="tip">超时仅视觉提醒、不自动流转；点击「进入复核」在下方展开对照</span>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        border
        size="small"
        max-height="360"
        :row-class-name="rowClass"
        highlight-current-row
      >
        <el-table-column prop="taskId" label="任务ID" width="180" show-overflow-tooltip />
        <el-table-column prop="recordId" label="病历ID" width="300" show-overflow-tooltip />
        <el-table-column prop="issueType" label="问题类型" min-width="150" show-overflow-tooltip />
        <el-table-column prop="score" label="当前评分" width="90" />
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ fmt(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="截止时间" width="190">
          <template #default="{ row }">
            <span :class="{ overdue: row.overdue }">{{ fmt(row.deadlineTime) }}</span>
            <el-tag v-if="row.overdue" type="danger" size="small" effect="plain" class="od-tag">超时</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="current && current.taskId === row.taskId" link type="warning" disabled>当前</el-button>
            <el-button v-else link type="primary" @click="openReview(row)">进入复核</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无复核任务" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="load"
        @size-change="handleSizeChange"
      />
    </PanelCard>

    <div v-loading="detailLoading">
      <!-- ② 当前任务卡：详情区头部，右上角固定「关闭详情」出口（UX-73 第七轮）。
           第六轮只把关闭入口放进底部吸底条，用户实测仍反馈「只有保存修改 / 复核通过」——
           进入复核后视线落在头部，出口必须在这里就出现，位置与弹窗右上角关闭同侧 -->
      <section v-if="current" class="task-card">
        <span class="task-id">{{ current.recordId }}</span>
        <span class="tag tag-score">当前评分：{{ current.score ?? '—' }} 分</span>
        <span v-for="t in issueTags" :key="t" class="tag tag-issue">{{ t }}</span>
        <span class="deadline">
          复核截止：<b>{{ fmt(current.deadlineTime) }}</b>（{{ remainText }}）
        </span>
        <el-button class="close-top" :disabled="submitting" @click="closeReview">关闭详情</el-button>
      </section>

      <!-- ③ 病历原文对照（可折叠） -->
      <details v-if="record" class="raw-panel" open>
        <summary>病历原文对照 · {{ record.registrationNo || record.id }}（{{ patientSummary }}）</summary>
        <div class="raw-bd raw-grid">
          <div
            v-for="f in FIELDS"
            :key="f.key"
            class="raw-item"
            :class="{ full: f.wide }"
          >
            <span class="k">{{ f.label }}</span>
            <span class="v">{{ fieldOf(record, f.key) || '—' }}</span>
          </div>
        </div>
      </details>

      <!-- ④ 左右对比：左原始只读 / 右人工修正 -->
      <div v-if="record" class="compare">
        <section class="panel">
          <h2 class="panel-hd hd-left">
            原始结构化数据（NLP 抽取）<span class="mini-tag">只读锁定</span>
          </h2>
          <div class="panel-bd">
            <div v-for="f in COMPARE_FIELDS" :key="f.key" class="field-row">
              <div class="flabel">{{ f.label }}</div>
              <div class="fvalue">
                <span v-if="originalText(f.key)">{{ originalText(f.key) }}</span>
                <span v-else class="miss">缺失（抽取为空）</span>
              </div>
            </div>

            <p class="ded-hd">质控扣分明细（合计 -{{ totalDeduct }} 分）</p>
            <div v-for="(d, i) in deductions" :key="i" class="ded-item">
              <span>{{ d.type }}：{{ d.reason }}</span>
              <span class="pts">-{{ d.points }}</span>
            </div>
            <div v-if="!deductions.length && precheck" class="ok">无扣分项</div>

            <template v-if="aiLines.length">
              <p class="ded-hd">AI 预检建议</p>
              <div class="ai-box">
                <p v-for="(l, i) in aiLines" :key="i">{{ l }}</p>
                <span class="ai-src">{{ aiSource === 'rule' ? '规则预检（LLM 未启用）' : 'AI 建议' }}</span>
              </div>
            </template>
          </div>
        </section>

        <section class="panel">
          <h2 class="panel-hd hd-right">人工修正</h2>
          <div class="panel-bd">
            <div
              v-for="f in COMPARE_FIELDS"
              :key="f.key"
              class="field-row"
              :class="{ fixed: isFixed(f.key) }"
            >
              <div class="flabel">{{ f.label }}</div>
              <div class="fvalue term-wrap">
                <TermInput
                  v-if="f.termType"
                  v-model="editValues[f.key]"
                  :type="f.termType"
                  :placeholder="originalText(f.key) || '原值为空，请输入或选择'"
                />
                <el-input
                  v-else
                  v-model="editValues[f.key]"
                  size="small"
                  :placeholder="originalText(f.key) || '原值为空，请输入'"
                  clearable
                />
              </div>
            </div>
            <div class="term-note">↑ 带下拉的字段可直接搜索国标术语；多个词用「、」分隔</div>

            <div class="field-row">
              <div class="flabel">复核备注</div>
              <div class="fvalue">
                <el-input v-model="remark" type="textarea" :rows="3" placeholder="留痕用，例如：已对照原文补充脉象" />
              </div>
            </div>

            <div class="preview">
              复核后预估评分：<b>{{ estimate.score }}</b> 分　预计分级：<span class="tag-ok">{{ estimate.grade }}</span>
              <span class="est-note">（按已补齐的核心字段扣分回算，最终以服务端重算为准）</span>
            </div>
          </div>
        </section>
      </div>

      <!-- ⑤ 提交反馈 -->
      <section v-if="result" class="result-bar">
        <span class="rk">提交反馈：</span>
        <span class="rv">
          状态：<b>{{ result.status }}</b>　·　重新评分：<b>{{ result.score }} 分</b>　·　
          {{ result.errors && result.errors.length ? '剩余问题：' : '剩余问题：无' }}
          <template v-if="result.errors && result.errors.length">
            <span v-for="(e, i) in result.errors" :key="i">{{ i ? '；' : '' }}{{ e.type }}：{{ e.msg }}</span>
          </template>
        </span>
        <span class="deadline">{{ submittedAt }}</span>
      </section>

      <!-- ⑥ 底部操作 -->
      <section v-if="record" class="footer-bar">
        <span class="tip">
          点击「复核通过」后系统会自动重新执行质控评分与诊疗逻辑校验；不填修正内容表示仅裁定不修改数据。
        </span>
        <div class="btns">
          <!-- 补关闭入口（UX-73）：此前只有保存 / 通过两个出口，想只读退出无处可点。
               第七轮在任务卡右上角再加一处，底部这处保留 —— 读到最底也有出口 -->
          <el-button :disabled="submitting" @click="closeReview">关闭详情</el-button>
          <el-button :loading="submitting" @click="submit(false)">保存修改</el-button>
          <el-button type="primary" :loading="submitting" @click="submit(true)">复核通过</el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import TermInput from '@/components/TermInput.vue'
import { listReviewTasks, submitReview } from '@/api/review'
import { getRawRecord } from '@/api/records'
import { aiReview } from '@/api/ai'
import { qcScore } from '@/api/qc'

const FIELDS = [
  { key: 'registrationNo', label: '登记号' },
  { key: 'outpatientNo', label: '门诊号' },
  { key: 'gender', label: '性别' },
  { key: 'age', label: '年龄' },
  { key: 'visitCount', label: '就诊次数' },
  { key: 'westernDiagnosis', label: '西医诊断', wide: true },
  { key: 'tcmDiagnosis', label: '中医诊断', wide: true },
  { key: 'chiefComplaint', label: '主诉', wide: true },
  { key: 'selfReport', label: '自诉', wide: true },
  { key: 'presentIllness', label: '现病史', wide: true },
  { key: 'inspection', label: '望诊', wide: true },
  { key: 'pulse', label: '脉诊', wide: true },
  { key: 'tongue', label: '舌诊', wide: true },
  { key: 'physicalExam', label: '查体', wide: true },
  { key: 'pattern', label: '辨证结论', wide: true },
  { key: 'prescription', label: '草药', wide: true },
  { key: 'followUp', label: '随访', wide: true },
  { key: 'treatmentEffect', label: '治疗效果' },
  { key: 'department', label: '开单科室' },
  { key: 'doctorId', label: '医生工号' },
  { key: 'visitTime', label: '接诊时间' }
]

const fieldOf = (row, key) => {
  if (!row) return ''
  if (key === 'visitTime') return row.visitTime ? String(row.visitTime).replace('T', ' ').substring(0, 19) : ''
  return row[key]
}

/**
 * 对照区的字段（功能设计附录A 的 9 类实体）。
 * termType 指向词典类型；治法/病因在现有词典里没有对应类别，故用普通输入框。
 */
const COMPARE_FIELDS = [
  { key: 'patternList', label: '证候', termType: 'pattern' },
  { key: 'treatmentList', label: '治法', termType: '' },
  { key: 'formulaList', label: '方剂', termType: 'formula' },
  { key: 'tongueList', label: '舌象', termType: 'symptom' },
  { key: 'pulseList', label: '脉象', termType: 'symptom' },
  { key: 'herbs', label: '中药', termType: 'herb' },
  { key: 'diseases', label: '疾病', termType: 'disease' },
  { key: 'symptoms', label: '症状', termType: 'symptom' },
  { key: 'causeList', label: '病因', termType: '' }
]

/** 扣分明细里「核心字段缺失」的 item 名 → structuredData 键，用于预估评分回算 */
const FIELD_BY_ITEM = {
  脉象: 'pulseList',
  舌象: 'tongueList',
  证候: 'patternList',
  治法: 'treatmentList',
  方剂: 'formulaList',
  中药: 'herbs'
}

const fmt = (t) => (t ? String(t).replace('T', ' ').substring(0, 16) : '—')

// ===== ① 任务列表 =====
const status = ref('待复核')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)

const load = async (p) => {
  if (typeof p === 'number') page.value = p
  loading.value = true
  try {
    const res = await listReviewTasks({ page: page.value, pageSize: pageSize.value, status: status.value })
    rows.value = res.data?.tasks || []
    total.value = res.data?.total || 0
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

const handleSizeChange = () => {
  page.value = 1
  load()
}

const rowClass = ({ row }) => (row.overdue ? 'row-overdue' : '')

// ===== ②~⑥ 同页复核 =====
const detailLoading = ref(false)
const current = ref(null)
const record = ref(null)
const precheck = ref(null)
const aiAnswer = ref('')
const aiSource = ref('')
const remark = ref('')
const submitting = ref(false)
const result = ref(null)
const submittedAt = ref('')

const editValues = reactive({})
const originalMap = ref({})

const aiLines = computed(() => (aiAnswer.value || '').split('\n').filter((l) => l.trim() !== ''))
const deductions = computed(() => precheck.value?.deductions || [])
const totalDeduct = computed(() => deductions.value.reduce((s, d) => s + (d.points || 0), 0))

const issueTags = computed(() =>
  String(current.value?.issueType || '')
    .split(/[；;，,]/)
    .map((s) => s.trim())
    .filter(Boolean)
)

const patientSummary = computed(() => {
  const r = record.value
  if (!r) return ''
  return [
    r.gender,
    r.age ? `${r.age} 岁` : '',
    r.department,
    r.visitTime ? `${String(r.visitTime).replace('T', ' ').substring(0, 10)} 就诊` : ''
  ]
    .filter(Boolean)
    .join('　')
})

const remainText = computed(() => {
  const t = current.value?.deadlineTime
  if (!t) return ''
  const days = Math.ceil((new Date(t).getTime() - Date.now()) / 86400000)
  if (days < 0) return `已超时 ${-days} 天`
  if (days === 0) return '今天截止'
  return `剩余 ${days} 天`
})

const textOf = (entry) => entry?.content || entry?.name || ''
const originalText = (key) => (originalMap.value[key] || []).map(textOf).filter(Boolean).join('、')
const isFixed = (key) => String(editValues[key] || '') !== originalText(key)

const safeParse = (sd) => {
  if (!sd) return {}
  if (typeof sd === 'object') return sd
  try {
    return JSON.parse(sd)
  } catch {
    return {}
  }
}

const fillEditors = (sd) => {
  const data = safeParse(sd)
  const map = {}
  COMPARE_FIELDS.forEach((f) => {
    const list = Array.isArray(data[f.key]) ? data[f.key] : []
    map[f.key] = list
    editValues[f.key] = list.map(textOf).filter(Boolean).join('、')
  })
  originalMap.value = map
}

/** 字段级表单 → structuredData；原存在的术语沿用原文溯源 sourceText */
const buildCorrected = () => {
  const out = {}
  COMPARE_FIELDS.forEach((f) => {
    const words = String(editValues[f.key] || '')
      .split(/[、,，;；|]/)
      .map((s) => s.trim())
      .filter(Boolean)
    out[f.key] = words.map((w) => {
      const hit = (originalMap.value[f.key] || []).find((e) => textOf(e) === w)
      if (hit) return hit
      return f.key === 'herbs'
        ? { name: w, sourceText: '', standardTerm: '' }
        : { content: w, sourceText: '', standardTerm: '' }
    })
  })
  return out
}

/**
 * 复核后预估评分（原型「复核后预估评分」区）。
 * 只做「已补齐的核心字段把对应扣分加回」这一条，且明确标注以服务端重算为准 ——
 * 前端不复制规则表，避免与服务端判定口径漂移。
 */
const estimate = computed(() => {
  const base = precheck.value?.score ?? current.value?.score ?? 0
  let gain = 0
  deductions.value.forEach((d) => {
    if (d.type !== '核心字段缺失') return
    const key = FIELD_BY_ITEM[d.item]
    if (key && String(editValues[key] || '').trim()) gain += d.points || 0
  })
  const score = Math.max(0, Math.min(100, base + gain))
  const grade = score >= 90 ? '合格 → 进入数据治理' : score >= 60 ? '待复核' : '无效'
  return { score, grade }
})

const openReview = async (row) => {
  current.value = row
  detailLoading.value = true
  record.value = null
  precheck.value = null
  aiAnswer.value = ''
  result.value = null
  remark.value = ''
  Object.keys(editValues).forEach((k) => delete editValues[k])
  originalMap.value = {}
  try {
    const [raw, sr] = await Promise.all([getRawRecord(row.recordId), qcScore({ recordId: row.recordId })])
    record.value = raw.data
    precheck.value = sr.data
    // 任务列表已带 structuredData，但以病历详情为准（列表数据可能滞后）
    fillEditors(raw.data?.structuredData ?? row.structuredData)
    try {
      const ai = await aiReview({ recordId: row.recordId })
      aiAnswer.value = ai.data?.answer || ''
      aiSource.value = ai.data?.source || ''
    } catch {
      aiAnswer.value = '（AI 预检不可用，请以左侧扣分明细为准）'
    }
    // 展开后滚动到任务卡，避免用户以为「点了没反应」
    await Promise.resolve()
    document.querySelector('.task-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch {
    // 拦截器已提示
  } finally {
    detailLoading.value = false
  }
}

/** 退出详情：收起任务卡 / 原文 / 对照区，保留提交反馈条 */
const exitDetail = () => {
  current.value = null
  record.value = null
  precheck.value = null
  aiAnswer.value = ''
  aiSource.value = ''
  remark.value = ''
  Object.keys(editValues).forEach((k) => delete editValues[k])
  originalMap.value = {}
}

/** 关闭详情：连提交反馈一起收起，回到纯任务列表（UX-73） */
const closeReview = () => {
  exitDetail()
  result.value = null
}

const submit = async (withCorrection) => {
  submitting.value = true
  result.value = null
  try {
    const body = {}
    if (withCorrection) body.correctedData = buildCorrected()
    if (remark.value.trim()) body.comment = remark.value.trim()
    const res = await submitReview(current.value.recordId, body)
    result.value = res.data
    submittedAt.value = new Date().toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
    ElMessage.success(`复核完成：${res.data.status}`)
    // 复核通过即退出详情（UX-74）：任务已办结，无需用户再手动关一次
    if (withCorrection) exitDetail()
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.rv-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.rv-bar > span:first-child {
  font-size: 13px;
  color: var(--text-sub);
}
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
}
.overdue {
  color: var(--danger);
  font-weight: bold;
}
.od-tag {
  margin-left: 6px;
}
:deep(.row-overdue) {
  background: #fdf6f4;
}

/* ===== ② 当前任务卡 ===== */
.task-card {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  background: #fff;
  border: 1px solid var(--line);
  border-left: 4px solid var(--ochre);
  border-radius: 6px;
  padding: 12px 18px;
  margin-bottom: 14px;
}
.task-id {
  font-size: 15px;
  font-weight: bold;
  color: var(--ink);
}
.tag {
  display: inline-block;
  padding: 1px 8px;
  font-size: 12px;
  border-radius: 2px;
  line-height: 20px;
}
.tag-score {
  color: var(--ochre);
  background: var(--ochre-light);
  border: 1px solid #e0cdb0;
}
.tag-issue {
  color: var(--danger);
  background: #f6e9e6;
  border: 1px solid #e3c3bb;
}
.deadline {
  margin-left: auto;
  font-size: 13px;
  color: var(--text-sub);
}
.deadline b {
  color: var(--danger);
}
/* 详情区右上角出口（UX-73 第七轮）：与病历详情弹窗右上角关闭同侧，
   进入复核即可见，不必先滚到页面底部 */
.task-card .close-top {
  flex-shrink: 0;
}

/* ===== ③ 原文折叠 ===== */
.raw-panel {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  margin-bottom: 14px;
}
.raw-panel summary {
  padding: 10px 16px;
  font-size: 14px;
  font-weight: bold;
  color: var(--ink);
  cursor: pointer;
  list-style: none;
}
.raw-panel summary::-webkit-details-marker {
  display: none;
}
.raw-panel summary::before {
  content: '▸ ';
  color: var(--ink-mid);
}
.raw-panel[open] summary::before {
  content: '▾ ';
}
.raw-panel summary:hover {
  background: #faf8f1;
}
.raw-bd {
  padding: 4px 20px 16px;
  border-top: 1px solid #eee9dd;
}
.raw-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 28px;
}
.raw-item {
  display: flex;
  padding: 7px 0;
  border-bottom: 1px dashed #ece8dc;
  font-size: 13px;
}
.raw-item.full {
  grid-column: 1 / -1;
}
.raw-item .k {
  width: 76px;
  flex-shrink: 0;
  color: var(--text-sub);
}
.raw-item .v {
  flex: 1;
  color: var(--text);
  word-break: break-all;
}

/* ===== ④ 左右对比 ===== */
.compare {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-bottom: 14px;
}
.compare .panel {
  margin-bottom: 0;
  /* 补齐面板外框（UX-75）：本页自写 .panel / .panel-hd / .panel-bd，
     原先漏了 .panel 的外框，左右对比区看起来没有边界，与病历数据页不一致 */
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow: hidden;
}
.panel-hd {
  margin: 0;
  padding: 10px 16px;
  border-bottom: 1px solid #eee9dd;
  font-size: 14px;
  font-weight: bold;
  display: flex;
  align-items: center;
  gap: 10px;
}
.panel-hd.hd-left {
  border-bottom-color: #eee4d3;
  background: #faf6ee;
  color: var(--ochre);
}
.panel-hd.hd-right {
  border-bottom-color: #d9e3dc;
  background: #f2f6f3;
  color: var(--ink-mid);
}
.mini-tag {
  font-size: 11.5px;
  font-weight: normal;
  color: var(--text-sub);
  border: 1px solid var(--line);
  border-radius: 2px;
  padding: 0 6px;
  line-height: 18px;
}
.panel-bd {
  padding: 6px 16px 16px;
}
.field-row {
  display: flex;
  align-items: flex-start;
  min-height: 40px;
  padding: 8px 0;
  border-bottom: 1px dashed #ece8dc;
}
.field-row:last-of-type {
  border-bottom: none;
}
/* 修正过的字段整行高亮，与原型一致 */
.field-row.fixed {
  background: #f2f6f3;
  border-radius: 2px;
  padding-left: 8px;
  padding-right: 8px;
  margin: 0 -8px;
}
.flabel {
  width: 78px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--text-sub);
  padding-top: 6px;
}
.fvalue {
  flex: 1;
  min-width: 0;
  font-size: 13.5px;
}
.miss {
  color: var(--danger);
  background: #f6e9e6;
  padding: 2px 10px;
  border-radius: 2px;
  font-size: 12.5px;
  display: inline-block;
}
.term-note {
  font-size: 11.5px;
  color: #a09c90;
  margin: 6px 0 8px 78px;
}
.ded-hd {
  font-size: 12.5px;
  color: var(--text-sub);
  margin: 13px 0 8px;
}
.ded-item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  background: var(--ochre-light);
  border-radius: 2px;
  padding: 6px 12px;
  margin-bottom: 6px;
  font-size: 12.5px;
}
.ded-item .pts {
  color: var(--danger);
  font-weight: bold;
  flex-shrink: 0;
}
.ok {
  padding: 6px 0;
  color: var(--ink-mid);
  font-size: 12.5px;
}
.ai-box {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink);
}
.ai-box p {
  margin: 0 0 4px;
}
.ai-src {
  display: inline-block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-sub);
}
.preview {
  margin-top: 12px;
  background: var(--ink-light);
  border: 1px solid #cddcd2;
  border-radius: 2px;
  padding: 9px 14px;
  font-size: 13px;
  color: var(--ink-mid);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.preview b {
  font-size: 17px;
}
.tag-ok {
  color: var(--ink-mid);
  background: #fff;
  border: 1px solid var(--ink-mid);
  border-radius: 2px;
  padding: 1px 8px;
  font-size: 12px;
}
.est-note {
  font-size: 11.5px;
  color: var(--text-sub);
}

/* ===== ⑤ 提交反馈条 ===== */
.result-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border: 1px solid var(--line);
  border-left: 4px solid var(--ink-mid);
  border-radius: 6px;
  padding: 12px 18px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.result-bar .rk {
  font-weight: bold;
  color: var(--ink-mid);
}
.result-bar .rv {
  color: var(--text-sub);
  font-size: 13px;
}
.result-bar b {
  color: var(--ink-mid);
}

/* ===== ⑥ 底部操作 ===== */
/* 页面根为 flex 列 + min-height:100%：内容不足一屏时 margin-top:auto 把底栏顶到底部，
   不再浮在页面中部（data-v 作用域元素浮中部问题）；内容超长时 sticky 仍吸底可见 */
.review-page {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
.footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 13px 20px;
  flex-wrap: wrap;
  /* 吸底（UX-73）：对照区很长，关闭 / 提交入口始终可见，不必滚到底 */
  position: sticky;
  bottom: 0;
  margin-top: auto;
  z-index: 3;
  box-shadow: 0 -2px 8px rgba(47, 70, 57, 0.06);
}
.footer-bar .tip {
  flex: 1;
  min-width: 240px;
}
.btns {
  display: flex;
  gap: 10px;
}

@media (max-width: 1200px) {
  .compare,
  .raw-grid {
    grid-template-columns: 1fr;
  }
}
</style>
