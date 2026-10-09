<template>
  <div class="std-report">
    <!-- 报告头：一句话结论 + 统计口径 + 时间区间 / 刷新 / 导出，全部收进一张卡。
         此前「质控门禁条 / 加载失败条 / 数据来源条 / 时间工具条 / 结论条」是五条各自
         带左边框的长横幅，叠在正文顶部像五道横杠。现合并为单一头部：
         状态随「加载中 / 加载失败 / 质控未完成 / 无数据 / 短板 / 正常」变化，
         时间区间与导出集中在右侧，异常（失败 / 质控未完成）在同处就近处理。 -->
    <header class="rpt-head" :class="headline.tone">
      <div class="rh-status" aria-live="polite">
        <span class="rh-icon" aria-hidden="true">{{ headline.icon }}</span>
        <div class="rh-text">
          <div class="rh-title">{{ headline.title }}</div>
          <p v-if="headline.desc" class="rh-desc">{{ headline.desc }}</p>
          <!-- 质控未完成：补一句「哪些部分不受影响」，避免用户以为整页失效 -->
          <p v-if="showQcGate" class="rh-note">
            只想看词典建设进度可继续往下看 —— 那部分与质控无关。
          </p>
          <!-- 数据来源提示：只在真拿到报告后渲染 —— 加载中 / 失败时它会陈述「0 条病历」，
               那是错误陈述（不是 0 条，是还没读到 / 没读到）。 -->
          <p v-else-if="report && !loadError" class="rh-caveat">
            当前数据共 {{ report?.dataset?.recordCount ?? 0 }} 条病历、主诉
            {{ report?.dataset?.chiefComplaintTemplates ?? 0 }} 种写法，属测试数据；
            下列数字用于验证词典建设进度，不代表真实病历上的准确率。
          </p>
        </div>
      </div>

      <!-- 时间维度：病历接诊时间跨度大，混在一起看不出「换了词表之后有没有变好」。
           控件与事件绑定（preset / applyPreset / applyCustom / loadReport）原样保留。 -->
      <div class="rh-tools">
        <el-radio-group v-model="preset" size="small" @change="applyPreset">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="1y">近一年</el-radio-button>
          <el-radio-button value="3y">近三年</el-radio-button>
          <el-radio-button value="custom">自定义</el-radio-button>
        </el-radio-group>
        <el-date-picker
          v-if="preset === 'custom'"
          v-model="customRange"
          type="daterange"
          size="small"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始"
          end-placeholder="结束"
          style="width: 240px"
          @change="applyCustom"
        />
        <el-button size="small" :loading="loading" @click="loadReport">
          {{ loadError ? '重试' : '刷新' }}
        </el-button>
        <el-button size="small" :loading="exporting" @click="handleExport">导出 CSV</el-button>
      </div>

      <!-- 质控未完成时的重跑入口与进度：与结论同处一卡，就近解决 -->
      <div v-if="showQcGate" class="rh-ops">
        <el-button type="primary" size="small" :loading="rerunning" @click="rerunQc">
          立即重跑质控
        </el-button>
        <el-button size="small" :loading="rerunning" @click="rerunAll">
          重跑「解析 + 质控」
        </el-button>
        <span v-if="qcLast" class="tip">上次完成：{{ qcLast }}</span>
        <!-- 重跑进行中的进度条：没有它用户只能干等，不知道系统在不在动 -->
        <div v-if="rerunStage !== 'idle'" class="rh-progress">
          <span class="gp-label">{{ rerunStageLabel }}</span>
          <el-progress
            v-if="rerunPercent !== null"
            :percentage="rerunPercent"
            :stroke-width="6"
            style="flex: 1 1 auto; min-width: 120px"
          />
        </div>
      </div>

      <p v-if="rangeTip" class="rh-range">{{ rangeTip }}</p>
    </header>

    <!-- P0-6：加载态给固定高度骨架（与终态同为 .kpi、同 min-height），
         KPI 区不再从 0px 一次性撑开 546px。 -->
    <div v-if="!loadError" class="kpi-row">
      <template v-if="loading">
        <div v-for="n in (kpis.length || 3)" :key="'kpi-skel-' + n" class="kpi kpi-skel" aria-hidden="true">
          <span class="sk sk-label" />
          <span class="sk sk-value" />
          <span class="sk sk-note" />
        </div>
      </template>
      <template v-else>
        <div v-for="k in kpis" :key="k.label" class="kpi" :class="k.tone">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value">{{ k.value }}</div>
          <div class="kpi-note">{{ k.note }}</div>
        </div>
      </template>
    </div>

    <!-- 28.22：归一率与缺词的图形视图。原先这两项只在下方收起区的 el-table 里，
         业务用户要先展开、再逐行读才知道短板在哪。
         失败态由报告头接管（不再重复渲染一屏失败文案），故这里直接不渲染。 -->
    <StandardizationCharts
      v-if="!loadError"
      :coverage="report?.coverage || []"
      :unmatched="report?.unmatched || {}"
      :loading="loading"
      :error="loadError"
      @retry="loadReport"
    />

    <!-- 第二屏：待办清单。这是本页最有价值的部分 ——
         把「多少条未归一」翻译成「该做什么、归谁、值多少」。 -->
    <PanelCard v-if="!loadError" title="建议的下一步">
      <template #extra>
        <span class="tip">按影响面排序，先做第一条</span>
      </template>
      <!-- P0-6：加载态给 2~3 行占位，列表不再从 0px 撑开 -->
      <ol v-if="loading" class="todo-list" aria-hidden="true">
        <li v-for="n in 3" :key="'todo-skel-' + n" class="todo">
          <div class="todo-idx" />
          <div class="todo-body">
            <div class="sk sk-line sk-w40" />
            <div class="sk sk-line sk-w90" />
          </div>
          <span class="sk sk-tag" />
        </li>
      </ol>
      <ol v-else class="todo-list">
        <li v-for="(t, i) in todos" :key="i" class="todo">
          <div class="todo-idx">{{ i + 1 }}</div>
          <div class="todo-body">
            <div class="todo-title">{{ t.title }}</div>
            <div class="todo-desc">{{ t.desc }}</div>
          </div>
          <!-- P1-8：右侧「标签 + 操作」固定宽度成列 —— 无按钮的行用同宽占位，
               否则第 1 条的标签被按钮挤到左边 101px，右侧列视觉断裂。 -->
          <div class="todo-ops">
            <el-tag size="small" :type="t.tagType" effect="plain">{{ t.owner }}</el-tag>
            <!-- 批次2：把这条待办的「待补词」复制走 —— 直达词典导入页粘贴即可，
                 避免用户看完「先补这 8 个词」还得自己手抄。无接口调用，不会有副作用。 -->
            <span class="todo-act">
              <el-button
                v-if="t.words && t.words.length"
                size="small"
                @click="copyGapWords(t.words)"
              >复制待补词</el-button>
            </span>
          </div>
        </li>
      </ol>
      <div v-if="!loading && !todos.length" class="empty-tip">
        {{ emptyTip }}
      </div>
    </PanelCard>

    <!-- 第三屏：明细默认收起。业务用户通常不需要逐类看，展开即可。
         按接诊月份看趋势：补词表 + 重跑解析只会覆盖部分月份，
         按月看才能判断「哪些月份已经吃到新词表」。 -->
    <StandardizationDetails
      v-if="!loadError"
      :report="report"
      :coverage-rows="coverageRows"
      :has-stale-rows="hasStaleRows"
      :qc-scored="qcScored"
      :qc-total="qcTotal"
      :qc-last="qcLast"
      :score-range="scoreRange"
    />

    <!-- 导出按钮已上移到报告头右侧，页面底部不再留孤立的操作行 -->
  </div>
