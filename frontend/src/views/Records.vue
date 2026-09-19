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
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="handleSearch"
        @size-change="handleSizeChange"
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
        :on-change="onFileChange"
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

      <!-- 失败态独立于上一次结果，避免误读为「本次结果」（UX-22） -->
      <div v-if="importFailed" class="import-failed">
        本次导入失败，请根据上方提示排查后重试（上一次结果已清除）。
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
      <el-form ref="createFormRef" :model="form" :rules="FORM_RULES" label-width="88px">
        <div class="form-grid">
          <el-form-item v-for="f in FIELDS" :key="f.key" :label="f.label" :prop="f.key" :class="{ wide: f.wide }">
            <!-- 性别改枚举下拉：自由文本会写进脏数据（UX-10） -->
            <el-select
              v-if="f.key === 'gender'"
              v-model="form.gender"
              placeholder="请选择"
              clearable
              style="width: 100%"
            >
              <el-option label="男" value="男" />
              <el-option label="女" value="女" />
            </el-select>
            <el-date-picker
              v-else-if="f.key === 'visitTime'"
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

    <el-dialog v-model="detailVisible" title="病历详情（原始字段只读）" width="min(780px, 92vw)">
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
const pageSize = ref(10)
const searching = ref(false)

const handleSearch = async () => {
  searching.value = true
  try {
    const res = await searchRecords({ ...query, page: page.value, pageSize: pageSize.value })
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    // 拦截器已提示
  } finally {
    searching.value = false
  }
}

/** 每页条数变化回到第 1 页（UX-24） */
const handleSizeChange = () => {
  page.value = 1
  handleSearch()
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
const MAX_FILE_MB = 50
const fileList = ref([])
const importing = ref(false)
const summary = ref(null)
const importFailed = ref(false)

const onExceed = () => ElMessage.warning('单次最多上传 20 个文件')

/** 前端预校验：类型与大小不合法直接剔除，不用等服务端返回（UX-27） */
const onFileChange = (file, list) => {
  const raw = file.raw
  if (!raw) return
  const name = (raw.name || '').toLowerCase()
  const reject = (reason) => {
    ElMessage.error(`「${raw.name}」${reason}`)
    const i = list.indexOf(file)
    if (i >= 0) list.splice(i, 1)
  }
  if (!name.endsWith('.xlsx') && !name.endsWith('.xls')) {
    reject('格式不支持，仅支持 .xlsx / .xls')
    return
  }
  if (raw.size > MAX_FILE_MB * 1024 * 1024) {
    reject(`超过 ${MAX_FILE_MB}MB 上限`)
  }
}

const handleImport = async () => {
  const formData = new FormData()
  fileList.value.forEach((f) => {
    if (f.raw) formData.append('files', f.raw)
  })
  // 发起即清空上一次结果并复位失败态，避免把旧结果误读成本次结果（UX-22）
  summary.value = null
  importFailed.value = false
  importing.value = true
  try {
    const res = await importRecords(formData)
    summary.value = res.data.summary
    ElMessage.success(`导入完成：成功 ${res.data.summary.success} 条，失败 ${res.data.summary.failed} 条`)
    fileList.value = []
    handleSearch()
  } catch {
    importFailed.value = true
  } finally {
    importing.value = false
  }
}

// ===== F·7.1 单条新增 =====
const emptyForm = () => FIELDS.reduce((o, f) => ({ ...o, [f.key]: '' }), {})
const form = reactive(emptyForm())
const creating = ref(false)
const createFormRef = ref(null)

/** 字段级校验（UX-10）：必填口径 + 数值范围 + 枚举 + 长度上限 */
const FORM_RULES = {
  registrationNo: [
    { required: true, message: '登记号不能为空', trigger: 'blur' },
    { max: 64, message: '登记号不超过 64 字', trigger: 'blur' }
  ],
  outpatientNo: [
    { required: true, message: '门诊号不能为空', trigger: 'blur' },
    { max: 64, message: '门诊号不超过 64 字', trigger: 'blur' }
  ],
  gender: [{ pattern: /^(男|女)$/, message: '性别只能选「男」或「女」', trigger: 'change' }],
  age: [
    {
      validator: (rule, value, cb) => {
        if (value === '' || value == null) return cb()
        const n = Number(value)
        if (!Number.isFinite(n) || n < 0 || n > 150) return cb(new Error('年龄需为 0~150 的数值'))
        cb()
      },
      trigger: 'blur'
    }
  ],
  visitCount: [
    {
      validator: (rule, value, cb) => {
        if (value === '' || value == null) return cb()
        const n = Number(value)
        if (!Number.isInteger(n) || n < 1) return cb(new Error('就诊次数需为不小于 1 的整数'))
        cb()
      },
      trigger: 'blur'
    }
  ]
}

const resetForm = () => {
  Object.assign(form, emptyForm())
  createFormRef.value?.clearValidate()
}

const handleCreate = async () => {
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  creating.value = true
  try {
    const payload = { ...form }
    if (!payload.visitTime) delete payload.visitTime
    if (payload.visitCount === '' || payload.visitCount == null) delete payload.visitCount
    if (payload.age === '' || payload.age == null) delete payload.age
    await createRecord(payload)
    ElMessage.success(`新增成功：登记号 ${payload.registrationNo}`)
    resetForm()
    // 回到第 1 页并刷新，让用户立刻确认已入库（UX-09）
    page.value = 1
    await handleSearch()
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
.import-failed {
  margin-top: 14px;
  padding: 8px 12px;
  background: #fdf6f4;
  border: 1px solid #e3c3bb;
  border-radius: 4px;
  font-size: 12.5px;
  color: var(--danger);
}
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
