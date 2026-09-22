<template>
  <div>
    <!-- 治理状态行（顶部） -->
    <section v-loading="statsLoading" class="gov-stats">
      <span class="gs"><b>{{ stats.qualified ?? 0 }}</b> 质控合格病历</span>
      <span class="gs"><b>{{ stats.pendingGovern ?? 0 }}</b> 待治理</span>
      <span class="gs"><b>{{ stats.governedCount ?? 0 }}</b> 已治理</span>
      <!-- 失败态与「确实为 0」区分开，避免用户把旧值当最新结果（UX-21） -->
      <span v-if="statsFailed" class="gs-fail">
        统计加载失败{{ statsLoadedAt ? `（上次成功 ${statsLoadedAt}）` : '' }}
        <el-button link type="primary" size="small" @click="loadStats">重试</el-button>
      </span>
    </section>

    <!-- 当前范围（先选范围 → 后续操作只作用于范围内） -->
    <section class="scope-bar">
      <RangeFilter v-model="filters" />
      <div class="scope-row">
        <span class="scope-tip">当前范围：<b>{{ scopeText }}</b></span>
        <el-button type="warning" :loading="recomputing" @click="handleRecompute">
          {{ recomputing ? '重算执行中…' : '质控评分重算' }}
        </el-button>
        <!-- 全库重算耗时随数据量增长，执行期间给出预期（UX-37） -->
        <span v-if="recomputing" class="tip">正在按规则重算范围内全部病历，数据量大时需数分钟，请勿关闭页面</span>
      </div>
    </section>

    <!-- 数据清洗与术语归一（流程图） -->
    <PanelCard title="数据清洗与术语自动归一">
      <!-- 总述只保留这一处（UX-57）；每步的一句话解释回到步骤卡内（UX-76） -->
      <div class="flow-tip">
        清洗<b>不会填充医生未书写的内容</b>，也<b>不会删除任何病历</b>；术语按最新词典统一为标准写法。
      </div>

      <div class="flow-wrapper">
        <div v-for="(s, i) in STEPS" :key="s.title" class="flow-step">
          <div class="step-card">
            <div class="step-num">{{ i + 1 }}</div>
            <div class="step-title">{{ s.title }}</div>
            <!-- 还原每步解释（UX-76）：UX-57 收敛过度，5 步说明全收进折叠区后
                 步骤卡只剩序号与标题，用户看不出每步到底做什么 -->
            <div class="step-desc">{{ s.desc }}</div>
          </div>
          <!-- 末步留占位箭头，保证 5 张卡片等宽（UX-64） -->
          <div class="step-arrow" :class="{ ghost: i === STEPS.length - 1 }" aria-hidden="true">→</div>
        </div>
      </div>

      <div class="clean-actions">
        <el-button type="primary" size="large" :loading="clean.loading" @click="handleClean">
          {{ clean.loading ? '清洗执行中…' : '执行数据清洗' }}
        </el-button>
        <!-- 执行期间说明「在做什么、要等多久、结果在哪看」，而不是只转一个圈（UX-37） -->
        <span v-if="clean.loading" class="tip">
          正在按 5 步依次处理，请勿关闭页面；完成后下方会给出分步结果
        </span>
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
        <div v-if="clean.result.normByLevel" class="level-dist">
          <span class="ld-lbl">三级命中分布</span>
          <span class="ld exact">精确 {{ clean.result.normByLevel.exact ?? 0 }}</span>
          <span class="ld contain">包含 {{ clean.result.normByLevel.contain ?? 0 }}</span>
          <span class="ld fuzzy">模糊 {{ clean.result.normByLevel.fuzzy ?? 0 }}</span>
        </div>
      </div>
    </PanelCard>

    <!-- 标准数据集导出（中下部） -->
    <PanelCard title="标准数据集导出">
      <div class="export-row">
        <div>
          <label for="ex-format">导出格式</label>
          <el-radio-group id="ex-format" v-model="format">
            <el-radio-button value="csv">CSV</el-radio-button>
            <el-radio-button value="json">JSON</el-radio-button>
          </el-radio-group>
        </div>
        <div>
          <label for="ex-department">科室</label>
          <el-select id="ex-department" v-model="filters.department" placeholder="全部科室" clearable style="width: 130px">
            <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
          </el-select>
        </div>
        <div>
          <!-- 同 RangeFilter：范围选择器的 id 必须传数组（内部两个 input） -->
          <label for="ex-date-start">就诊时间</label>
          <el-date-picker
            :id="['ex-date-start', 'ex-date-end']"
            v-model="filters.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="截止"
            style="width: 240px"
          />
        </div>
        <div>
          <label for="ex-pattern">证候</label>
          <TermInput id="ex-pattern" v-model="filters.pattern" type="pattern" placeholder="如：肝肾亏虚" style="width: 150px" />
        </div>
        <el-button @click="handlePreview" :loading="preview.loading">预览数据集</el-button>
        <el-button type="primary" :loading="exporting" @click="handleExport">导出下载</el-button>
      </div>
      <div class="tip" style="margin-top: 8px">
        只导出质控合格的病历，手机号与身份证号会自动脱敏。
      </div>

      <div v-if="preview.result" class="preview-box">
        <div class="preview-hd">
          <span>预览：共 {{ preview.result.total }} 条合格病历（样本前10条，点击行查看完整详情）</span>
          <!-- 21 列全出会横向滚很长，改为默认只显示关键列，其余按需勾选（UX-38） -->
          <el-popover placement="bottom-end" :width="260" trigger="click">
            <template #reference>
              <el-button size="small" plain>
                列显示（{{ visibleCols.length }}/{{ PREVIEW_COLS.length }}）
              </el-button>
            </template>
            <div class="col-picker">
              <el-checkbox-group v-model="visibleCols">
                <el-checkbox v-for="c in PREVIEW_COLS" :key="c.prop" :value="c.prop">{{ c.label }}</el-checkbox>
              </el-checkbox-group>
              <div class="col-picker-actions">
                <el-button size="small" @click="resetCols">恢复默认</el-button>
                <el-button size="small" @click="visibleCols = PREVIEW_COLS.map((c) => c.prop)">全选</el-button>
              </div>
            </div>
          </el-popover>
        </div>
        <el-table
          :data="preview.result.sample"
          border
          size="small"
          max-height="520"
          highlight-current-row
          @row-click="openDetail"
        >
          <el-table-column
            v-for="c in visibleColsList"
            :key="c.prop"
            :prop="c.prop"
            :label="c.label"
            :width="c.width"
            :formatter="c.formatter"
            show-overflow-tooltip
          />
          <!-- 行内「查看」入口：键盘用户也能打开详情，且给鼠标用户明确的「可点」提示（UX-18） -->
          <el-table-column label="操作" width="70" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openDetail(row)">查看</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="筛选范围内没有质控合格的病历" :image-size="80" />
          </template>
        </el-table>
      </div>
    </PanelCard>

    <!-- 单条完整详情改弹窗（UX-77）：排版与病历数据页的「病历详情」共用同一组件 -->
    <RecordDetailDialog v-model="detailVisible" :record="detail" title="病历完整详情" />
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import TermInput from '@/components/TermInput.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import RecordDetailDialog from '@/components/RecordDetailDialog.vue'
import { clean as cleanApi, exportDataset, previewDataset, governanceStats, recomputeQc } from '@/api/governance'
import { getDepartments } from '@/api/stats'
import { saveBlob } from '@/utils/download'
import { useAiStore } from '@/stores/ai'