</template>

<script setup>
// 标准化质量报告（批次 24）。
//
// 设计取向：这页的读者是质控科业务用户，不是工程师。所以
//   ① 先给结论，再给数字，最后才给明细；
//   ② 把「多少条没归一」翻译成「该做什么、归谁管」；
//   ③ 明细默认收起，需要时再展开。
// 技术口径（甲类/乙类、normLevel、可归一实体）只在本文件内部使用，不出现在界面上。
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StandardizationDetails from '@/components/StandardizationDetails.vue'
import StandardizationCharts from '@/components/StandardizationCharts.vue'
import { getStandardizationReport, exportStandardizationReport } from '@/api/stats'
import { submitNlpBatch as submitExtractBatch, getNlpBatchProgress, listNlpBatch } from '@/api/nlp'
import { recomputeQc as submitQcBatch, getQcBatch, listQcBatch } from '@/api/qc'
import { saveBlob } from '@/utils/download'

const report = ref(null)
const loading = ref(false)
// P0-4：加载失败与加载中互斥 —— null 只表示「还没读到」，失败另有其位，
// 否则失败会被渲染成「正在读取…」+ 空态 + 绿色结论（把故障读成了一切正常）。
const loadError = ref(false)
const exporting = ref(false)
const rerunning = ref(false)

// ---- 时间区间 ----
// 病历接诊时间跨度大（实测 2019-01 ~ 2025-12），必须能按时间切：
// 新词表只对重跑过解析的病历生效，而这些病历的接诊时间往往集中在某几个月。
const preset = ref('all')
const customRange = ref(null)

const fmtDate = (d) => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 当前生效的区间；null 表示不限 */
function currentRange() {
  if (preset.value === 'custom') {
    const r = customRange.value
    if (Array.isArray(r) && r.length === 2 && r[0] && r[1]) {
      return { start: r[0], end: r[1] }
    }
    return null
  }
  if (preset.value === '1y' || preset.value === '3y') {
    const years = preset.value === '1y' ? 1 : 3
    const end = new Date()
    const start = new Date(end.getFullYear() - years + 1, 0, 1)
    return { start: fmtDate(start), end: fmtDate(end) }
  }
  return null
}

const rangeTip = computed(() => {
  const r = report.value?.range
  if (!r) return ''
  if (r.start || r.end) {
    return `当前统计 ${r.start || '不限'} ~ ${r.end || '不限'}，`
      + `区间内 ${r.records} 条`
      + (r.excluded > 0 ? `（已排除 ${r.excluded} 条区间外数据）` : '')
  }
  return `当前统计全部 ${r.records} 条`
})

const applyPreset = () => loadReport()
const applyCustom = () => loadReport()

const pct = (part, total) => (total ? `${((part / total) * 100).toFixed(1)}%` : '—')

