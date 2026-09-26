<template>
  <div>
    <!-- 清洗状态行（顶部） -->
    <section v-loading="statsLoading" class="gov-stats">
      <span class="gs" title="这三张卡只统计「质控合格」的病历；清洗范围若包含待复核/无效，条数会对不上">
        <b>{{ stats.qualified ?? 0 }}</b> 质控合格病历</span>
      <span class="gs"><b>{{ stats.pendingGovern ?? 0 }}</b> 待清洗</span>
      <span class="gs"><b>{{ stats.governedCount ?? 0 }}</b> 已清洗</span>
      <!-- 失败态与「确实为 0」区分开，避免用户把旧值当最新结果-->
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
      </div>
    </section>

    <!-- 数据清洗与术语归一（流程图） -->
    <PanelCard title="数据清洗与术语自动归一">
      <!-- 总述只保留这一处；每步的一句话解释回到步骤卡内-->
      <div class="flow-tip">
        清洗<b>不会填充医生未书写的内容</b>，也<b>不会删除任何病历</b>；术语按最新词典统一为标准写法。
      </div>

      <div class="flow-wrapper">
        <div v-for="(s, i) in STEPS" :key="s.title" class="flow-step">
          <div class="step-card">
            <div class="step-num">{{ i + 1 }}</div>
            <div class="step-title">{{ s.title }}</div>
            <!-- 还原每步解释： 收敛过度，5 步说明全收进折叠区后
                 步骤卡只剩序号与标题，用户看不出每步到底做什么 -->
            <div class="step-desc">{{ s.desc }}</div>
          </div>
          <!-- 末步留占位箭头，保证 5 张卡片等宽-->
          <div class="step-arrow" :class="{ ghost: i === STEPS.length - 1 }" aria-hidden="true">→</div>
        </div>
      </div>

      <div class="clean-actions">
        <el-button type="primary" size="large" :loading="clean.loading" @click="handleClean">
          {{ clean.loading ? '清洗执行中…' : '执行数据清洗' }}
        </el-button>
        <!-- 执行期间说明「在做什么、要等多久、结果在哪看」，而不是只转一个圈-->
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
          <span class="ld exact">{{ LEVEL_TINY[1] }} {{ clean.result.normByLevel.exact ?? 0 }}</span>
          <span class="ld contain">{{ LEVEL_TINY[2] }} {{ clean.result.normByLevel.contain ?? 0 }}</span>
          <span class="ld fuzzy">{{ LEVEL_TINY[3] }} {{ clean.result.normByLevel.fuzzy ?? 0 }}</span>
        </div>
      </div>
    </PanelCard>

    <!-- 标准数据集导出（中下部） -->
    <PanelCard title="标准数据集导出">
      <div class="export-row">
        <div>
          <!-- 单选按钮组是「一组」控件，而 <label for> 只能关联「单个」控件：
               指向 el-radio-group 的根 div（role=radiogroup）会命中非可标注元素，
               Chrome 的 Issues 面板报「Incorrect use of <label for=FORM_ELEMENT>」。
               正解是视觉文案用 span，
               组本身用 aria-label 命名 —— 组内每个 radio 由 element-plus 自己的
               <label> 包裹，已经各自有名字，不需要我们再管。 -->
          <span class="field-lbl">导出格式</span>
          <el-radio-group v-model="format" aria-label="导出格式">
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
          <!-- 同 RangeFilter：范围选择器的 id 必须传数组（内部两个 input）；
               aria-label 由 Picker 透传给 PickerRangeTrigger，会同时落到起止两个框上 -->
          <label for="ex-date-start">就诊时间</label>
          <el-date-picker
            :id="['ex-date-start', 'ex-date-end']"
            aria-label="就诊时间"
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
        只导出质控合格的病历（<b>不随上方「分级」变化</b>，分级只作用于数据清洗）；
        导出的文件与上方预览里出现的手机号、身份证号都会自动打码。
      </div>

      <div v-if="preview.result" class="preview-box">
        <div class="preview-hd">
          <span>预览：共 {{ preview.result.total }} 条合格病历（样本前10条，点击行查看完整详情）</span>
          <!-- 21 列全出会横向滚很长，改为默认只显示关键列，其余按需勾选-->
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
          <!-- 行内「查看」入口：键盘用户也能打开详情，且给鼠标用户明确的「可点」提示-->
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

    <!-- 单条完整详情改弹窗：排版与病历数据页的「病历详情」共用同一组件 -->
    <RecordDetailDialog v-model="detailVisible" :record="detail" title="病历完整详情" />
  </div>
</template>