const aiStore = useAiStore()

const STEPS = [
  { title: '去重', desc: '重复病历只标记，不删除' },
  { title: '字段清理', desc: '只去多余空格，不改内容' },
  { title: '格式规整', desc: '统一剂量与日期的写法' },
  { title: '脏数据隔离', desc: '无法修复的病历标记为无效' },
  { title: '术语归一', desc: '把「咽喉痛」这类写法统一成标准术语' }
]

const stats = reactive({ qualified: 0, pendingGovern: 0, governedCount: 0 })
const statsLoading = ref(false)
// 写操作失败后数字会停在旧值，需显式失败态 + 上次成功时间，
// 否则用户会把这些数字当成最新结果（UX-21）
const statsFailed = ref(false)
const statsLoadedAt = ref('')

// 当前范围（批B·4.1）：清洗 / 质控重算 只作用于该范围（filters 见下方声明）
const scopeText = computed(() => {
  const parts = []
  if (filters.department) parts.push(filters.department)
  if (filters.dateRange && filters.dateRange.length === 2) parts.push(`${filters.dateRange[0]}~${filters.dateRange[1]}`)
  if (filters.pattern) parts.push(filters.pattern)
  if (filters.grade) parts.push(filters.grade)
  return parts.length ? parts.join(' · ') : '全部'
})