// ---------------- 质控完成度（决定报告有没有意义）
// 三态而不是两态：未加载 / 已加载且未完成 / 已加载且完成。
// 早前只用两态，页面打开时 report 还是 null，complete 取到 false，
// 「质控没跑完」的告警会先闪一下再消失 —— 看着像出了故障。
const qc = computed(() => report.value?.qc || null)
const qcLoaded = computed(() => qc.value !== null)
const qcComplete = computed(() => qc.value?.complete === true)
/** 只在「确实拿到了结果且不完整」时才提示 */
const showQcGate = computed(() => qcLoaded.value && !qcComplete.value)
const qcTotal = computed(() => qc.value?.total ?? report.value?.dataset?.recordCount ?? 0)
const qcScored = computed(() => qc.value?.scored ?? 0)
const qcLast = computed(() => qc.value?.lastScoredAt || '')
// P2-9：是否存在「解析早于词表」的行 —— 新鲜度徽标的过期态取这里
const hasStaleRows = computed(() => (report.value?.monthly || []).some((r) => r.stale))

/** 批量重跑：质控 / 解析都可能有几千条，必须先让人确认范围 */
async function confirmRerun(what, countHint) {
  try {
    await ElMessageBox.confirm(
      `将对本组织全部病历重跑${what}。${countHint ? `当前数据域内约 ${countHint} 条。` : ''}`
      + '任务在后台执行，本页会自动跟进进度并在完成后刷新报告；'
      + '中途可以离开页面。',
      `确认重跑${what}`,
      { type: 'warning', confirmButtonText: `重跑${what}`, cancelButtonText: '取消' }
    )
    return true
  } catch {
    return false
  }
}

// ---- 重跑任务的进度跟踪 ----
// 重跑是异步的（提交即返回，进度靠轮询）。不轮询的话，用户点了「重跑」却看到
// 页面数字纹丝不动，会以为没生效 —— 实测确认过：提交后页面数据确实不会自己变。
// 做法与 NlpExtract.vue 的批任务轮询一致：定时拉进度，任务结束或失败即停，
// 页面不可见时暂停（省请求）。
const ACTIVE_STATUS = ['QUEUED', 'RUNNING']
const POLL_MS = 3000
const POLL_TIMEOUT_MS = 10 * 60 * 1000

const pollTimer = ref(null)
const pollDeadline = ref(0)
/** 跟踪中的任务：解析任务与质控任务，解析先跑完才轮到质控 */
const track = ref({ nlpId: '', qcId: '', stage: 'idle' })
/** 当前阶段与进度，供页面显示 */
const rerunStage = computed(() => track.value.stage)
const rerunStageLabel = computed(() => {
  const s = track.value.stage
  if (s === 'parse') return '结构化解析进行中'
  if (s === 'qc') return '质控评分进行中'
  return ''
})
const rerunProgress = ref({ done: 0, total: 0 })

/** 进度百分比；拿不到 total 时返回 null（不显示进度条，避免显示 0% 误导） */
const rerunPercent = computed(() => {
  const { done, total } = rerunProgress.value
  if (!total || total <= 0) return null
  return Math.min(100, Math.round((done / total) * 100))
})

const stopPoll = () => {
  if (pollTimer.value) {
    clearInterval(pollTimer.value)
    pollTimer.value = null
  }
}

const isActiveStatus = (t) => !!t && ACTIVE_STATUS.includes(t.status)

/** 任务结束的统一收尾：停轮询、刷一次报告、提示结果 */
async function finishRerun(message) {
  stopPoll()
  track.value = { nlpId: '', qcId: '', stage: 'idle' }
  rerunProgress.value = { done: 0, total: 0 }
  await loadReport()
  if (message) {
    ElMessage.success(message)
  }
}

/** 单次轮询：先看解析任务，再看质控任务；任一阶段结束即推进或收尾 */
async function pollOnce() {
  const { nlpId, qcId, stage } = track.value
  if (!nlpId && !qcId) {
    stopPoll()
    return
  }
  // 超过时限就停：任务可能因数据量大跑很久，不该让页面一直发请求
  if (Date.now() > pollDeadline.value) {
    stopPoll()
    ElMessage.info('重跑仍在后台进行，稍后点「刷新」查看最新结果')
    return
  }
  try {
    if (stage === 'parse' && nlpId) {
      const res = await getNlpBatchProgress(nlpId)
      rerunProgress.value = { done: res.data?.done ?? 0, total: res.data?.total ?? 0 }
      if (!isActiveStatus(res.data)) {
        // 解析结束才能接着算质控：质控读的是 structured_data，解析没完它算的还是旧的。
        // 这里提交质控也可能撞上「另一个任务正在提交」的幂等拒绝 —— 解析刚结束时
        // 它的 cancel/收尾还在占位，所以要容错，不能因为一次拒绝就整条链路断掉。
        let qcId = ''
        try {
          const qc = await submitQcBatch()
          qcId = qc.data?.id || ''
          rerunProgress.value = { done: 0, total: qc.data?.total || 0 }
          ElMessage.info('结构化解析已完成，质控评分已接着开始')
        } catch (e) {
          if (!isAlreadyRunning(e)) {
            await finishRerun('结构化解析已完成，但质控重跑未能启动，请在「质控校验」页手动发起')
            return
          }
          // 已有任务在跑：直接切到盯它，不再重复提交
          qcId = ''
        }
        track.value = { nlpId: '', qcId, stage: qcId ? 'qc' : 'idle' }
        if (!qcId) stopPoll()
      }
      return
    }
    if (stage === 'qc' && qcId) {
      const res = await getQcBatch(qcId)
      rerunProgress.value = { done: res.data?.done ?? 0, total: res.data?.total ?? 0 }
      if (!isActiveStatus(res.data)) {
        const failed = res.data?.failed ?? 0
        await finishRerun(
          failed > 0
            ? `重跑完成，但有 ${failed} 条失败，可在「质控校验」页查看`
            : '重跑完成，报告已刷新'
        )
      }
    }
  } catch {
    // 请求失败即停，避免空转；任务本身可能仍在后台跑
    stopPoll()
    ElMessage.warning('读取重跑进度失败，可稍后点「刷新」查看最新结果')
  }
}

