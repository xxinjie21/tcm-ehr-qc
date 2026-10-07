<template>
  <div>
    <!-- 顶部合并条（审查报告 V7）：原「清洗状态行」独占一行、右侧 60% 是空白，
         紧随其后的「当前范围」条又是整行。两块合成一条 —— 左边三个统计数字，
         右边范围选择 + 当前范围文案，右侧留白由控件吃掉，不再裸露。
         统计的加载 / 失败态（含重试按钮）原样保留，但只挂在数字组上：
         给整条加 loading 遮罩会让统计刷新期间范围筛选也点不了。 -->
    <section class="scope-bar">
      <div v-loading="statsLoading" element-loading-text="正在统计标准化情况…" class="gs-group">
        <span class="gs" title="这三张卡只统计「质控合格」的病历；清洗范围若包含待复核/无效，条数会对不上">
          <b>{{ stats.qualified ?? 0 }}</b> 质控合格病历</span>
        <span class="gs"><b>{{ stats.pendingGovern ?? 0 }}</b> 待清洗</span>
        <span class="gs"><b>{{ stats.governedCount ?? 0 }}</b> 已清洗</span>
        <!-- 失败态与「确实为 0」区分开，避免用户把旧值当最新结果-->
        <span v-if="statsFailed" class="gs-fail">
          统计加载失败{{ statsLoadedAt ? `（上次成功 ${statsLoadedAt}）` : '' }}
          <el-button link type="primary" size="small" @click="loadStats">重试</el-button>
        </span>
      </div>

      <!-- 当前范围（先选范围 → 后续操作只作用于范围内） -->
      <div class="scope-ctrls">
        <RangeFilter v-model="filters" />
        <div class="scope-row">
          <span class="scope-tip">当前范围：<b>{{ scopeText }}</b></span>
        </div>
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
          <!-- 28.18：步骤卡从 div 改 button —— 点开看「这步具体做什么、结果在哪看」，
               键盘可 Tab 聚焦、Enter/Space 触发（原生 button 行为） -->
          <button
            type="button"
            class="step-card"
            :class="{ active: activeStep === i }"
            :aria-expanded="activeStep === i"
            @click="activeStep = activeStep === i ? -1 : i"
          >
            <div class="step-num">{{ i + 1 }}</div>
            <div class="step-title">{{ s.title }}</div>
            <!-- 还原每步解释： 收敛过度，5 步说明全收进折叠区后
                 步骤卡只剩序号与标题，用户看不出每步到底做什么 -->
            <div class="step-desc">{{ s.desc }}</div>
          </button>
          <!-- 末步留占位箭头，保证 5 张卡片等宽-->
          <div class="step-arrow" :class="{ ghost: i === STEPS.length - 1 }" aria-hidden="true">→</div>
        </div>
      </div>

      <!-- 28.18：点开的步骤详述；再点同一张卡或「收起」关闭 -->
      <transition name="panel-fade">
        <div v-if="activeStepInfo" class="step-detail">
          <div class="sd-hd">
            <span class="sd-num">{{ activeStep + 1 }}</span>
            <b>{{ activeStepInfo.title }}</b>
            <button type="button" class="sd-close" @click="activeStep = -1">收起</button>
          </div>
          <p class="sd-desc">{{ activeStepInfo.detail }}</p>
          <p class="sd-where">结果看这里：{{ activeStepInfo.where }}</p>
        </div>
      </transition>

      <div class="clean-actions">
        <el-button type="primary" size="large" :loading="clean.loading" @click="handleClean">
          {{ clean.loading ? '清洗执行中…' : '执行数据清洗' }}
        </el-button>
        <!-- 执行期间说明「在做什么、要等多久、结果在哪看」，而不是只转一个圈-->
        <span v-if="clean.loading" class="tip">
          正在按 5 步依次处理，请勿关闭页面；完成后下方会给出分步结果
        </span>
      </div>

      <!-- 28.14：后端是同步接口、拿不到真实百分比，用不确定进度条只表达「在推进」；
           刻意不显示百分比数字，避免暗示一个并不存在的精确进度 -->
      <el-progress
        v-if="clean.loading"
        class="clean-progress"
        :percentage="100"
        :indeterminate="true"
        :duration="2"
        :show-text="false"
      />

      <!-- 28.14：清洗失败时给出可操作的失败报告（原因 + 发生时间 + 会不会留下半成品），
           而不是只留一个一闪而过的全局错误提示 -->
      <el-alert
        v-if="clean.error"
        class="clean-error"
        type="error"
        show-icon
        title="清洗未完成"
        @close="clean.error = null"
      >
        <div>{{ clean.error.message }}</div>
        <div class="clean-error-sub">
          发生时间：{{ clean.error.at }}。可点上方按钮重试；失败不会改动已清洗完成的数据。
        </div>
      </el-alert>

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
          <!-- 人工修改过的病历被跳过归一（方案 A：人工成果优先）。单列出来，
               否则「归一命中数突然变少」看起来像清洗出错。 -->
          <el-tooltip
            v-if="clean.result.manualSkipped > 0"
            content="这些病历的结构化数据被人工修改过，按「人工成果优先」跳过了归一，避免把人工修正撤销"
            placement="top"
          >
            <div class="stat-item ochre">
              <div class="num">{{ clean.result.manualSkipped }}</div>
              <div class="lbl">含人工修改·已跳过</div>
            </div>
          </el-tooltip>
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
      <div class="tip" style="margin-top: var(--sp-2)">
        只导出质控合格的病历（<b>不随上方「分级」变化</b>，分级只作用于数据清洗）；
        导出的文件与上方预览里出现的手机号、身份证号都会自动打码。
      </div>

      <!-- 28.18：导出历史 —— 导出是一次性动作，之前导过什么、用的什么范围，
           页面一关就没了。这里只在本机 localStorage 记元数据 + 当时的筛选载荷
           （不存病历数据本身），方便按同一口径重导。 -->
      <div v-if="exportHistory.length" class="export-history">
        <div class="eh-hd">最近导出（本机记录，最多 10 条）</div>
        <ul class="eh-list">
          <li v-for="(h, i) in exportHistory" :key="h.at + '-' + i">
            <span class="eh-time">{{ h.at }}</span>
            <span class="eh-fmt">{{ h.format.toUpperCase() }}</span>
            <span class="eh-scope" :title="h.scope">{{ h.scope }}</span>
            <el-button link type="primary" size="small" :disabled="exporting" @click="reExport(h)">
              重新导出
            </el-button>
          </li>
        </ul>
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
            :min-width="c.minWidth"
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
            <EmptyState text="筛选范围内没有质控合格的病历" />
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
// 设计取舍：清洗只做去重标记 / 字段清理 / 空值规整 / 脏数据隔离 / 术语归一，既不删除病历、
// 也不填充医生未书写的内容；导出恒只取质控合格病历，分级筛选只作用于清洗、不影响导出范围。
import { useDepartments } from '@/composables/useDepartments'
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmBox } from '@/utils/confirm'
import EmptyState from '@/components/EmptyState.vue'
import PanelCard from '@/components/PanelCard.vue'
import TermInput from '@/components/TermInput.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import RecordDetailDialog from '@/components/RecordDetailDialog.vue'
import { clean as cleanApi, governanceStats } from '@/api/governance'
import { exportDataset, previewDataset } from '@/api/export'
import { saveBlob } from '@/utils/download'
import { useAiContextStore } from '@/stores/ai'
import { LEVEL_TINY } from '@/utils/structured'

