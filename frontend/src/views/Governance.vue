<template>
  <div>
    <!-- 治理状态行（顶部） -->
    <section class="gov-stats">
      <span class="gs"><b>{{ stats.qualified ?? 0 }}</b> 质控合格病历</span>
      <span class="gs"><b>{{ stats.pendingGovern ?? 0 }}</b> 待治理</span>
      <span class="gs"><b>{{ stats.governedCount ?? 0 }}</b> 已治理</span>
    </section>

    <!-- 数据清洗与术语归一（流程图） -->
    <PanelCard title="数据清洗与术语自动归一">
      <div class="flow-tip">
        <b>清洗绝不填充医生未书写的内容</b> —— 缺失字段由质控扣分标记，由人工复核环节补充；
        术语归一在解析环节已首次执行，此处按最新词典对全库实体兜底补归一。
      </div>

      <div class="flow-wrapper">
        <div v-for="(s, i) in STEPS" :key="s.title" class="flow-step">
          <div class="step-card">
            <div class="step-num">{{ i + 1 }}</div>
            <div class="step-title">{{ s.title }}</div>
            <div class="step-desc">{{ s.desc }}</div>
          </div>
          <div v-if="i < STEPS.length - 1" class="step-arrow">→</div>
        </div>
      </div>

      <div class="clean-actions">
        <el-button type="primary" size="large" :loading="clean.loading" @click="handleClean">
          {{ clean.loading ? '清洗执行中…' : '执行数据清洗' }}
        </el-button>
        <span class="tip">点击后按上述5步流水线处理，约1~3秒完成</span>
      </div>

      <!-- 清洗结果统计（中部） -->
      <div v-if="clean.result" class="clean-result">
        <div class="result-hd">清洗结果</div>
        <div class="clean-stats">
          <div class="stat-item">
            <div class="num">{{ clean.result.total }}</div>
            <div class="lbl">清洗总病历</div>
          </div>
          <div class="stat-item green">
            <div class="num">{{ clean.result.deduped }}</div>
            <div class="lbl">去重</div>
          </div>
          <div class="stat-item green">
            <div class="num">{{ clean.result.repaired }}</div>
            <div class="lbl">字段清理</div>
          </div>
          <div class="stat-item ochre">
            <div class="num">{{ clean.result.cleared }}</div>
            <div class="lbl">空值规整</div>
          </div>
          <div class="stat-item red">
            <div class="num">{{ clean.result.isolated }}</div>
            <div class="lbl">隔离归档</div>
          </div>
          <div class="stat-item green">
            <div class="num">{{ clean.result.normalized }}</div>
            <div class="lbl">术语归一命中</div>
          </div>
        </div>
      </div>
    </PanelCard>

    <!-- 标准数据集导出（中下部） -->
    <PanelCard title="标准数据集导出">
      <div class="export-row">
        <div>
          <label>导出格式</label>
          <el-radio-group v-model="format">
            <el-radio-button value="csv">CSV</el-radio-button>
            <el-radio-button value="json">JSON</el-radio-button>
          </el-radio-group>
        </div>
        <div>
          <label>科室</label>
          <el-select v-model="filters.department" placeholder="全部科室" clearable style="width: 130px">
            <el-option label="中医内科" value="中医内科" />
          </el-select>
        </div>
        <div>
          <label>就诊时间</label>
          <el-date-picker
            v-model="filters.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="截止"
            style="width: 240px"
          />
        </div>
        <div>
          <label>证候</label>
          <TermInput v-model="filters.pattern" type="pattern" placeholder="如：肝肾亏虚" style="width: 150px" />
        </div>
        <el-button @click="handlePreview" :loading="preview.loading">预览数据集</el-button>
        <el-button type="primary" :loading="exporting" @click="handleExport">导出下载</el-button>
      </div>
      <div class="tip" style="margin-top: 8px">
        仅导出质控合格病历（21字段），自动脱敏手机号/身份证号；筛选条件复用多条件病历查询。
      </div>

      <div v-if="preview.result" class="preview-box">
        <div class="preview-hd">预览：共 {{ preview.result.total }} 条合格病历（样本前10条，点击行查看完整详情）</div>
        <el-table
          :data="preview.result.sample"
          border
          size="small"
          max-height="300"
          highlight-current-row
          @row-click="(row) => (detail = row)"
        >
          <el-table-column prop="registrationNo" label="登记号" width="150" fixed />
          <el-table-column prop="gender" label="性别" width="60" />
          <el-table-column prop="age" label="年龄" width="60" />
          <el-table-column prop="westernDiagnosis" label="西医诊断" width="150" show-overflow-tooltip />
          <el-table-column prop="tcmDiagnosis" label="中医诊断" width="150" show-overflow-tooltip />
          <el-table-column prop="chiefComplaint" label="主诉" width="180" show-overflow-tooltip />
          <el-table-column prop="selfReport" label="自诉" width="180" show-overflow-tooltip />
          <el-table-column prop="presentIllness" label="现病史" width="200" show-overflow-tooltip />
          <el-table-column prop="inspection" label="望诊" width="120" show-overflow-tooltip />
          <el-table-column prop="pulse" label="脉诊" width="120" show-overflow-tooltip />
          <el-table-column prop="tongue" label="舌诊" width="140" show-overflow-tooltip />
          <el-table-column prop="physicalExam" label="查体" width="120" show-overflow-tooltip />
          <el-table-column prop="pattern" label="辨证结论" width="200" show-overflow-tooltip />
          <el-table-column prop="prescription" label="草药" width="260" show-overflow-tooltip />
          <el-table-column prop="followUp" label="随访" width="120" show-overflow-tooltip />
          <el-table-column prop="treatmentEffect" label="治疗效果" width="100" />
          <el-table-column prop="department" label="科室" width="90" />
          <el-table-column prop="doctorId" label="医生工号" width="90" />
          <el-table-column label="接诊时间" width="110">
            <template #default="{ row }">{{ (row.visitTime || '').substring(0, 10) }}</template>
          </el-table-column>
          <el-table-column prop="score" label="评分" width="60" fixed="right" />
          <el-table-column prop="grade" label="分级" width="70" fixed="right" />
        </el-table>
      </div>
    </PanelCard>

    <!-- 单条完整详情弹窗 -->
    <el-dialog v-model="detailVisible" title="病历完整详情" width="760px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item v-for="f in FIELDS" :key="f.key" :label="f.label" :span="f.wide ? 2 : 1">
            {{ fieldOf(detail, f.key) || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="评分">{{ detail.score }}</el-descriptions-item>
          <el-descriptions-item label="分级">{{ detail.grade }}</el-descriptions-item>
        </el-descriptions>
        <div class="sd-title">结构化数据（术语已归一，sourceText为原文溯源）</div>
        <pre class="sd-json">{{ prettyStructured(detail.structuredData) }}</pre>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import TermInput from '@/components/TermInput.vue'
import { clean as cleanApi, exportDataset, previewDataset, governanceStats } from '@/api/governance'
import { saveBlob } from '@/utils/download'

const STEPS = [
  { title: '去重', desc: '原始文本哈希重复标记无效，不删除' },
  { title: '字段清理', desc: '仅去空格 / 统一空值表示，绝不填值' },
  { title: '格式规整', desc: '剂量单位 / 日期展示统一写法' },
  { title: '脏数据隔离', desc: '仅"无法修复"的病历标记无效' },
  { title: '术语自动归一', desc: '精确-包含-模糊三级，content 替换为标准词' }
]

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

const prettyStructured = (s) => {
  if (!s) return '（无结构化数据）'
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

const stats = reactive({ qualified: 0, pendingGovern: 0, governedCount: 0 })

const loadStats = async () => {
  const res = await governanceStats()
  Object.assign(stats, res.data)
}

const clean = reactive({ loading: false, result: null })

const handleClean = async () => {
  clean.loading = true
  try {
    const res = await cleanApi({})
    clean.result = res.data
    ElMessage.success(`清洗完成：归一命中 ${res.data.normalized} 处`)
    loadStats()
  } finally {
    clean.loading = false
  }
}

const filters = reactive({ department: '', dateRange: null, pattern: '' })
const format = ref('csv')
const exporting = ref(false)

const buildPayload = () => ({
  format: format.value,
  filters: {
    department: filters.department || '',
    dateRange: filters.dateRange || [],
    pattern: filters.pattern || ''
  }
})

const preview = reactive({ loading: false, result: null })
const detail = ref(null)
const detailVisible = computed({
  get: () => !!detail.value,
  set: (v) => { if (!v) detail.value = null }
})

const handlePreview = async () => {
  preview.loading = true
  try {
    const res = await previewDataset(buildPayload())
    preview.result = res.data
    if (!res.data.total) ElMessage.warning('筛选范围内无质控合格病历')
  } finally {
    preview.loading = false
  }
}

const handleExport = async () => {
  exporting.value = true
  try {
    const blob = await exportDataset(buildPayload())
    if (blob && blob.type && blob.type.includes('application/json')) {
      const text = await blob.text()
      let msg = '导出失败'
      try {
        msg = JSON.parse(text).msg || msg
      } catch { /* keep default */ }
      ElMessage.error(msg)
      return
    }
    saveBlob(blob, `tcm_ehr_dataset_${Date.now()}.${format.value}`)
    ElMessage.success('导出成功')
  } finally {
    exporting.value = false
  }
}

onMounted(loadStats)
</script>

<style scoped>
/* ===== 治理状态行（顶部） ===== */
.gov-stats {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px 24px;
  margin-bottom: 20px;
  display: flex;
  gap: 48px;
}
.gs {
  font-size: 13px;
  color: var(--text-sub);
}
.gs b {
  font-size: 22px;
  color: var(--ink);
  margin-right: 6px;
}

/* ===== 流程说明条 ===== */
.flow-tip {
  background: var(--ink-light);
  border: 1px solid #cddcd2;
  border-radius: 4px;
  padding: 8px 14px;
  font-size: 12.5px;
  color: var(--ink-mid);
  line-height: 1.7;
  margin-bottom: 18px;
}

/* ===== 清洗流程图 ===== */
.flow-wrapper {
  display: flex;
  align-items: stretch;
  gap: 6px;
  margin-bottom: 20px;
}
.flow-step {
  display: flex;
  align-items: center;
  flex: 1;
  gap: 6px;
  min-width: 0;
}
.step-card {
  flex: 1;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 16px 8px;
  text-align: center;
  transition: transform 0.15s, box-shadow 0.15s;
}
.step-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 3px 10px rgba(47, 70, 57, 0.12);
}
.step-num {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--ink-mid);
  color: #fff;
  font-size: 14px;
  font-weight: bold;
  line-height: 30px;
  margin: 0 auto 8px;
}
.step-title {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  margin-bottom: 6px;
}
.step-desc {
  font-size: 11.5px;
  color: var(--text-sub);
  line-height: 1.5;
  padding: 0 4px;
}
.step-arrow {
  font-size: 20px;
  color: var(--ochre);
  flex-shrink: 0;
  font-weight: bold;
}

/* ===== 执行按钮区 ===== */
.clean-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 4px;
}
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
}