function startPoll() {
  stopPoll()
  pollDeadline.value = Date.now() + POLL_TIMEOUT_MS
  pollTimer.value = setInterval(() => {
    // 页面不可见时暂停：后台标签页没必要持续打接口
    if (document.visibilityState !== 'visible') return
    pollOnce()
  }, POLL_MS)
}

/** 批量重跑：质控评分 */
/**
 * 接管「已经在跑」的那个任务。
 *
 * <p>为什么需要：提交时会撞上后端的两道幂等保护（实测 400 有两种 msg ——
 * 「已有重算任务在排队或运行中」「有另一个重算任务正在提交」）。这不是错误，
 * 而是「你来晚了，任务已经在跑」。此时最合理的响应不是报错让用户干等，
 * 而是从任务列表里找到它、接着盯它的进度 —— 用户看到进度条在走，目标就达到了。</p>
 */
const adoptRunningTask = async (stage) => {
  try {
    const res = stage === 'parse' ? await listNlpBatch() : await listQcBatch()
    const active = (res.data || []).find((t) => ACTIVE_STATUS.includes(t.status))
    if (!active) {
      return false
    }
    track.value = stage === 'parse'
      ? { nlpId: active.id, qcId: '', stage: 'parse' }
      : { nlpId: '', qcId: active.id, stage: 'qc' }
    rerunProgress.value = { done: active.done ?? 0, total: active.total ?? 0 }
    startPoll()
    return true
  } catch {
    // 列表都拿不到（接口异常）就退化为「提示用户稍后刷新」
    return false
  }
}

const submitAndTrack = async (submitFn, stage, startMsg) => {
  try {
    const res = await submitFn()
    const id = res.data?.id || ''
    track.value = stage === 'parse'
      ? { nlpId: id, qcId: '', stage: 'parse' }
      : { nlpId: '', qcId: id, stage: 'qc' }
    rerunProgress.value = { done: 0, total: res.data?.total || 0 }
    startPoll()
    ElMessage.info(startMsg)
  } catch (e) {
    // 「已有任务在跑」是正常状态：不报错，改为接管它并继续显示进度
    if (isAlreadyRunning(e)) {
      const adopted = await adoptRunningTask(stage)
      ElMessage.info(adopted
        ? '已有重跑任务在进行，已接管它的进度'
        : '已有重跑任务在进行，完成后点「刷新」查看最新结果')
    }
    // 其余错误由拦截器提示
  }
}

/** 判断是不是「已有任务在提交/运行」这类幂等拒绝 */
const isAlreadyRunning = (e) => {
  const msg = e?.response?.data?.msg || e?.msg || ''
  return String(msg).includes('另一个重算任务') || String(msg).includes('已有重算任务')
}

/** 批量重跑：质控评分 */
const rerunQc = async () => {
  if (!(await confirmRerun('质控评分', qcTotal.value))) return
  rerunning.value = true
  try {
    await submitAndTrack(submitQcBatch, 'qc', '质控重跑已开始，完成后本页会自动刷新')
  } finally {
    rerunning.value = false
  }
}

/** 批量重跑：先结构化解析、再质控评分 */
const rerunAll = async () => {
  if (!(await confirmRerun('结构化解析与质控', qcTotal.value))) return
  rerunning.value = true
  try {
    await submitAndTrack(submitExtractBatch, 'parse', '结构化解析已开始，完成后会自动接着重跑质控')
  } finally {
    rerunning.value = false
  }
}

/**
 * 找出「最该补词表」的类型。
 *
 * 只在**实际抽取到实体**的类型里比大小：方剂只有 5 条、治法 0 条，
 * 但这两类在当前数据里一条实体都没抽到（数据本身没有对应文本），
 * 按条数排序会把它们排到最前头，给出「先补方剂词表」这种无效建议。
 * 补一张没数据可匹配的词表，对归一率毫无帮助 —— 要补的是
 * 「量最大 × 词表最小」的那一类，那才是真正的瓶颈。
 */
  const bottleneck = computed(() => {
    const d = report.value
    if (!d) return null
    // coverage.field 是 structured_data 的字段名（symptoms/diseases/…），
    // dictQuality.label 是中文名（症状/疾病/…），两者不同名。
    // 必须按 label 对齐 —— 早前误用 field 建 Map 再用 label 查，
    // 结果恒为 0，「最该补的词表」卡片就一直显示「—」。
    const byLabel = new Map((d.coverage || []).map((c) => [c.label, c]))
    const candidates = (d.dictQuality || []).filter((x) => {
      const c = byLabel.get(x.label)
      return x.termCount > 0 && c && c.total > 0
    })
    if (!candidates.length) return null
    // 未归一量降序、词表量升序：先补「缺口大且词表小」的那类
    return candidates
      .map((x) => {
        const c = byLabel.get(x.label)
        return { ...x, unmatched: c.total - c.normalized }
      })
      .sort((a, b) => (b.unmatched - a.unmatched) || (a.termCount - b.termCount))[0]
  })