const aiStore = useAiContextStore()

// 28.18：每步补 detail（点开看的详述）与 where（结果落在结果区哪一项），
// 步骤卡据此从「只能看」变成「可点开」。
const STEPS = [
  { title: '去重', desc: '重复病历只标记，不删除',
    detail: '按登记号等 21 个字段完全一致判定为重复；重复病历只打标记、不删除，后续统计仍以去重后的口径计算。',
    where: '清洗结果「去重」项' },
  { title: '字段清理', desc: '只去多余空格，不改内容',
    detail: '只规整字段内多余空格与全半角差异，不填充医生未书写的内容，也不改写已有文字。',
    where: '清洗结果「字段清理」项' },
  { title: '空值规整', desc: '仅有空格等空白字符的字段记一次规整',
    detail: '一个字段若只含空格、换行等空白字符，统一记为空并计数，避免「看起来有值、实际无内容」。',
    where: '清洗结果「空值规整」项' },
  { title: '脏数据隔离', desc: '无法修复的病历标记为无效',
    detail: '缺关键字段且无法修复的病历标记为无效并隔离归档，不参与合格率等统计；隔离不等于删除。',
    where: '清洗结果「隔离归档」项' },
  { title: '术语归一', desc: '把「咽喉痛」这类写法统一成标准术语',
    detail: '按最新词典把主诉、诊断等术语统一为标准写法；被人工修改过的病历按「人工成果优先」跳过归一。',
    where: '清洗结果「术语归一命中」与三级命中分布' }
]

