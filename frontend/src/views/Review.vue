<template>
  <PanelCard title="人工复核工作台">
    <div class="rv-bar">
      <span>状态</span>
      <el-select v-model="status" size="small" style="width: 130px" @change="load(1)">
        <el-option label="待复核" value="待复核" />
        <el-option label="已完成" value="已完成" />
      </el-select>
      <el-button size="small" @click="load()">刷新</el-button>
      <span class="tip">超时仅视觉提醒、不自动流转</span>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      size="small"
      max-height="520"
      :row-class-name="rowClass"
    >
      <el-table-column prop="recordId" label="病历ID" width="320" show-overflow-tooltip />
      <el-table-column prop="issueType" label="问题类型" width="110" />
      <el-table-column prop="score" label="评分" width="70" />
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmt(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="复核截止" width="180">
        <template #default="{ row }">
          <span :class="{ overdue: row.overdue }">{{ fmt(row.deadlineTime) }}</span>
          <el-tag v-if="row.overdue" type="danger" size="small" effect="plain" class="od-tag">超时</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openReview(row)">复核</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无复核任务" :image-size="80" />
      </template>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      :page-size="pageSize"
      :total="total"
      layout="total, prev, pager, next"
      style="margin-top: 12px; justify-content: flex-end"
      @current-change="load"
    />
  </PanelCard>

  <el-dialog v-model="visible" title="复核对照（原始病历 / AI预检单）" width="min(1040px, 94vw)" top="6vh">
    <div v-loading="detailLoading" class="rv-grid">
      <!-- 左：原始病历 + 结构化 -->
      <section class="rv-col">
        <div class="col-hd">原始病历（只读）</div>
        <el-descriptions v-if="record" :column="2" border size="small">
          <el-descriptions-item v-for="f in FIELDS" :key="f.key" :label="f.label" :span="f.wide ? 2 : 1">
            {{ fieldOf(record, f.key) || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="评分">{{ record.score ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="分级">{{ record.grade || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="sd-title">结构化数据</div>
        <StructuredDataCard :data="record?.structuredData" />
      </section>

      <!-- 右：AI 预检 + 修正 -->
      <section class="rv-col">
        <div class="col-hd">AI 预检意见</div>
        <div class="ai-box">
          <p v-for="(l, i) in aiLines" :key="i">{{ l }}</p>
          <span class="ai-src">{{ aiSource === 'rule' ? '规则预检（LLM 未启用）' : 'AI 建议' }}</span>
        </div>

        <div class="blk-title">扣分明细</div>
        <ul class="ded">
          <li v-for="(d, i) in (precheck?.deductions || [])" :key="i">
            {{ d.type }} · {{ d.item }}（-{{ d.points }}）：{{ d.reason }}
          </li>
          <li v-if="!(precheck?.deductions || []).length && precheck" class="ok">无扣分项</li>
        </ul>

        <div class="blk-title">人工修正（structuredData JSON，可留空）</div>
        <el-input v-model="corrected" type="textarea" :rows="7" placeholder="按附录A结构编辑；留空表示不修改数据" />

        <div class="rv-actions">
          <el-button type="primary" :loading="submitting" @click="submit(true)">提交修正并复核</el-button>
          <el-button :loading="submitting" @click="submit(false)">仅重算评分</el-button>
        </div>
        <div class="tip">
          「仅重算评分」不修改病历数据；任务是否结束由重算结果判定（不填修正内容时即等同此操作）。
        </div>
        <div v-if="result" class="rv-result">
          复核结果：<b>{{ result.status }}</b>，评分 {{ result.score }}
          <ul v-if="result.errors && result.errors.length" class="ded">
            <li v-for="(e, i) in result.errors" :key="i">{{ e.type }}：{{ e.msg }}</li>
          </ul>
        </div>
      </section>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StructuredDataCard from '@/components/StructuredDataCard.vue'
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
  { key: 'pulse', label: '脉诊' },
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
  if (key === 'visitTime') return (row.visitTime || '').replace('T', ' ').substring(0, 19)
  return row[key]
}
const fmt = (v) => (v ? String(v).replace('T', ' ').substring(0, 19) : '—')

const status = ref('待复核')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)

const load = async (p) => {
  if (typeof p === 'number') page.value = p
  loading.value = true
  try {
    const res = await listReviewTasks({ page: page.value, pageSize, status: status.value })
    rows.value = res.data?.tasks || []
    total.value = res.data?.total || 0
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

const rowClass = ({ row }) => (row.overdue ? 'row-overdue' : '')

// ===== 复核弹窗 =====
const visible = ref(false)
const detailLoading = ref(false)
const record = ref(null)
const precheck = ref(null)
const aiAnswer = ref('')
const aiSource = ref('')
const corrected = ref('')
const submitting = ref(false)
const result = ref(null)
const currentId = ref('')

const aiLines = computed(() => (aiAnswer.value || '').split('\n').filter((l) => l.trim() !== ''))

const openReview = async (row) => {
  currentId.value = row.recordId
  visible.value = true
  detailLoading.value = true
  record.value = null
  precheck.value = null
  aiAnswer.value = ''
  result.value = null
  corrected.value = ''
  try {
    const [raw, sr] = await Promise.all([getRawRecord(row.recordId), qcScore({ recordId: row.recordId })])
    record.value = raw.data
    precheck.value = sr.data
    const sd = raw.data?.structuredData
    corrected.value = sd ? (typeof sd === 'string' ? sd : JSON.stringify(sd, null, 1)) : ''
    // AI 预检意见（LLM 关时为规则预检原文）
    try {
      const ai = await aiReview({ recordId: row.recordId })
      aiAnswer.value = ai.data?.answer || ''
      aiSource.value = ai.data?.source || ''
    } catch {
      aiAnswer.value = '（AI 预检不可用，请以左侧扣分明细为准）'
    }
  } catch {
    // 拦截器已提示
  } finally {
    detailLoading.value = false
  }
}

const submit = async (withCorrection) => {
  submitting.value = true
  result.value = null
  try {
    let correctedData = null
    if (withCorrection && corrected.value.trim()) {
      try {
        correctedData = JSON.parse(corrected.value)
      } catch {
        ElMessage.error('修正数据不是合法 JSON')
        return
      }
    }
    const body = correctedData ? { correctedData } : {}
    const res = await submitReview(currentId.value, body)
    result.value = res.data
    ElMessage.success(`复核完成：${res.data.status}`)
    load()
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
.rv-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  min-height: 420px;
  /* 视口相关的最大高度 + 内部滚动：1366×768 等矮屏下弹窗顶部不再被裁切（UX-12） */
  max-height: calc(92vh - 150px);
  overflow-y: auto;
  padding-right: 4px;
}
.rv-col {
  min-width: 0;
}
.col-hd {
  font-size: 13.5px;
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: 8px;
  margin-bottom: 10px;
}
.sd-title,
.blk-title {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  margin: 14px 0 8px;
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
.ded {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--text);
  line-height: 1.8;
}
.ded .ok {
  list-style: none;
  margin-left: -18px;
  color: var(--ink-mid);
}
.rv-actions {
  margin-top: 12px;
  display: flex;
  gap: 10px;
  /* 吸底：右列内容滚动时操作按钮始终可见（UX-12） */
  position: sticky;
  bottom: 0;
  background: #fff;
  padding: 10px 0;
  border-top: 1px solid var(--line);
}
.rv-result {
  margin-top: 12px;
  background: var(--ink-light);
  border: 1px solid #cddcd2;
  border-radius: 4px;
  padding: 8px 12px;
  font-size: 12.5px;
  color: var(--ink);
}
@media (max-width: 1200px) {
  .rv-grid {
    grid-template-columns: 1fr;
  }
}
</style>