// ---------------- 空数据判据（P0-5）----------------
// 「没有数据」绝不能渲染成「全部达标」：指标卡的绿色与结论条的绿色对勾都只在
// 真有数据可评时才允许出现。展示分支判定，不动后端任何计算口径。
const isEmptyReport = computed(() => {
  const d = report.value
  if (!d) return false
  return (d.dataset?.recordCount ?? 0) === 0 || (d.coverage || []).length === 0
})

// ---------------- 关键卡 ----------------
// 三张卡各回答一个业务问题：词典够不够、归一顺不顺、评分灵不灵
const kpis = computed(() => {
  const d = report.value
  if (!d) return []
  if (isEmptyReport.value) {
    return [
      { label: '最该补的词表', value: '—', note: '本期无数据可评估', tone: '' },
      { label: '术语归一率', value: '—', note: '本期无数据可评估', tone: '' },
      { label: '评分区分度', value: '—', note: '本期无数据可评估', tone: '' }
    ]
  }
  const gap = d.unmatched || {}
  const gapRate = gap.total ? gap.dictionaryGap / gap.total : 0
  const b = bottleneck.value
  const s = d.score || {}
  const cappedRate = s.total ? s.capped / s.total : 0

  return [
    {
      label: '最该补的词表',
      value: b ? `${b.label} ${b.termCount} 条` : '—',
      note: b
        ? `有 ${b.unmatched} 条实体抽到了却没归上，而它只有 ${b.termCount} 条词；`
        + '补这一类见效最快'
        : '暂无明显短板',
      tone: b && b.unmatched > 0 ? 'warn' : 'ok'
    },
    {
      label: '术语归一率',
      value: symRate(d),
      note: gapRate >= 0.5
        ? `未归一的 ${gap.total} 条里有 ${pct(gap.dictionaryGap, gap.total)} 是词表没收录`
        : '词表覆盖尚可',
      tone: symRateValue(d) < 0.5 ? 'warn' : 'ok'
    },
    {
      label: '评分区分度',
      value: pct(s.capped, s.total),
      note: cappedRate > 0.3
        ? `${pct(s.capped, s.total)} 的病历扣分相同，分数难以区分质量`
        : '扣分分布较分散，评分有区分度',
      tone: cappedRate > 0.3 ? 'warn' : 'ok'
    }
  ]
})

function symRate(d) {
  const sym = (d.coverage || []).find((c) => c.field === 'symptoms')
  return sym && sym.total ? pct(sym.normalized, sym.total) : '—'
}
function symRateValue(d) {
  const sym = (d.coverage || []).find((c) => c.field === 'symptoms')
  return sym && sym.total ? sym.normalized / sym.total : 0
}

// ---------------- 一句话结论（报告头状态）----------------
// 五态互斥：加载失败 / 质控未完成 / 加载中 / 无数据 / 有话要说。
// 前两态此前各占一条独立横幅，现统一由报告头表达，操作就近放在右侧。
const headline = computed(() => {
  // P0-4：失败优先于一切 —— 不读 report（它是 null，会误导成「正在读取…」）
  if (loadError.value) {
    return {
      icon: '!',
      tone: 'danger',
      title: '报告加载失败',
      desc: '服务没有返回数据，本页不展示任何统计结论 —— 故障不能被读成「没有数据」或「一切正常」。'
    }
  }
  // 质控未完成：分数类结论不可信，先说清「为什么不可信」再给重跑入口
  if (showQcGate.value) {
    return {
      icon: '!',
      tone: 'warn',
      title: '质控评分还没跑完，现在的数字不可信',
      desc: `本组织 ${qcTotal.value} 条病历中，有 ${qcScored.value} 条已完成质控评分。`
        + '报告里的「评分区分度」「扣分封顶」都来自质控结果，质控完成后报告才有意义。'
    }
  }
  const d = report.value
  if (!d) return { icon: '·', tone: '', title: '正在读取…', desc: '' }
  // P0-5：空数据走中性分支，不得进入 ok 分支（绿色对勾 + 「合理区间」是假绿灯）
  if (isEmptyReport.value) {
    return {
      icon: '·',
      tone: '',
      title: '本期无数据可评估',
      desc: '统计区间内没有可用于评估的病历数据，换一个时间区间或先导入病历。'
    }
  }
  const u = d.unmatched || {}
  const b = bottleneck.value
  const s = d.score || {}

  if (u.total && u.dictionaryGap / u.total >= 0.5) {
    return {
      icon: '!',
      tone: 'warn',
      title: '词典建设是当前主要瓶颈',
      desc: `术语未归一的 ${u.total} 条里有 ${pct(u.dictionaryGap, u.total)} 是词表里没有收录。`
        + (b ? `最该补的是${b.label}：它只有 ${b.termCount} 条词，却有 ${b.unmatched} 条实体没能归上。` : '')
    }
  }
  if (s.total && s.capped / s.total > 0.5) {
    return {
      icon: '!',
      tone: 'warn',
      title: '评分区分度不足',
      desc: `${pct(s.capped, s.total)} 的病历因「未归一术语」被扣满上限，分数集中在 `
        + `${s.min}~${s.max}，难以据此判断病历质量。`
    }
  }
  return {
    icon: '✓',
    tone: 'ok',
    title: '词典覆盖与评分处于合理区间',
    desc: '暂未发现明显短板，可继续扩充词表提升覆盖面。'
  }
})