// 当前点开的步骤序号（-1 = 未展开）；一次只展开一个
const activeStep = ref(-1)
const activeStepInfo = computed(() => (activeStep.value >= 0 ? STEPS[activeStep.value] : null))

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

// 28.14：clean.error 保存最近一次清洗失败的「原因 + 发生时间」，供失败报告展示
const clean = reactive({ loading: false, result: null, error: null })

// 执行数据清洗：先二次确认（文案明确「只标记不删除、不补医生未写内容」），
// 再按当前 filters 提交；成功后写入分步结果与三级命中分布，并刷新顶部统计
const handleClean = async () => {
  // 1. 先取「本次会处理多少条」，再弹确认：数据清洗是会对范围内病历成批写入的操作，
  //    用户点之前必须知道影响面（批次6）。条数走既有预览接口，不新增后端；
  //    取不到（网络/权限）不阻断清洗，确认文案退化为只讲范围，绝不假装「0 条」。
  let affected = 0
  try {
    const pv = await previewDataset(buildPayload())
    affected = pv.data?.total || 0
  } catch {
    affected = 0
  }
  // 2. 二次确认，文案写明「只标记不删除、不补医生未写内容」，并带上影响条数
  if (!(await confirmBox(
      affected > 0
        ? `将对「${scopeText.value}」范围内的 ${affected} 条病历执行数据清洗：去重只标记、不删除，也不会填充医生未书写的内容。确认？`
        : `将对「${scopeText.value}」范围内的病历执行数据清洗：去重只标记、不删除，也不会填充医生未书写的内容。确认？`,
      '数据清洗',
      { type: 'warning', confirmButtonText: affected > 0 ? `确认清洗 ${affected} 条` : '确认执行', cancelButtonText: '取消' }))) {
    return
  }
  // 3. 置清洗中状态，并清掉上一次失败报告
  clean.loading = true
  clean.error = null
  try {
    // 4. 按当前范围提交清洗
    const res = await cleanApi({ filters: { ...filters } })
    // 5. 写入分步结果，并把三级命中分布同步给 AI 助手
    clean.result = res.data
    aiStore.setNormByLevel(res.data.normByLevel || { exact: 0, contain: 0, fuzzy: 0 })
    // 6. 提示归一命中数；含人工修改被跳过时一并说明，避免「归一数变少」看起来像出错
    const skipped = res.data.manualSkipped || 0
    ElMessage.success(skipped > 0
      ? `清洗完成：归一命中 ${res.data.normalized} 处，${skipped} 条含人工修改已跳过归一`
      : `清洗完成：归一命中 ${res.data.normalized} 处`)
    // 7. 刷新顶部统计（待清洗 / 已清洗会变化）
    loadStats()
  // 8. 失败由响应拦截器统一提示，这里再落一条可操作的失败报告供页面回看
  } catch (e) {
    clean.error = {
      message: e?.message || '清洗请求失败',
      at: new Date().toLocaleTimeString('zh-CN', { hour12: false })
    }
  // 9. 无论成败都关掉清洗中状态
  } finally {
    clean.loading = false
  }
}

const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })
const format = ref('csv')
const exporting = ref(false)
// 导出文件名去重序号：Date.now() 是毫秒级，同一毫秒内连点两次导出仍会同名
let exportSeq = 0

// 28.18：导出历史（本机 localStorage 元数据，最多 10 条）。存的是元数据 + 当时的筛选载荷，
// 不含导出内容本身；解析失败/被用户清掉都退回空列表，不阻塞导出主流程。
const EXPORT_HISTORY_KEY = 'tcm:exportHistory'
const exportHistory = ref([])
const loadExportHistory = () => {
  try {
    const raw = JSON.parse(localStorage.getItem(EXPORT_HISTORY_KEY) || '[]')
    exportHistory.value = Array.isArray(raw) ? raw : []
  } catch { exportHistory.value = [] }
}
const pushExportHistory = (payload) => {
  exportHistory.value = [
    { at: new Date().toLocaleString(), format: format.value, scope: scopeText.value, payload },
    ...exportHistory.value
  ].slice(0, 10)
  try { localStorage.setItem(EXPORT_HISTORY_KEY, JSON.stringify(exportHistory.value)) }
  catch { /* 存不下（隐私模式/配额满）就只在本次会话内保留 */ }
}

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
 *
 * 长文本列（≥180 的主诉 / 自诉 / 现病史 / 辨证结论 / 草药）用 min-width 而不是 width：
 * 它们的固定宽是「内容很长时的上限」，不是「必须占的位」。窄容器里先被压缩
 * （配合 show-overflow-tooltip 省略），而不是把整张表顶到溢出；宽容器里仍按原值展开。
 * 主键 / 编号这类定宽列保持 width，避免压缩后对不齐。
 */
