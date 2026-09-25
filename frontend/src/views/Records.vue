<template>
  <div>
    <!-- 三块能力改为标签页切换（UX-51）：原先「查表 / 批量导入 / 单条新增」纵向堆叠，
         用户到达表单本身就要滚动整屏；切换后每屏只面对一件事 -->
    <el-tabs v-model="activeTab" class="records-tabs">
      <!-- ============ 病历代查 ============ -->
      <el-tab-pane label="病历代查" name="query">
        <PanelCard title="病历代查">
          <RangeFilter v-model="query" />
          <div class="actions">
            <el-button type="primary" :loading="searching" @click="handleSearch">查 询</el-button>
            <el-button :disabled="searching" @click="handleReset">重置</el-button>
            <el-button
              type="danger"
              plain
              :disabled="searching || !selectedIds.length"
              @click="handleBatchDelete"
            >批量删除{{ selectedIds.length ? `（${selectedIds.length}）` : '' }}</el-button>
            <span class="tip-inline">共 {{ total }} 条</span>
          </div>

          <el-table
            ref="tableRef"
            v-loading="searching"
            :data="rows"
            border
            size="small"
            style="margin-top: 12px"
            max-height="420"
            :row-class-name="rowClass"
            @selection-change="onSelectionChange"
          >
            <el-table-column type="selection" width="46" />
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

        <!-- 详情改回弹窗（UX-69）：用户第六轮明确指定用弹窗，属 UX-66 的例外。
             弹窗内仍是左右两栏对照（UX-65），排版与「病历完整详情」共用同一组件 -->
        <RecordDetailDialog v-model="detailVisible" :record="raw" />
      </el-tab-pane>

      <!-- ============ 病历批量导入 ============ -->
      <el-tab-pane label="病历批量导入" name="import" lazy>
        <PanelCard title="病历批量导入">
          <div class="tip">
            支持 .xlsx / .xls，单文件 ≤50MB、单次 ≤20 个文件；按「登记号」等 21 字段解析入库，
            与治理清洗同一去重口径（21 字段完全一致视为重复，跳过并记录）。
          </div>
          <div class="import-auto">
            <el-switch v-model="autoExtract" />
            <span>导入后自动结构化解析（后台任务，需抽取服务已开启）</span>
            <span class="tip">开启后导入秒回，解析交由后台队列，可在「结构化解析」页看进度</span>
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
            <el-button v-if="importing" :disabled="cancelled" @click="cancelImport">取消</el-button>
            <el-button v-else :disabled="!fileList.length" @click="fileList = []">清空</el-button>
          </div>

          <!-- 逐文件进度（UX-15）：给出「第 n/N 个」与已入库统计，并说明不可关页面 -->
          <div v-if="importing" class="import-progress">
            <div class="ip-hd">
              正在导入第 {{ Math.min(progress.done + 1, progress.total) }}/{{ progress.total }} 个文件：
              <b>{{ progress.current || '准备中…' }}</b>
            </div>
            <el-progress
              :percentage="progress.total ? Math.round((progress.done / progress.total) * 100) : 0"
              :stroke-width="10"
            />
            <div class="ip-sub">
              已入库 {{ progress.success }} 条，失败/跳过 {{ progress.failed }} 条；导入期间请勿关闭或刷新页面
            </div>
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
      </el-tab-pane>

      <!-- ============ 单条新增病历 ============ -->
      <el-tab-pane label="单条新增病历" name="create" lazy>
        <PanelCard title="单条新增病历">
          <!-- 紧凑口径（UX-51 第七轮修订）：标签左置 + 控件 small + 文本域单行起步。
               第六轮只做了「分区常显 + 3 列栅格」，控件仍是 32px、标签各占一行，
               用户实测仍要下拉；本轮直接压控件高度（.compact-form 见 theme.css） -->
          <el-form
            ref="createFormRef"
            class="compact-form"
            :model="form"
            :rules="FORM_RULES"
            label-width="72px"
            size="small"
          >
            <!-- 语义分区 + 多列栅格（UX-51 修订）：原先 21 字段平铺是 1000px+ 长表单，
                 改成折叠分组后用户仍要逐组展开、整页依旧要滚动。
                 现改为分区常显 + 3 列栅格：21 字段压到约 12 行，常规屏幕一屏内可填完，
                 校验失败的红字也直接可见（不再藏在折叠区里） -->
            <div v-for="g in FIELD_GROUPS" :key="g.title" class="form-group">
              <div class="group-hd">{{ g.title }}</div>
              <div class="form-grid">
                <el-form-item v-for="f in fieldsOf(g)" :key="f.key" :label="f.label" :prop="f.key" :class="{ wide: f.wide }">
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
                  <el-input
                    v-else
                    v-model="form[f.key]"
                    :type="f.multi ? 'textarea' : 'text'"
                    :rows="1"
                    :autosize="f.multi ? { minRows: 1, maxRows: 2 } : false"
                    clearable
                  />
                </el-form-item>
              </div>
            </div>
            <div class="actions create-actions">
              <el-button type="primary" :loading="creating" @click="handleCreate">新增病历</el-button>
              <el-button :disabled="creating" @click="resetForm">重置</el-button>
            </div>
          </el-form>
        </PanelCard>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import RecordDetailDialog from '@/components/RecordDetailDialog.vue'