// ---------------- 待办清单 ----------------
// 按影响面排序，每条说清「做什么、归谁、能解决多少」
const todos = computed(() => {
  const d = report.value
  if (!d) return []
  const u = d.unmatched || {}
  const list = []
  const b = bottleneck.value

  // ① 先说「解析早于词表建立」—— 它排在最前不是因为最严重，
  //    而是因为它会让后面所有词表相关的判断都失真：不重跑解析，
  //    你在这页看到的归一率全是旧的，补词表的效果也验证不了。
  if (staleTypes.value.length) {
    const names = staleTypes.value.map((c) => `${c.label}（${c.termCount} 词）`).join('、')
    list.push({
      title: '重跑结构化解析',
      desc: `${names} 已抽到实体却一条都没归上 —— 结构化数据早于词表建立，`
        + '不重跑解析，下面几条的效果都验证不了。',
      owner: '抽取',
      tagType: 'warning'
    })
  }

  if (u.dictionaryGap > 0) {
    const real = realGapTypes.value
    const realTotal = real.reduce((a, c) => a + (c.total - c.normalized), 0)
    // 批次2：后端新给的「词表缺口 TOP15」（键=实体原文，值=次数）—— 让这条待办从
    // 「有 N 条没归上」变成「先补这几个词」，用户可直接拿它去补词表（不再需要自己去翻数据）
    const topPairs = Object.entries(u.top || {})
      .sort((a, b) => b[1] - a[1])
      .slice(0, 8)
    const topWords = topPairs.map(([w, n]) => `${w}（${n} 次）`).join('、')
    list.push({
      title: b ? `补充${b.label}标准词表` : '补充标准词表',
      // 批次2：把 TOP8 词原文一并带上，供模板里的「复制待补词」按钮使用（可行动清单的出口）
      words: topPairs.map(([w]) => w),
      desc: `${b ? b.label : '症状'}类现有 ${b ? b.termCount : 72} 条词，`
        + (real.length > 1
          ? `未归一的 ${realTotal} 条分布在 ${real.map((c) => c.label).join('、')}。`
          : `未归一的 ${u.dictionaryGap} 条均属此类。`)
        // 病因类自建库以来就没有独立词表（EntityTypes 的 8 个有词表类型里不含 cause），
        // 因此它的未归一是**结构性的**：用户看到病因近 100% 未归一，很容易以为是系统坏了。
        // 这句是恒定成立的事实，不随数据变化。
        + '病因类无独立词表，其未归一属词表缺口而非解析问题。'
        + (topWords ? `最该先补：${topWords}。` : '')
        + '补完需重跑解析。',
      owner: '词表',
      tagType: 'warning'
    })
  }
  if (u.misrouted > 0) {
    list.push({
      title: '修复脉象、舌象被当成症状',
      desc: `${u.misrouted} 条「脉细数」这类脉象要素被归进了症状，会拉低症状归一率。`,
      owner: '抽取',
      tagType: 'warning'
    })
  }
  const s = d.score || {}
  if (s.total && s.capped / s.total > 0.3) {
    list.push({
      title: '放宽质控扣分上限',
      desc: `${pct(s.capped, s.total)} 的病历「未归一术语」被扣满上限，分数失去区分度。`
        + '建议按未归一数量分段扣分。',
      owner: '规则',
      tagType: 'warning'
    })
  }
  const selfAlias = (d.dictQuality || []).reduce((a, b2) => a + (b2.selfAliasCount || 0), 0)
  if (selfAlias > 0) {
    list.push({
      title: '清理重复别名',
      desc: `有 ${selfAlias} 个词条把标准词本身也写进了别名，`
        + '会在归一时自己命中自己，属词表数据缺陷。',
      owner: '词表',
      tagType: 'info'
    })
  }
  if (u.fragment > 0) {
    list.push({
      title: '减少抽取残词',
      desc: `有 ${u.fragment} 条未归一实体是抽取时截断的残字（如单字），`
        + '属抽取质量问题，与词表无关。',
      owner: '抽取',
      tagType: 'info'
    })
  }
  return list
})

// 待办区的空态文案：真·空数据要说「无数据」，不能说「没有短板、一切合理」
const emptyTip = computed(() => (isEmptyReport.value
  ? '本期无可评估数据，暂无待办建议。'
  : '当前没有明显短板。词典规模、归一与评分都处在合理区间。'))

// ---------------- 明细 ----------------
const coverageRows = computed(() =>
  (report.value?.coverage || []).map((c) => ({
    label: c.label,
    total: c.total,
    normalized: c.normalized,
    termCount: c.termCount,
    stale: c.suspectedStaleExtraction,
    rate: c.total ? pct(c.normalized, c.total) : '未抽取'
  }))
)
/** 词表非空、也抽到了实体，却一条都没归上 —— 这是解析早于词表建立，补词表无效 */
const staleTypes = computed(() =>
  (report.value?.coverage || []).filter((c) => c.suspectedStaleExtraction)
)

/** 真正的「词表没收录」：排除了上述那类，剩下的才是补词表能解决的 */
const realGapTypes = computed(() =>
  (report.value?.coverage || [])
    .filter((c) => c.total > 0 && !c.suspectedStaleExtraction && c.total - c.normalized > 0)
)