const PREVIEW_COLS = [
  { prop: 'registrationNo', label: '登记号', width: 150 },
  { prop: 'gender', label: '性别', width: 60 },
  { prop: 'age', label: '年龄', width: 60 },
  { prop: 'westernDiagnosis', label: '西医诊断', width: 150 },
  { prop: 'tcmDiagnosis', label: '中医诊断', width: 150 },
  { prop: 'chiefComplaint', label: '主诉', minWidth: 180 },
  { prop: 'selfReport', label: '自诉', minWidth: 180 },
  { prop: 'presentIllness', label: '现病史', minWidth: 200 },
  { prop: 'inspection', label: '望诊', width: 120 },
  { prop: 'pulse', label: '脉诊', width: 120 },
  { prop: 'tongue', label: '舌诊', width: 140 },
  { prop: 'physicalExam', label: '查体', width: 120 },
  { prop: 'pattern', label: '辨证结论', minWidth: 200 },
  { prop: 'prescription', label: '草药', minWidth: 260 },
  { prop: 'followUp', label: '随访', width: 120 },
  { prop: 'treatmentEffect', label: '治疗效果', width: 100 },
  { prop: 'department', label: '科室', width: 90 },
  { prop: 'doctorId', label: '医生工号', width: 90 },
  { prop: 'visitTime', label: '接诊时间', width: 110, formatter: (row) => (row.visitTime || '').substring(0, 10) },
  { prop: 'score', label: '评分', width: 60 },
  { prop: 'grade', label: '分级', width: 70 }
]

// 默认列收敛到 7 个核心字段（审查报告 L3）：原 10 列定宽合计 1340 + 操作列 70，
// 比 1350 的容器还宽，预览一打开就横向溢出。取「主键 / 诊断 / 来源 / 时间 / 结论」
// 这几类信息量最大的列（合计 780 + 70，留有余量）；长文本（主诉 / 现病史 / 草药…）
// 移出默认集，需要时仍在「列显示」popover 里勾选（选择器未改动）。
const DEFAULT_COLS = ['registrationNo', 'westernDiagnosis', 'tcmDiagnosis',
  'department', 'visitTime', 'score', 'grade']
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

// 导出并落盘：后端成功回文件流、失败回 JSON，故先判别 blob 类型 ——
// 是 JSON 就解析 msg 报错，否则才落盘保存，避免把一段错误 JSON 当数据集下载下来。
// 返回 true 表示确实下载成功（供历史记录与提示区分成败）。
const exportAndSave = async (payload, ext) => {
  const blob = await exportDataset(payload)
  // 失败回的是 JSON → 解析出 msg 报错，不落盘
  if (blob && blob.type && blob.type.includes('application/json')) {
    const text = await blob.text()
    let msg = '导出失败'
    try {
      msg = JSON.parse(text).msg || msg
    // 解析不出就沿用默认文案
    } catch { /* keep default */ }
    ElMessage.error(msg)
    return false
  }
  // 确认是文件流才落盘；文件名带毫秒 + 递增序号，避免同一毫秒内两次导出同名
  saveBlob(blob, `tcm_ehr_dataset_${Date.now()}_${exportSeq++}.${ext}`)
  return true
}

// 导出下载
const handleExport = async () => {
  // 1. 置导出中状态
  exporting.value = true
  try {
    // 2. 固定住本次载荷（成功后才入历史），避免后续筛选变化影响记录
    const payload = buildPayload()
    // 3. 导出成功后提示并记入历史
    if (await exportAndSave(payload, format.value)) {
      ElMessage.success('导出成功')
      pushExportHistory(payload)
    }
  // 4. 失败由响应拦截器统一提示
  } catch {
    // 拦截器已提示
  // 5. 无论成败都关掉导出中状态
  } finally {
    exporting.value = false
  }
}