<script setup>
// 数据治理页：在「当前范围」内执行数据清洗与术语归一，并把质控合格病历导出为标准数据集。
// 设计取舍：清洗只做去重标记 / 字段清理 / 格式规整 / 脏数据隔离 / 术语归一，既不删除病历、
// 也不填充医生未书写的内容；导出恒只取质控合格病历，分级筛选只作用于清洗、不影响导出范围。
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import TermInput from '@/components/TermInput.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import RecordDetailDialog from '@/components/RecordDetailDialog.vue'
import { clean as cleanApi, exportDataset, previewDataset, governanceStats } from '@/api/governance'
import { getDepartments } from '@/api/stats'
import { saveBlob } from '@/utils/download'
import { useAiStore } from '@/stores/ai'
import { LEVEL_TINY } from '@/utils/structured'

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
// 否则用户会把这些数字当成最新结果
const statsFailed = ref(false)
const statsLoadedAt = ref('')

// 当前范围：清洗 / 导出 只作用于该范围（filters 见下方声明）
const scopeText = computed(() => {
  const parts = []
  if (filters.department) parts.push(filters.department)
  if (filters.dateRange && filters.dateRange.length === 2) parts.push(`${filters.dateRange[0]}~${filters.dateRange[1]}`)
  if (filters.pattern) parts.push(filters.pattern)
  if (filters.grade) parts.push(filters.grade)
  return parts.length ? parts.join(' · ') : '全部'
})

// 拉取治理统计（质控合格 / 待清洗 / 已清洗）并同步给 aiStore 供 AI 助手引用；
// 失败时保留旧值但置失败态，状态行据此提示「数字可能不是最新」
const loadStats = async () => {
  // 1. 置加载态并清空上次失败态
  statsLoading.value = true
  statsFailed.value = false
  try {
    // 2. 拉取三项统计并覆盖本地状态
    const res = await governanceStats()
    Object.assign(stats, res.data)
    // 3. 同步给 aiStore，供 AI 助手引用
    aiStore.setStats(res.data)
    // 4. 记下本次成功时间，失败提示里可回显
    statsLoadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  // 5. 保留旧值但置失败态，提示「数字可能不是最新」
  } catch {
    // 标记失败态，状态行据此提示「显示的可能不是最新值」
    statsFailed.value = true
  // 6. 无论成败都关掉 loading
  } finally {
    statsLoading.value = false
  }
}

const clean = reactive({ loading: false, result: null })

// 执行数据清洗：先二次确认（文案明确「只标记不删除、不补医生未写内容」），
// 再按当前 filters 提交；成功后写入分步结果与三级命中分布，并刷新顶部统计
const handleClean = async () => {
  // 1. 先二次确认，文案写明「只标记不删除、不补医生未写内容」
  try {
    await ElMessageBox.confirm(
      `将对「${scopeText.value}」范围内的病历执行数据清洗：去重只标记、不删除，也不会填充医生未书写的内容。确认？`,
      '数据清洗',
      { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
    )
  // 2. 用户取消就直接返回
  } catch {
    return
  }
  // 3. 置清洗中状态
  clean.loading = true
  try {
    // 4. 按当前范围提交清洗
    const res = await cleanApi({ filters: { ...filters } })
    // 5. 写入分步结果，并把三级命中分布同步给 AI 助手
    clean.result = res.data
    aiStore.setNormByLevel(res.data.normByLevel || { exact: 0, contain: 0, fuzzy: 0 })
    // 6. 提示归一命中数
    ElMessage.success(`清洗完成：归一命中 ${res.data.normalized} 处`)
    // 7. 刷新顶部统计（待清洗 / 已清洗会变化）
    loadStats()
  // 8. 失败由响应拦截器统一提示
  } catch {
    // 拦截器已提示
  // 9. 无论成败都关掉清洗中状态
  } finally {
    clean.loading = false
  }
}

const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })
const format = ref('csv')
const exporting = ref(false)

// 组装预览 / 导出共用的请求体：只带后端约定的 department / dateRange / pattern 三个维度，
// 刻意不含 grade —— 分级只作用于数据清洗，导出恒为质控合格病历
const buildPayload = () => ({
  format: format.value,
  filters: {
    department: filters.department || '',
    dateRange: filters.dateRange || [],
    pattern: filters.pattern || ''
  }
})