const recomputing = ref(false)
const handleRecompute = async () => {
  try {
    await ElMessageBox.confirm(
      `将对「${scopeText.value}」范围内的病历按质控规则重算评分与分级（覆盖现有分数），确认？`,
      '质控评分重算',
      { type: 'warning', confirmButtonText: '确认重算', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  recomputing.value = true
  try {
    const res = await recomputeQc({ filters: { ...filters } })
    const d = res.data
    ElMessage.success(`重算完成：合格 ${d.qualified}，待复核 ${d.pendingReview}，无效 ${d.invalid}，失败 ${d.failed}`)
    loadStats()
  } catch {
    // 拦截器已提示
  } finally {
    recomputing.value = false
  }
}

const loadStats = async () => {
  statsLoading.value = true
  statsFailed.value = false
  try {
    const res = await governanceStats()
    Object.assign(stats, res.data)
    aiStore.setStats(res.data)
    statsLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch {
    // 标记失败态，状态行据此提示「显示的可能不是最新值」（UX-21）
    statsFailed.value = true
  } finally {
    statsLoading.value = false
  }
}

const clean = reactive({ loading: false, result: null })

const handleClean = async () => {
  try {
    await ElMessageBox.confirm(
      `将对「${scopeText.value}」范围内的病历执行数据清洗：去重只标记、不删除，也不会填充医生未书写的内容。确认？`,
      '数据清洗',
      { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  clean.loading = true
  try {
    const res = await cleanApi({ filters: { ...filters } })
    clean.result = res.data
    aiStore.setNormByLevel(res.data.normByLevel || { exact: 0, contain: 0, fuzzy: 0 })
    ElMessage.success(`清洗完成：归一命中 ${res.data.normalized} 处`)
    loadStats()
  } catch {
    // 拦截器已提示
  } finally {
    clean.loading = false
  }
}

const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })
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

/**
 * 预览表列定义（UX-38）。21 列全出横向滚动很长，默认只显示关键列，
 * 其余在「列显示」里按需勾选；formatter 处理接诊时间这类要截断展示的字段。
 */
const PREVIEW_COLS = [
  { prop: 'registrationNo', label: '登记号', width: 150 },
  { prop: 'gender', label: '性别', width: 60 },
  { prop: 'age', label: '年龄', width: 60 },
  { prop: 'westernDiagnosis', label: '西医诊断', width: 150 },
  { prop: 'tcmDiagnosis', label: '中医诊断', width: 150 },
  { prop: 'chiefComplaint', label: '主诉', width: 180 },
  { prop: 'selfReport', label: '自诉', width: 180 },
  { prop: 'presentIllness', label: '现病史', width: 200 },
  { prop: 'inspection', label: '望诊', width: 120 },
  { prop: 'pulse', label: '脉诊', width: 120 },
  { prop: 'tongue', label: '舌诊', width: 140 },
  { prop: 'physicalExam', label: '查体', width: 120 },
  { prop: 'pattern', label: '辨证结论', width: 200 },
  { prop: 'prescription', label: '草药', width: 260 },
  { prop: 'followUp', label: '随访', width: 120 },
  { prop: 'treatmentEffect', label: '治疗效果', width: 100 },
  { prop: 'department', label: '科室', width: 90 },
  { prop: 'doctorId', label: '医生工号', width: 90 },
  { prop: 'visitTime', label: '接诊时间', width: 110, formatter: (row) => (row.visitTime || '').substring(0, 10) },
  { prop: 'score', label: '评分', width: 60 },
  { prop: 'grade', label: '分级', width: 70 }
]

const DEFAULT_COLS = ['registrationNo', 'gender', 'age', 'westernDiagnosis', 'tcmDiagnosis',
  'chiefComplaint', 'pattern', 'prescription', 'score', 'grade']
const visibleCols = ref([...DEFAULT_COLS])
const visibleColsList = computed(() => PREVIEW_COLS.filter((c) => visibleCols.value.includes(c.prop)))
const resetCols = () => {
  visibleCols.value = [...DEFAULT_COLS]
}

const preview = reactive({ loading: false, result: null })

// ===== 详情（弹窗，UX-77）=====
const detail = ref(null)
const detailVisible = ref(false)

// 点详情：写入共享状态，供 AI 助手"这份病历…"类问题使用（批C·3.2）
const openDetail = (row) => {
  detail.value = row
  aiStore.setActiveRecord(row)
  detailVisible.value = true
}

/** 只收起弹窗、保留 detail：否则关闭动画期间内容会闪空 */
const closeDetail = () => {
  detailVisible.value = false
}

const handlePreview = async () => {
  preview.loading = true
  try {
    const res = await previewDataset(buildPayload())
    preview.result = res.data
    if (!res.data.total) ElMessage.warning('筛选范围内无质控合格病历')
  } catch {
    // 拦截器已提示
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
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

// 导出区科室选项取后端实际值，避免写死科室与库中数据对不上（同 UX-03）
const departments = ref([])
const loadDepartments = async () => {
  try {
    const res = await getDepartments()
    departments.value = res.data || []
  } catch {
    departments.value = []
  }
}

onMounted(() => {
  loadStats()
  loadDepartments()
})
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
/* 统计失败提示（UX-21） */
.gs-fail {
  margin-left: auto;
  font-size: 12.5px;
  color: var(--danger);
}

/* 当前范围条（批B·4.1） */
.scope-bar {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px 24px;
  margin-bottom: 20px;
}
.scope-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}
.scope-tip { font-size: 13px; color: var(--text-sub); }
.scope-tip b { color: var(--ink); }

/* ===== 流程说明条 =====
   边框与圆角统一为 --line / 6px，与 .step-card、.level-dist .ld 同一套规格（UX-64） */
.flow-tip {
  background: var(--ink-light);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 12.5px;
  color: var(--ink-mid);
  line-height: 1.7;
  margin-bottom: 16px;
}
.flow-tip b {
  color: var(--ink);
  font-weight: normal;
}

/* ===== 清洗流程图 ===== */
.flow-wrapper {
  display: flex;
  align-items: stretch;
  gap: 6px;
  margin-bottom: 12px;
}
.flow-step {
  display: flex;
  align-items: center;
  flex: 1 1 0;
  gap: 6px;
  min-width: 0;
}
.step-card {
  flex: 1;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px 12px;
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
}
/* 每步的一句话解释（UX-76）：卡内常显，不再收进折叠区 */
.step-desc {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-sub);
}
/* 箭头定宽，末步用同宽占位，保证 5 张卡片等宽（UX-64） */
.step-arrow {
  width: 20px;
  flex-shrink: 0;
  text-align: center;
  font-size: 20px;
  color: var(--ochre);
  font-weight: bold;
}
.step-arrow.ghost {
  visibility: hidden;
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

/* 三级命中分布（批B·2.2）；圆角与流程区统一为 6px（UX-64） */
.level-dist {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 12px;
  font-size: 13px;
  color: var(--ink);
}
.level-dist .ld-lbl { color: var(--text-sub); font-size: 12.5px; }
.level-dist .ld { padding: 2px 10px; border: 1px solid var(--line); border-radius: 6px; background: #fff; }
.level-dist .ld.exact { color: var(--ink-mid); }
.level-dist .ld.contain { color: var(--ochre); }
.level-dist .ld.fuzzy { color: var(--danger); }

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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
/* 列显示选择器（UX-38） */
.col-picker :deep(.el-checkbox-group) {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 320px;
  overflow-y: auto;
}
.col-picker :deep(.el-checkbox) {
  margin-right: 0;
}
.col-picker-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  border-top: 1px solid var(--line);
  padding-top: 8px;
}
/* 预览行可点击下钻，给出指针提示（UX-18） */
.preview-box :deep(.el-table__body tr) {
  cursor: pointer;
}

@media (max-width: 1200px) {
  /* 窄屏改 3 列网格并隐藏箭头，保证每行卡片等宽（UX-64） */
  .flow-wrapper {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
  }
  .step-arrow {
    display: none;
  }
  .clean-stats { grid-template-columns: repeat(3, 1fr); }
}
</style>