import {
  importRecords, createRecord, searchRecords, getRawRecord, deleteRecords
} from '@/api/records'
import { fmtDateTime } from '@/utils/format'
import { useAiStore } from '@/stores/ai'

const aiStore = useAiStore()

// 当前标签页（UX-51）；导入与新增懒加载，首屏只渲染查询表
const activeTab = ref('query')

/**
 * 21 个原始字段（UX-51）。
 *
 * <p>`wide` 只留给真正需要整行宽度的长叙述（主诉 / 自诉 / 现病史 / 草药）；
 * `multi` 表示用文本域（单行起步、随输入自增），其余短字段走单行输入。
 * 第七轮之前 wide 有 11 个，span 2 在 3 列栅格里排不紧，纵向白白多出 4 行，
 * 这也是用户反复说「填写框还是太大」的直接原因。</p>
 */
const FIELDS = [
  { key: 'registrationNo', label: '登记号' },
  { key: 'outpatientNo', label: '门诊号' },
  { key: 'gender', label: '性别' },
  { key: 'age', label: '年龄' },
  { key: 'visitCount', label: '就诊次数' },
  { key: 'westernDiagnosis', label: '西医诊断', multi: true },
  { key: 'tcmDiagnosis', label: '中医诊断', multi: true },
  { key: 'chiefComplaint', label: '主诉', multi: true, wide: true },
  { key: 'selfReport', label: '自诉', multi: true, wide: true },
  { key: 'presentIllness', label: '现病史', multi: true, wide: true },
  { key: 'inspection', label: '望诊', multi: true },
  { key: 'pulse', label: '脉诊', multi: true },
  { key: 'tongue', label: '舌诊', multi: true },
  { key: 'physicalExam', label: '查体', multi: true },
  { key: 'pattern', label: '辨证结论', multi: true },
  { key: 'prescription', label: '草药', multi: true, wide: true },
  { key: 'followUp', label: '随访', multi: true },
  { key: 'treatmentEffect', label: '治疗效果', multi: true },
  { key: 'department', label: '开单科室' },
  { key: 'doctorId', label: '医生工号' },
  { key: 'visitTime', label: '接诊时间' }
]

/**
 * 单条新增的分区（UX-51）：21 个字段平铺会产生 1000px+ 的长表单，
 * 按语义分 5 组，只有含必填项的第一组默认展开。
 */
const FIELD_MAP = FIELDS.reduce((m, f) => ({ ...m, [f.key]: f }), {})
const FIELD_GROUPS = [
  { title: '基本信息', keys: ['registrationNo', 'outpatientNo', 'gender', 'age', 'visitCount', 'department', 'doctorId', 'visitTime'] },
  { title: '主诉与病史', keys: ['chiefComplaint', 'selfReport', 'presentIllness'] },
  { title: '四诊', keys: ['inspection', 'tongue', 'pulse', 'physicalExam'] },
  { title: '诊断', keys: ['westernDiagnosis', 'tcmDiagnosis', 'pattern'] },
  { title: '处方与随访', keys: ['prescription', 'followUp', 'treatmentEffect'] }
]
const fieldsOf = (group) => group.keys.map((k) => FIELD_MAP[k]).filter(Boolean)

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

