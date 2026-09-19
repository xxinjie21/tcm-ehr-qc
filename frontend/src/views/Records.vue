<template>
  <div>
    <PanelCard title="病历代查">
      <RangeFilter v-model="query" />
      <div class="actions">
        <el-button type="primary" :loading="searching" @click="handleSearch">查 询</el-button>
        <el-button :disabled="searching" @click="handleReset">重置</el-button>
        <span class="tip">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="searching" :data="rows" border size="small" style="margin-top: 12px" max-height="420">
        <el-table-column prop="id" label="病历ID" width="320" show-overflow-tooltip />
        <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">查看</el-button>
            <el-button link type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="无符合条件的病历" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="handleSearch"
      />
    </PanelCard>

    <PanelCard title="病历批量导入">
      <div class="tip">
        支持 .xlsx / .xls，单文件 ≤50MB、单次 ≤20 个文件；按「登记号」等 21 字段解析入库，
        与治理清洗同一去重口径（21 字段完全一致视为重复，跳过并记录）。
      </div>

      <el-upload
        v-model:file-list="fileList"
        class="uploader"
        drag
        multiple
        :auto-upload="false"
        :limit="20"
        accept=".xlsx,.xls"
        :on-exceed="onExceed"
      >
        <div class="up-inner">
          <div class="up-title">将 Excel 拖到此处，或<em>点击选择</em></div>
          <div class="up-sub">可多选，仅 .xlsx / .xls</div>
        </div>
      </el-upload>

      <div class="actions">
        <el-button type="primary" :loading="importing" :disabled="!fileList.length" @click="handleImport">
          {{ importing ? '导入中…' : '开始导入' }}
        </el-button>
        <el-button :disabled="!fileList.length || importing" @click="fileList = []">清空</el-button>
      </div>

      <div v-if="summary" class="result">
        <div class="result-hd">导入结果</div>
        <div class="stats">
          <div class="stat-item"><div class="num">{{ summary.total }}</div><div class="lbl">有效数据行</div></div>
          <div class="stat-item green"><div class="num">{{ summary.success }}</div><div class="lbl">成功入库</div></div>
          <div class="stat-item red"><div class="num">{{ summary.failed }}</div><div class="lbl">失败 / 跳过</div></div>
        </div>
        <el-table v-if="summary.failures && summary.failures.length" :data="summary.failures" border size="small" max-height="260">
          <el-table-column prop="filename" label="文件" width="240" show-overflow-tooltip />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
        </el-table>
      </div>
    </PanelCard>

    <PanelCard title="单条新增病历">
      <el-form :model="form" label-width="88px">
        <div class="form-grid">
          <el-form-item v-for="f in FIELDS" :key="f.key" :label="f.label" :class="{ wide: f.wide }">
            <el-date-picker
              v-if="f.key === 'visitTime'"
              v-model="form.visitTime"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="接诊时间"
              style="width: 100%"
            />
            <el-input v-else v-model="form[f.key]" :type="f.wide ? 'textarea' : 'text'" :rows="f.wide ? 2 : 1" clearable />
          </el-form-item>
        </div>
        <div class="actions">
          <el-button type="primary" :loading="creating" @click="handleCreate">新增病历</el-button>
          <el-button :disabled="creating" @click="resetForm">重置</el-button>
        </div>
      </el-form>
    </PanelCard>

    <el-dialog v-model="detailVisible" title="病历详情（原始字段只读）" width="780px">
      <template v-if="raw">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item v-for="f in FIELDS" :key="f.key" :label="f.label" :span="f.wide ? 2 : 1">
            {{ fieldOf(raw, f.key) || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="评分">{{ raw.score ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="分级">{{ raw.grade || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="sd-title">结构化数据（sourceText 为原文溯源）</div>
        <StructuredDataCard :data="raw.structuredData" />
        <AiInterpretCard :record-id="raw.id" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import StructuredDataCard from '@/components/StructuredDataCard.vue'
import AiInterpretCard from '@/components/AiInterpretCard.vue'
import {
  importRecords, createRecord, searchRecords, getRawRecord, deleteRecords
} from '@/api/records'
import { useAiStore } from '@/stores/ai'

const aiStore = useAiStore()

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
  if (key === 'visitTime') return row.visitTime ? String(row.visitTime).replace('T', ' ').substring(0, 19) : ''
  return row[key]
}

// ===== F·7.4 查询 =====
const query = reactive({ department: '', dateRange: null, pattern: '', grade: '' })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const searching = ref(false)

const handleSearch = async () => {
  searching.value = true
  try {
    const res = await searchRecords({ ...query, page: page.value, pageSize })
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    // 拦截器已提示
  } finally {
    searching.value = false
  }
}

const handleReset = () => {
  query.department = ''
  query.dateRange = null
  query.pattern = ''
  query.grade = ''
  page.value = 1
  handleSearch()
}

// ===== 详情 / 删除 =====
const raw = ref(null)
const detailVisible = ref(false)

const openDetail = async (id) => {
  try {
    const res = await getRawRecord(id)
    raw.value = res.data
    detailVisible.value = true
    // 写入共享状态，供 AI 助手"这份病历…"与解读卡使用
    aiStore.setActiveRecord(res.data)
  } catch {
    // 拦截器已提示
  }
}

const handleDelete = async (id) => {
  try {
    await ElMessageBox.confirm('确认删除该病历？删除后不可恢复（会留痕）。', '删除病历', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteRecords([id])
    ElMessage.success('删除成功')
    handleSearch()
  } catch {
    // 拦截器已提示
  }
}

// ===== F·7.1 导入 =====
const fileList = ref([])
const importing = ref(false)
const summary = ref(null)

const onExceed = () => ElMessage.warning('单次最多上传 20 个文件')

const handleImport = async () => {
  const formData = new FormData()
  fileList.value.forEach((f) => {
    if (f.raw) formData.append('files', f.raw)
  })
  importing.value = true
  try {
    const res = await importRecords(formData)
    summary.value = res.data.summary
    ElMessage.success(`导入完成：成功 ${res.data.summary.success} 条，失败 ${res.data.summary.failed} 条`)
    fileList.value = []
  } catch {
    // 拦截器已提示
  } finally {
    importing.value = false
  }
}

// ===== F·7.1 单条新增 =====
const emptyForm = () => FIELDS.reduce((o, f) => ({ ...o, [f.key]: '' }), {})
const form = reactive(emptyForm())
const creating = ref(false)

const resetForm = () => Object.assign(form, emptyForm())

const handleCreate = async () => {
  if (!form.registrationNo) {
    ElMessage.warning('登记号不能为空')
    return
  }
  if (!form.outpatientNo) {
    ElMessage.warning('门诊号不能为空')
    return
  }
  creating.value = true
  try {
    const payload = { ...form }
    if (!payload.visitTime) delete payload.visitTime
    if (payload.visitCount === '' || payload.visitCount == null) delete payload.visitCount
    await createRecord(payload)
    ElMessage.success('新增成功')
    resetForm()
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

onMounted(handleSearch)
</script>

<style scoped>
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
  line-height: 1.7;
  margin-bottom: 14px;
}
.uploader :deep(.el-upload-dragger) {
  padding: 26px 10px;
  border: 1px dashed var(--line);
  background: var(--paper);
}
.up-inner { text-align: center; }
.up-title { font-size: 13.5px; color: var(--ink); }
.up-title em { color: var(--ink-mid); font-style: normal; font-weight: bold; }
.up-sub { font-size: 12px; color: var(--text-sub); margin-top: 4px; }
.actions { margin-top: 14px; display: flex; gap: 10px; align-items: center; }
.result { margin-top: 18px; border-top: 1px dashed #ece8dc; padding-top: 14px; }
.result-hd { font-size: 14px; font-weight: bold; color: var(--ink); margin-bottom: 12px; }
.stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
.stat-item { background: #fff; border: 1px solid var(--line); border-radius: 6px; padding: 12px 16px; text-align: center; }
.stat-item .num { font-size: 22px; font-weight: bold; color: var(--ink); }
.stat-item .lbl { font-size: 12px; color: var(--text-sub); margin-top: 4px; }
.stat-item.green .num { color: var(--ink-mid); }
.stat-item.red .num { color: var(--danger); }
.form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0 18px; }
.form-grid .wide { grid-column: 1 / -1; }
.sd-title { font-size: 13px; font-weight: bold; color: var(--ink); margin: 14px 0 8px; }
@media (max-width: 1200px) {
  .form-grid { grid-template-columns: 1fr; }
}
</style>