/**
 * 预览表列定义。21 列全出横向滚动很长，默认只显示关键列，
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
// 由勾选的列 prop 过滤出实际要渲染的列定义（顺序跟随 PREVIEW_COLS）；
// 用户把列全部取消时返回空数组，表格只剩固定的「操作」列，不额外兜底回默认列
const visibleColsList = computed(() => PREVIEW_COLS.filter((c) => visibleCols.value.includes(c.prop)))
// 恢复默认列：把勾选回退到 DEFAULT_COLS 的关键列组合，与「全选」共同构成列显示的快捷操作
const resetCols = () => {
  visibleCols.value = [...DEFAULT_COLS]
}

const preview = reactive({ loading: false, result: null })

// ===== 详情=====
const detail = ref(null)
const detailVisible = ref(false)

// 点详情：写入共享状态，供 AI 助手"这份病历…"类问题使用
const openDetail = (row) => {
  // 1. 记下当前行作为弹窗数据
  detail.value = row
  // 2. 写入共享状态，供 AI 助手「这份病历…」类提问引用
  aiStore.setActiveRecord(row)
  // 3. 数据就绪后再打开弹窗
  detailVisible.value = true
}

/** 只收起弹窗、保留 detail：否则关闭动画期间内容会闪空 */
const closeDetail = () => {
  detailVisible.value = false
}

// 预览数据集：按当前范围取质控合格病历（后端只回前 10 条样本）；
// 范围内无合格病历时 total 为 0，额外提示用户，而不是只丢一张空表出来
const handlePreview = async () => {
  // 1. 置加载态
  preview.loading = true
  try {
    // 2. 按当前范围取质控合格病历（后端只回前 10 条样本）
    const res = await previewDataset(buildPayload())
    // 3. 写入预览结果
    preview.result = res.data
    // 4. 范围内无合格病历要额外提示，而不是只丢一张空表
    if (!res.data.total) ElMessage.warning('筛选范围内无质控合格病历')
  // 5. 失败由响应拦截器统一提示
  } catch {
    // 拦截器已提示
  // 6. 无论成败都关掉 loading
  } finally {
    preview.loading = false
  }
}

// 导出下载：后端成功回文件流、失败回 JSON，故先判别 blob 类型 ——
// 是 JSON 就解析 msg 报错，否则才落盘保存，避免把一段错误 JSON 当数据集下载下来
const handleExport = async () => {
  // 1. 置导出中状态
  exporting.value = true
  try {
    // 2. 请求导出：后端成功回文件流、失败回 JSON
    const blob = await exportDataset(buildPayload())
    // 3. 失败回的是 JSON → 解析出 msg 报错，避免把错误 JSON 当数据集存下来
    if (blob && blob.type && blob.type.includes('application/json')) {
      const text = await blob.text()
      let msg = '导出失败'
      try {
        msg = JSON.parse(text).msg || msg
      // 解析不出就沿用默认文案
      } catch { /* keep default */ }
      // 4. 提示错误并结束，不落盘
      ElMessage.error(msg)
      return
    }
    // 5. 确认是文件流才落盘保存
    saveBlob(blob, `tcm_ehr_dataset_${Date.now()}.${format.value}`)
    // 6. 提示导出成功
    ElMessage.success('导出成功')
  // 7. 失败由响应拦截器统一提示
  } catch {
    // 拦截器已提示
  // 8. 无论成败都关掉导出中状态
  } finally {
    exporting.value = false
  }
}

// 导出区科室选项取后端实际值，避免写死科室与库中数据对不上
const departments = ref([])
// 加载导出区的科室下拉项（取后端实际科室值，避免写死科室与库中数据对不上）；
// 失败时降级为空列表，不阻塞页面其余功能
const loadDepartments = async () => {
  // 1. 取后端实际科室值，避免写死科室与库中数据对不上
  try {
    const res = await getDepartments()
    // 2. 填充下拉选项
    departments.value = res.data || []
  // 3. 失败时降级为空列表，不阻塞页面其余功能
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
/* ===== 清洗状态行（顶部） ===== */
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
/* 统计失败提示*/
.gs-fail {
  margin-left: auto;
  font-size: 12.5px;
  color: var(--danger);
}

/* 当前范围条*/
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
   边框与圆角统一为 --line / 6px，与 .step-card、.level-dist .ld 同一套规格*/
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
/* 每步的一句话解释：卡内常显，不再收进折叠区 */
.step-desc {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-sub);
}
/* 箭头定宽，末步用同宽占位，保证 5 张卡片等宽*/
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
  font-size: 13px;
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

/* 三级命中分布；圆角与流程区统一为 6px*/
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
/* 字段视觉标签：单选按钮组不能用 <label for>，改用 span，故与 label 共用同一套样式 */
label,
.field-lbl {
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
/* 列显示选择器*/
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
/* 预览行可点击下钻，给出指针提示*/
.preview-box :deep(.el-table__body tr) {
  cursor: pointer;
}

@media (max-width: 1200px) {
  /* 窄屏改 3 列网格并隐藏箭头，保证每行卡片等宽*/
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