// ===== 详情（弹窗，UX-69）=====
const raw = ref(null)
const detailVisible = ref(false)
const activeId = ref('')

/** 当前查看行高亮，便于在长表里对上号 */
const rowClass = ({ row }) => (row.id === activeId.value ? 'row-active' : '')

const openDetail = async (id) => {
  try {
    const res = await getRawRecord(id)
    raw.value = res.data
    activeId.value = id
    // 写入共享状态，供 AI 助手"这份病历…"与解读卡使用
    aiStore.setActiveRecord(res.data)
    detailVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

/** 只收起弹窗、保留 raw：否则关闭动画期间内容会闪空（UX-69） */
const closeDetail = () => {
  detailVisible.value = false
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
    if (activeId.value === id) {
      closeDetail()
      raw.value = null
      activeId.value = ''
    }
    tableRef.value?.clearSelection()
    selectedIds.value = []
    handleSearch()
  } catch {
    // 拦截器已提示
  }
}

/** 批量删除：表格多选 → 一次提交 ids */
const tableRef = ref(null)
const selectedIds = ref([])
const onSelectionChange = (rows) => {
  selectedIds.value = rows.map((r) => r.id)
}
const handleBatchDelete = async () => {
  const ids = selectedIds.value
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(
      `确认删除选中的 ${ids.length} 份病历？删除后不可恢复（会留痕）。`,
      '批量删除病历',
      { type: 'warning', confirmButtonText: `删除 ${ids.length} 条`, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await deleteRecords(ids)
    const n = res.data?.deletedCount ?? ids.length
    ElMessage.success(`已删除 ${n} 份病历`)
    if (ids.includes(activeId.value)) {
      closeDetail()
      raw.value = null
      activeId.value = ''
    }
    tableRef.value?.clearSelection()
    selectedIds.value = []
    handleSearch()
  } catch {
    // 拦截器已提示
  }
}

// ===== F·7.1 导入 =====
const MAX_FILE_MB = 50
const fileList = ref([])
/** 导入后自动结构化解析（默认关；需抽取服务已开启） */
const autoExtract = ref(false)
const importing = ref(false)
const summary = ref(null)
const importFailed = ref(false)
// 逐文件分批上传的进度（UX-15）：后端导入是同步接口，拿不到中间 taskId，
// 因此按「文件」粒度推进度 —— 既真实可取消，也避免单次超大请求
const progress = reactive({ done: 0, total: 0, current: '', success: 0, failed: 0 })
const cancelled = ref(false)

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
  const files = fileList.value.map((f) => f.raw).filter(Boolean)
  if (!files.length) return
  // 发起即清空上一次结果并复位失败态，避免把旧结果误读成本次结果（UX-22）
  summary.value = null
  importFailed.value = false
  cancelled.value = false
  progress.done = 0
  progress.total = files.length
  progress.current = ''
  progress.success = 0
  progress.failed = 0
  importing.value = true
  let autoTaskSubmitted = false
  try {
    const failures = []
    for (let i = 0; i < files.length; i++) {
      if (cancelled.value) break
      progress.current = files[i].name
      const fd = new FormData()
      fd.append('files', files[i])
      fd.append('autoExtract', autoExtract.value ? 'true' : 'false')
      const res = await importRecords(fd)
      const s = res.data.summary || {}
      if (res.data.autoExtractTaskId) autoTaskSubmitted = true
      progress.success += s.success || 0
      progress.failed += s.failed || 0
      if (s.failures && s.failures.length) failures.push(...s.failures)
      progress.done = i + 1
    }
    summary.value = {
      total: progress.success + progress.failed,
      success: progress.success,
      failed: progress.failed,
      failures
    }
    const tail = cancelled.value ? '（已取消，未处理剩余文件）' : ''
    const autoTail = autoTaskSubmitted ? '；已提交后台结构化解析' : ''
    ElMessage.success(`导入完成：成功 ${progress.success} 条，失败 ${progress.failed} 条${autoTail}${tail}`)
    fileList.value = []
    handleSearch()
  } catch {
    importFailed.value = true
  } finally {
    importing.value = false
    progress.current = ''
  }
}

/** 取消：当前文件完成后不再提交后续文件，已入库的不回滚 */
const cancelImport = () => {
  cancelled.value = true
  ElMessage.info('已取消，正在处理中的文件完成后停止')
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
    // 切回查表页并回到第 1 页刷新，让用户立刻确认已入库（UX-09）
    page.value = 1
    await handleSearch()
    activeTab.value = 'query'
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

onMounted(handleSearch)
</script>

<style scoped>
/* 标签页（UX-51）：去掉底部分隔线，避免与面板边框叠成双线 */
.records-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
.records-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
  line-height: 1.7;
  margin-bottom: 14px;
}
.tip-inline {
  font-size: 12.5px;
  color: var(--text-sub);
}
.import-auto {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  font-size: 12.5px;
  color: var(--ink);
}
.import-auto .tip {
  margin: 0;
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
/* 逐文件导入进度（UX-15） */
.import-progress {
  margin-top: 14px;
  padding: 10px 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
}
.ip-hd {
  font-size: 12.5px;
  color: var(--text);
  margin-bottom: 8px;
}
.ip-hd b {
  color: var(--ink);
}
.ip-sub {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-sub);
}
.result-hd { font-size: 14px; font-weight: bold; color: var(--ink); margin-bottom: 12px; }
.stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
.stat-item { background: #fff; border: 1px solid var(--line); border-radius: 6px; padding: 12px 16px; text-align: center; }
.stat-item .num { font-size: 22px; font-weight: bold; color: var(--ink); }
.stat-item .lbl { font-size: 12px; color: var(--text-sub); margin-top: 4px; }
.stat-item.green .num { color: var(--ink-mid); }
.stat-item.red .num { color: var(--danger); }
/* 多列栅格（UX-51 修订）：3 列时 21 字段压到约 11 行，常规屏幕一屏可填完。
   wide（长文本）占 2 列而非整行 —— 否则每行拉满宽度、纵向白白多出数行；
   第七轮进一步只把主诉 / 自诉 / 现病史 / 草药 4 项定为 wide（原先 11 项），
   并把控件高度统一压到 small（24px，见 theme.css 的 .compact-form） */
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 12px;
}
.form-grid .wide {
  grid-column: span 2;
}
.form-grid :deep(.el-form-item) {
  margin-bottom: 6px;
}
.form-grid :deep(.el-form-item__label) {
  font-size: 12px;
  line-height: 1.5;
  padding-bottom: 0;
}
/* 分区常显（UX-51 修订）：不再折叠，标题只作视觉分隔 */
.form-group {
  margin-bottom: 4px;
}
.group-hd {
  position: relative;
  padding: 3px 0 4px 9px;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: bold;
  color: var(--ink);
  border-bottom: 1px solid var(--line);
}
.group-hd::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  width: 3px;
  height: 12px;
  background: var(--ink-mid);
}
/* 提交按钮吸底，长表单滚动时始终可见（UX-51） */
.create-actions {
  position: sticky;
  bottom: 0;
  background: #fff;
  padding: 8px 0;
  border-top: 1px solid var(--line);
  z-index: 1;
}

:deep(.row-active) td {
  background: var(--ink-light) !important;
}
/* 栅格降级（UX-51 第七轮，按真机量测定断点）：
   1500px 以下如果取消 span 2，21 字段从 11 行降到 8 行，比降到 2 列更省高度；
   1200px 以下 3 列每列已不足 320px，标签左置后控件过窄，才收 2 列 */
@media (max-width: 1559px) {
  .form-grid .wide { grid-column: span 1; }
}
@media (max-width: 1199px) {
  .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 900px) {
  .form-grid { grid-template-columns: 1fr; }
}
</style>