/* ===== 清洗结果（中部） ===== */
.clean-result {
  margin-top: 24px;
  border-top: 1px dashed #ece8dc;
  padding-top: 16px;
}
.result-hd {
  font-size: 14px;
  font-weight: bold;
  color: var(--ink);
  margin-bottom: 14px;
}
.clean-stats {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}
.stat-item {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px 16px;
  text-align: center;
}
.stat-item .num {
  font-size: 22px;
  font-weight: bold;
  color: var(--ink);
}
.stat-item .lbl {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 4px;
}
.stat-item.green .num { color: var(--ink-mid); }
.stat-item.ochre .num { color: var(--ochre); }
.stat-item.red .num { color: var(--danger); }

/* ===== 导出区 ===== */
.export-row {
  display: flex;
  gap: 16px;
  align-items: flex-end;
  flex-wrap: wrap;
  padding-bottom: 6px;
}
label {
  display: block;
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 3px;
}
.preview-box {
  margin-top: 16px;
  border-top: 1px dashed #ece8dc;
  padding-top: 12px;
}
.preview-hd {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  margin-bottom: 8px;
}
.sd-title {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  margin: 12px 0 6px;
}
.sd-json {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 10px;
  font-size: 12px;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
}

@media (max-width: 1200px) {
  .flow-wrapper { flex-wrap: wrap; }
  .flow-step { min-width: 180px; flex: 1 1 30%; }
  .clean-stats { grid-template-columns: repeat(3, 1fr); }
}
</style>