// 按历史记录用同一范围与格式重导
const reExport = async (entry) => {
  exporting.value = true
  try {
    if (await exportAndSave(entry.payload, entry.format)) {
      ElMessage.success('已按历史记录重新导出')
    }
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

// 导出区科室选项取后端实际值，避免写死科室与库中数据对不上
// 科室下拉：收敛到 useDepartments（Promise 单例缓存，与 Dashboard/RangeFilter 共用）
const { departments, reload: loadDepartments } = useDepartments()

onMounted(() => {
  loadStats()
  loadDepartments()
  loadExportHistory()
})
</script>

<style scoped>
/* ===== 顶部合并条（统计数字 + 当前范围，审查报告 V7） ===== */
/* 数字组：合并进 .scope-bar 后作为左侧一簇。
   baseline 对齐让三个数字与各自的单位文字落在同一基线上 */
.gs-group {
  display: flex;
  align-items: baseline;
  gap: var(--sp-5);
  flex-wrap: wrap;
}
.gs {
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
}
.gs b {
  font-size: var(--fs-xl);
  color: var(--ink);
  margin-right: 6px;
}
/* 统计失败提示：紧跟数字组尾部。原先靠 margin-left:auto 顶到独占一行的最右侧，
   合并后右侧已被范围控件占住，容器宽度由内容决定，那条 auto 外边距已无可分配空间 */
.gs-fail {
  font-size: var(--fs-xs);
  color: var(--danger);
}

/* 当前范围条：合并后承载左数字组 + 右范围控件两簇 */
.scope-bar {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-4) var(--sp-5);
  margin-bottom: 20px;
  /* 批次16 · 16.4：筛选区压成一行。
     原先 .scope-row（「当前范围」那行）带 margin-top:10px，**永远另起一行** —— 控件本身
     在 1366 下是放得下的（约 700px），是这一行让整块筛选区占到 2~3 行，把下面的流程区
     挤出首屏。改成横向：左筛选、右「当前范围」；窄屏（≤1560）由 flex-wrap 自动换行，
     不裁切、也不硬挤成一行。
     V7 合并后再套一层 space-between：左簇统计数字、右簇范围控件，
     原来统计条右侧那 60% 空白由右簇吃掉。 */
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
/* 右簇：范围选择器 + 「当前范围」文案，内部仍横排、底对齐（沿用原 scope-bar 的排法） */
.scope-ctrls {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.scope-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 0;
}
/* 窄屏（1366 这一档）：缩小间距，让筛选与「当前范围」仍尽量同行 */
@media (max-width: 1560px) {
  .scope-bar {
    gap: var(--sp-3);
    padding: var(--sp-3) var(--sp-4);
  }
}
.scope-tip { font-size: var(--fs-base); color: var(--text-sub-strong); }
.scope-tip b { color: var(--ink); }

/* ===== 流程说明条 =====
   边框与圆角统一为 --line / 6px，与 .step-card、.level-dist .ld 同一套规格*/
.flow-tip {
  background: var(--ink-light);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-3) var(--sp-4);
  font-size: var(--fs-xs);
  color: var(--ink-mid);
  line-height: 1.7;
  margin-bottom: var(--sp-4);
}
.flow-tip b {
  color: var(--ink);
}

/* ===== 清洗流程图 ===== */
.flow-wrapper {
  display: flex;
  align-items: stretch;
  gap: 6px;
  margin-bottom: var(--sp-3);
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
  padding: var(--sp-4) var(--sp-3);
  text-align: center;
  transition: transform var(--dur-fast) var(--ease-out),
    box-shadow var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out);
  /* 28.18：div → button 后的重置，保持原卡片观感 */
  cursor: pointer;
  font-family: inherit;
  color: inherit;
  width: 100%;
}
.step-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 3px 10px rgba(47, 70, 57, 0.12);
}
.step-card.active {
  border-color: var(--ochre);
  box-shadow: 0 0 0 1px var(--ochre) inset;
}
/* 28.18：点开的步骤详述 */
.step-detail {
  margin-top: var(--sp-3);
  background: var(--surface);
  border: 1px solid var(--line);
  border-left: 3px solid var(--ochre);
  border-radius: 6px;
  padding: var(--sp-2) var(--sp-3);
}
.sd-hd {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-base);
  color: var(--ink);
}
.sd-num {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--ink-mid);
  color: var(--surface);
  font-size: var(--fs-xs);
  line-height: 20px;
  text-align: center;
}
.sd-close {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--text-sub-strong);
  font-size: var(--fs-xs);
  cursor: pointer;
  padding: 0;
}
.sd-close:hover { color: var(--ink); }
.sd-desc, .sd-where {
  margin: 6px 0 0;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
.sd-where { color: var(--ink-mid); }
/* 步骤详述的展开/收起：用全局共享的 panel-fade（纯淡入淡出，不做位移）。
   它嵌在卡片里，位移会让卡片边缘跳动；纯 opacity 也不创建包含块、不裁剪，
   是这类内嵌面板最稳的做法。此前是自写的 .step-fade（写死了时长与 ease 曲线），
   已并入 theme.css 的动效令牌与 ② 类，见 docs/视觉与交互审查报告-第三轮.md 附录 D。 */
.step-num {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--ink-mid);
  color: var(--surface);
  font-size: var(--fs-base);
  font-weight: bold;
  line-height: 30px;
  margin: 0 auto var(--sp-2);
}
.step-title {
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
}
/* 每步的一句话解释：卡内常显，不再收进折叠区 */
.step-desc {
  margin-top: 6px;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--text-sub-strong);
}
/* 箭头定宽，末步用同宽占位，保证 5 张卡片等宽*/
.step-arrow {
  width: 20px;
  flex-shrink: 0;
  text-align: center;
  font-size: var(--fs-page);
  color: var(--ochre-text);
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
  gap: var(--sp-4);
  margin-bottom: var(--sp-1);
}