const scoreRange = computed(() => {
  const s = report.value?.score
  return s && s.total ? `${s.min} ~ ${s.max}` : '—'
})

const loadReport = async () => {
loading.value = true
loadError.value = false
try {
      // 时间区间为空时把参数省略，不发 start=undefined 这类脏参数
      const range = currentRange()
      const res = await getStandardizationReport(range || undefined)
      report.value = res.data || null
    } catch {
      // 拦截器已提示，这里不叠加泛化文案；
      // P0-4：失败必须落进独立状态位，不能只把 report 置 null 了事
      report.value = null
      loadError.value = true
    } finally {
      loading.value = false
    }
  }

// 导出 CSV（28.23）：改由后端生成文件流，与数据集/日志两条导出口径一致。
// 后端成功回文件流、失败回 JSON，故先判别 blob 类型，避免把错误 JSON 当 CSV 存下来。
const handleExport = async () => {
  const r = report.value
  if (!r) return
  exporting.value = true
  try {
    const blob = await exportStandardizationReport(currentRange() || undefined)
    if (blob && blob.type && blob.type.includes('application/json')) {
      let msg = '导出失败'
      try {
        msg = JSON.parse(await blob.text()).msg || msg
      } catch { /* 解析不出就沿用默认文案 */ }
      ElMessage.error(msg)
      return
    }
    saveBlob(blob, `标准化质量报告-${r.generatedAt.replace(/[-: ]/g, '')}.csv`)
    // P1-7：导出成功此前静默 —— 下载发生在浏览器层，页面必须给一句确认
    ElMessage.success('报告已导出')
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

// ---- 待补词复制 ----

/**
 * 复制待补词到剪贴板。
 *
 * 写成函数而不是模板内联表达式，是为了处理一个真实故障：navigator.clipboard
 * 只在安全上下文（HTTPS / localhost）下存在，内网 HTTP 部署时它是 undefined ——
 * 内联写法 `navigator.clipboard.writeText(...)` 会**同步抛 TypeError**，
 * 后面的 .catch 根本不会执行，用户看到的就是「点了没反应」。
 *
 * 所以走双通道：异步剪贴板 API 可用就优先用；不可用或失败则退回 execCommand 兜底；
 * 两条都失败时把词显示出来让用户手动复制 —— 保证任何情况下都有反馈，不静默失败。
 */
async function copyGapWords(words) {
  const list = words || []
  if (!list.length) return
  const okTip = `已复制 ${list.length} 个待补词，可到「术语词典 → 批量导入」粘贴`

  if (window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(list.join('、'))
      ElMessage.success(okTip)
      return
    } catch {
      // 权限被拒 / 文档未聚焦：落到下面的兜底通道
    }
  }
  if (copyByExecCommand(list.join('、'))) {
    ElMessage.success(okTip)
    return
  }
  // 两条通道都失败：至少让用户能手动复制，不给「静默失败」
  ElMessageBox.alert(list.join('\n'), '复制失败，请手动选中以下待补词复制', {
    confirmButtonText: '知道了'
  })
}

/** execCommand('copy') 兜底：HTTP 环境下的唯一可用通道（API 已废弃，但浏览器仍普遍支持） */
function copyByExecCommand(text) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  ta.style.position = 'fixed'
  ta.style.top = '-1000px'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  try {
    ta.select()
    ta.setSelectionRange(0, text.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(ta)
  }
}

onMounted(() => {
  loadReport()
  // 页面从后台切回来时，若重跑还在跑，立刻补一次进度检查，
  // 否则要等下一个轮询周期才继续（间隔 3 秒，影响很小，但逻辑上更完整）
  document.addEventListener('visibilitychange', onVisibilityChange)
})

// 离开页面必须停轮询：定时器活着会每 3 秒打一次接口，
// 而这个页面可能早就被关掉了 —— 白白发请求，还可能让用户以为页面仍在忙
onBeforeUnmount(() => {
  stopPoll()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

function onVisibilityChange() {
  if (document.visibilityState === 'visible' && pollTimer.value) {
    pollOnce()
  }
}
</script>

<style scoped>
/* ===== 报告头：一句话结论 + 统计口径 + 时间区间 / 刷新 / 导出，单卡承载 =====
   此前「质控门禁条 / 加载失败条 / 数据来源条 / 时间工具条 / 结论条」五条各自
   带左边框的长横幅叠在正文顶部。现收敛为一张卡：左边是随状态变化的结论，
   右侧是操作，异常（失败 / 质控未完成）的重跑入口与进度就近放在结论下方。
   状态底色沿用旧结论条的三档语义：warn=赭石浅底，ok=米灰底，danger=警示浅底。 */
.rpt-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: var(--sp-3) var(--sp-4);
  padding: var(--sp-4);
  margin-bottom: var(--sp-3);
  background: var(--surface);
  border: 1px solid var(--line);
  border-left: 3px solid var(--ink-mid);
  /* P2-1：容器类统一 6px 圆角（与 PanelCard 同档） */
  border-radius: 6px;
}
.rpt-head.warn {
  border-left-color: var(--ochre);
  background: var(--ochre-surface);
}
.rpt-head.ok {
  border-left-color: var(--success);
  background: var(--surface-sub);
}
.rpt-head.danger {
  border-left-color: var(--danger);
  background: var(--danger-surface);
}
/* 状态区：占剩余宽度，窄则换行 */
.rh-status {
  flex: 1 1 420px;
  min-width: 0;
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
}
.rh-icon {
  flex: 0 0 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--ink-mid);
  color: var(--surface);
  font-size: var(--fs-xs);
  font-weight: 700;
}
.rpt-head.warn .rh-icon { background: var(--ochre-deep); }
.rpt-head.ok .rh-icon { background: var(--success); }
.rpt-head.danger .rh-icon { background: var(--danger); }
.rh-text { min-width: 0; }
.rh-title {
  font-size: var(--fs-title);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.rh-desc {
  margin: 0;
  font-size: var(--fs-base);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
/* 结论下方的两条次级说明：质控未完成时的「仍可看什么」、测试数据口径提示 */
.rh-note,
.rh-caveat {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
.rh-caveat { color: var(--text-sub); }
/* 操作区：时间区间 / 刷新 / 导出 */
.rh-tools {
  flex: 0 1 auto;
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}
/* 质控未完成的重跑入口 + 进度：与状态同卡，靠上边框分组 */
.rh-ops {
  flex: 1 1 100%;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-1);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
}
.rh-progress {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 200px;
}
.gp-label {
  flex: 0 0 auto;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  white-space: nowrap;
}
/* 当前统计区间的口径说明 */
.rh-range {
  flex: 1 1 100%;
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}

/* 关键卡 */
.kpi-row {
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
  /* P2-2：区块间距收口到 --sp-3 —— 与上下相邻区块同一档 */
  margin-bottom: var(--sp-3);
}
.kpi {
  /* 三张卡等分一行：基宽按 3 等分取（扣掉 2 个 gap），禁止 grow ——
     此前误按 4 等分，三张卡只占 3/4 宽，右侧留出一条空白。
     窄屏走 2 列 / 1 列断点，同行永远等宽。 */
  flex: 0 1 calc((100% - 2 * var(--sp-3)) / 3);
  min-width: 0;
  /* P0-6：min-height 把骨架与终态钉在同一高度，加载不产生布局跳动 */
  min-height: 134px;
  padding: var(--sp-3);
  border: 1px solid var(--line);
  /* P2-1：容器类 6px */
  border-radius: 6px;
  background: var(--surface);
}
.kpi.warn { border-left: 3px solid var(--ochre); }
.kpi.ok { border-left: 3px solid var(--success); }
/* 窄视口：两列等宽（最后一张独占一行的场景由 flex-grow:0 保证不被拉满，保持卡宽一致） */
@media (max-width: 1100px) {
  .kpi { flex-basis: calc((100% - var(--sp-3)) / 2); }
}
@media (max-width: 700px) {
  .kpi { flex-basis: 100%; }
}
/* 骨架块 —— 静态占位（全站动效令牌约束：不允许新增字面量 transition/animation） */
.kpi-skel { display: block; }
.sk {
  display: block;
  background: var(--surface-sub);
  border: 1px solid var(--line);
  border-radius: 4px;
}
.sk-label { width: 72px; height: 14px; margin-bottom: 8px; }
.sk-value { width: 116px; height: 34px; margin-bottom: 10px; }
.sk-note { width: 70%; height: 14px; }
.kpi-label {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-bottom: 2px;
}
.kpi-value {
  font-size: var(--fs-xl);
  font-weight: 600;
  color: var(--ink);
  line-height: 1.3;
}
.kpi-note {
  margin-top: 4px;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--text-sub-strong);
}
/* 待办清单 */
.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.todo {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
  padding: var(--sp-3) 0;
  border-bottom: 1px dashed var(--line);
}
.todo:last-child { border-bottom: none; }
.todo-idx {
  flex: 0 0 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 50%;
  background: var(--ink-mid);
  color: var(--surface);
  font-size: var(--fs-xs);
  font-weight: 600;
}
.todo-body { flex: 1 1 auto; min-width: 0; }
.todo-title {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.todo-desc {
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
  /* P2-5：长说明限宽 —— 避免单行 85+ 字远超中文长文可读行宽 */
  max-width: 88ch;
}
/* P1-8：右侧固定宽度操作列。无按钮的行用 .todo-act 同宽占位，
     保证多条待办的标签左缘竖直成列（A17）。 */
.todo-ops {
  flex: 0 0 160px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-2);
}
.todo-act {
  flex: 0 0 96px;
  display: flex;
  justify-content: flex-end;
}
/* P0-6：待办区加载占位 —— 2~3 行，不再是 0 高 */
.todo-list .sk-line {
  height: 14px;
  border-radius: 4px;
  background: var(--surface-sub);
  margin-bottom: 6px;
}
.sk-w40 { width: 40%; }
.sk-w90 { width: 90%; }
.sk-tag {
  flex: 0 0 48px;
  height: 20px;
  border-radius: 4px;
  background: var(--surface-sub);
}
.empty-tip {
  padding: var(--sp-3) 0;
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
}

/*
 * 打印 / 导出 PDF 功能已移除（2026-10-07）：原 28.18 的「打印 / 另存 PDF」按钮
 * 走浏览器打印，后端 pdfbox 从未使用且无 CJK 字体，实际依赖用户在打印对话框里
 * 「另存为 PDF」，体验鸡肋，按需求整段删除（按钮 / handlePrint / scoped @media print）。
 */
</style>