/* 28.14：不确定进度条与失败报告 */
.clean-progress {
  margin: var(--sp-3) auto 0;
  max-width: 420px;
}
.clean-error {
  margin-top: var(--sp-3);
}
.clean-error-sub {
  margin-top: 2px;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}

/* ===== 清洗结果（中部） ===== */
.clean-result {
  margin-top: var(--sp-5);
  border-top: 1px dashed var(--line-soft);
  padding-top: var(--sp-4);
}
.result-hd {
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  margin-bottom: 14px;
}
.clean-stats {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: var(--sp-3);
}
.stat-item {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-4) var(--sp-4);
  text-align: center;
}
.stat-item .num {
  font-size: var(--fs-xl);
  font-weight: bold;
  color: var(--ink);
}
.stat-item .lbl {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-top: var(--sp-1);
}
.stat-item.green .num { color: var(--ink-mid); }
.stat-item.ochre .num { color: var(--ochre-text); }
.stat-item.red .num { color: var(--danger); }

/* 三级命中分布；圆角与流程区统一为 6px*/
.level-dist {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  margin-top: var(--sp-3);
  font-size: var(--fs-base);
  color: var(--ink);
}
.level-dist .ld-lbl { color: var(--text-sub-strong); font-size: var(--fs-xs); }
.level-dist .ld { padding: var(--sp-1) var(--sp-3); border: 1px solid var(--line); border-radius: 6px; background: var(--surface); }
.level-dist .ld.exact { color: var(--ink-mid); }
.level-dist .ld.contain { color: var(--ochre-text); }
.level-dist .ld.fuzzy { color: var(--danger); }

/* ===== 导出区 ===== */
.export-row {
  display: flex;
  gap: var(--sp-4);
  align-items: flex-end;
  flex-wrap: wrap;
  padding-bottom: 6px;
}
/* 字段视觉标签：单选按钮组不能用 <label for>，改用 span，故与 label 共用同一套样式 */
label,
.field-lbl {
  display: block;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-bottom: 3px;
}
/* 28.18：导出历史列表 */
.export-history {
  margin-top: var(--sp-3);
  border-top: 1px dashed var(--line-soft);
  padding-top: var(--sp-2);
}
.eh-hd {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-bottom: var(--sp-1);
}
.eh-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.eh-list li {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 3px 0;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.eh-time { min-width: 150px; }
.eh-fmt {
  min-width: 42px;
  font-weight: bold;
  color: var(--ink-mid);
}
.eh-scope {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-box {
  margin-top: var(--sp-4);
  border-top: 1px dashed var(--line-soft);
  padding-top: var(--sp-3);
}
.preview-hd {
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  margin-bottom: var(--sp-2);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
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
  gap: var(--sp-2);
  border-top: 1px solid var(--line);
  padding-top: var(--sp-2);
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
    gap: var(--sp-2);
  }
  .step-arrow {
    display: none;
  }
  .clean-stats { grid-template-columns: repeat(3, 1fr); }
}
</style>
