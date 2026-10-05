<template>
  <div class="std-report">
    <!-- 质控未完成时先挡一道：报告里的分数分布与封顶率都来自 qc_results，
         质控没跟上就展示结论等于拿旧数据误导人。 -->
    <div v-if="showQcGate" class="gate">
      <div class="gate-icon">!</div>
      <div class="gate-body">
        <div class="gate-title">质控评分还没跑完，现在的数字不可信</div>
        <div class="gate-desc">
          本组织 {{ qcTotal }} 条病历中，有 {{ qcScored }} 条已完成质控评分。
          报告里的「评分区分度」「扣分封顶」都来自质控结果，
          <b>质控完成后报告才有意义</b>，否则这些数字是上一次的结果。
        </div>
<div class="gate-ops">
            <el-button type="primary" size="small" :loading="rerunning" @click="rerunQc">
              立即重跑质控
            </el-button>
            <el-button size="small" :loading="rerunning" @click="rerunAll">
              重跑「解析 + 质控」
            </el-button>
            <span v-if="qcLast" class="tip">上次完成：{{ qcLast }}</span>
          </div>
          <!-- 重跑进行中的进度条：没有它用户只能干等，不知道系统在不在动 -->
          <div v-if="rerunStage !== 'idle'" class="gate-progress">
            <span class="gp-label">{{ rerunStageLabel }}</span>
            <el-progress
              v-if="rerunPercent !== null"
              :percentage="rerunPercent"
              :stroke-width="6"
              style="flex: 1 1 auto; min-width: 120px"
            />
          </div>
          <div class="gate-note">
            只想看词典建设进度（甲类），可继续往下看 —— 那部分与质控无关。
          </div>
      </div>
    </div>

      <!-- 数据来源提示：放在顶部但用轻量样式，不用刺眼的告警条。
           这批数据只有 10 个模板，数字不能当真实病历性能看，但也不该拦住用户往下读。 -->
      <div class="src-note">
        <span class="src-icon">i</span>
        <span>
          当前数据共 {{ report?.dataset?.recordCount ?? 0 }} 条病历，主诉只有
          {{ report?.dataset?.chiefComplaintTemplates ?? 0 }} 种写法，属于测试数据。
          下面的数字用于<strong>验证词典建设进度</strong>，不代表真实病历上的准确率。
        </span>
      </div>

      <!-- 时间维度：病历接诊时间跨度大，混在一起看不出「换了词表之后有没有变好」 -->
      <PanelCard title="统计区间">
        <div class="time-row">
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
          <el-button size="small" :loading="loading" @click="loadReport">刷新</el-button>
          <span class="tip">{{ rangeTip }}</span>
        </div>
      </PanelCard>


    <!-- 第一屏：一句话结论 + 三个关键卡。看这一屏就知道该做什么、去哪看。 -->
    <div class="headline" :class="headline.tone">
      <div class="hl-icon">{{ headline.icon }}</div>
      <div class="hl-text">
        <div class="hl-title">{{ headline.title }}</div>
        <div class="hl-desc">{{ headline.desc }}</div>
      </div>
    </div>

    <div class="kpi-row">
      <div v-for="k in kpis" :key="k.label" class="kpi" :class="k.tone">
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-value">{{ k.value }}</div>
        <div class="kpi-note">{{ k.note }}</div>
      </div>
    </div>

    <!-- 第二屏：待办清单。这是本页最有价值的部分 ——
         把「多少条未归一」翻译成「该做什么、归谁、值多少」。 -->
    <PanelCard title="建议的下一步">
      <template #extra>
        <span class="tip">按影响面排序，先做第一条</span>
      </template>
      <ol class="todo-list">
        <li v-for="(t, i) in todos" :key="i" class="todo">
          <div class="todo-idx">{{ i + 1 }}</div>
          <div class="todo-body">
            <div class="todo-title">{{ t.title }}</div>
            <div class="todo-desc">{{ t.desc }}</div>
          </div>
          <el-tag size="small" :type="t.tagType" effect="plain">{{ t.owner }}</el-tag>
        </li>
      </ol>
      <div v-if="!todos.length" class="empty-tip">
        当前没有明显短板。词典规模、归一与评分都处在合理区间。
      </div>
    </PanelCard>

    <!-- 第三屏：明细默认收起。业务用户通常不需要逐类看，展开即可。 -->
      <!-- 按接诊月份看趋势：补词表 + 重跑解析只会覆盖部分月份，
           按月看才能判断「哪些月份已经吃到新词表」 -->
      <el-collapse class="detail">
        <el-collapse-item name="month">
          <template #title>
            <span class="ct">按月份看</span>
            <span class="ct-sub">补词表后逐月对比，看新词表吃到了哪些数据</span>
          </template>
          <el-table :data="report?.byMonth || []" border size="small" max-height="320">
            <el-table-column prop="month" label="月份" width="110" />
            <el-table-column prop="records" label="病历数" width="90" align="right" />
            <el-table-column label="症状归一率" min-width="120">
              <template #default="{ row }">
                <span v-if="row.symptomRate">{{ row.symptomRate }}</span>
                <span v-else class="tip">未抽取</span>
              </template>
            </el-table-column>
            <el-table-column prop="dictionaryGap" label="词表缺口" width="100" align="right" />
            <el-table-column prop="avgScore" label="平均分" width="90" align="right" />
            <el-table-column label="扣分封顶" width="100" align="right">
              <template #default="{ row }">
                <span :class="{ warn: row.capped > 0 }">{{ row.capped }}</span>
              </template>
            </el-table-column>
          </el-table>
          <p class="detail-note">
            「词表缺口」是该月未归一里属于「标准词但词表没有收录」的部分 ——
            补词表能直接解决的就是它。若某个月缺口明显比别的月多，多半是那个月的
            数据还没重跑过解析。
          </p>
        </el-collapse-item>

        <el-collapse-item name="dict">
        <template #title>
          <span class="ct">各词典明细</span>
          <span class="ct-sub">词条数、别名与编码情况</span>
        </template>
        <el-table :data="report?.dictQuality || []" border size="small">
          <el-table-column prop="label" label="类型" width="90" />
          <el-table-column prop="termCount" label="词条数" width="90" align="right" />
          <el-table-column label="有别名" width="100" align="right">
            <template #default="{ row }">{{ row.aliasedCount }}</template>
          </el-table-column>
          <el-table-column label="有国标编码" width="120" align="right">
            <template #default="{ row }">
              <span :class="{ warn: row.codedCount === 0 }">{{ row.codedCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="别名重复" width="100" align="right">
            <template #default="{ row }">
              <span :class="{ warn: row.selfAliasCount > 0 }">{{ row.selfAliasCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="source" label="词表来源" min-width="170" show-overflow-tooltip />
        </el-table>
        <p v-if="report?.crossTypeDuplicates?.length" class="detail-note">
          另有 {{ report.crossTypeDuplicates.length }} 个术语同时出现在多本词典里
          （如 {{ report.crossTypeDuplicates.slice(0, 6).join('、') }}），
          同一词可能在不同类型下命中不同词典，建议只在一处保留。
        </p>
      </el-collapse-item>

      <el-collapse-item name="cover">
        <template #title>
          <span class="ct">各类术语归一情况</span>
          <span class="ct-sub">抽取了多少、归一了多少</span>
        </template>
        <el-table :data="coverageRows" border size="small">
          <el-table-column prop="label" label="类型" width="90" />
          <el-table-column prop="total" label="抽取到" width="100" align="right" />
          <el-table-column prop="normalized" label="已归一" width="100" align="right" />
          <el-table-column prop="termCount" label="词条数" width="90" align="right" />
          <el-table-column label="归一率" min-width="110">
            <template #default="{ row }">{{ row.rate }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="150">
            <template #default="{ row }">
              <span v-if="row.stale" class="stale-tag">解析早于词表，需重跑</span>
            </template>
          </el-table-column>
        </el-table>
      </el-collapse-item>

      <el-collapse-item name="detail-misc">
        <template #title>
          <span class="ct">诊断明细</span>
          <span class="ct-sub">可用于对外核对口径</span>
        </template>
        <!-- 体验修复（2026-10-05）：此前页面没有任何「本页不自动刷新」的说明，
             用户不知道报告何时更新、该点哪个按钮才生效。 -->
        <div style="margin-bottom:8px; padding:8px 10px; font-size:12px; line-height:1.7;
                    color:var(--text); background:var(--ochre-light); border-left:3px solid var(--ochre); border-radius:4px;">
          <b>本页不会自动刷新。</b>怎么让它更新：
          <b>补了词表</b> → 点「重跑『解析 + 质控』」（新词表只对重跑过解析的病历生效）；
          <b>只改质控规则</b> → 点「立即重跑质控」即可；
          <b>只是想再看一遍</b> → 点右上角「刷新」。
          下方列表里带「解析早于词表，需重跑」标记的行，就是还没吃到新词表的病历。
        </div>
        <div class="misc-grid">
                    <FreshnessTag :time="report?.generatedAt" :stale="hasStaleRows"
                        reason="部分病历的解析早于词表，需重跑" />
          <div>
            <span>质控完成</span>
            <b>{{ qcScored }} / {{ qcTotal }}{{ qcLast ? `（${qcLast}）` : '' }}</b>
          </div>
          <div><span>病历总数</span><b>{{ report?.dataset?.recordCount ?? 0 }}</b></div>
          <div><span>主诉写法种类</span><b>{{ report?.dataset?.chiefComplaintTemplates ?? 0 }}</b></div>
          <div><span>来自患者口语的记录</span><b>{{ report?.dataset?.recordsWithColloquialSymptom ?? 0 }}</b></div>
          <div><span>评分区间</span><b>{{ scoreRange }}</b></div>
          <div><span>平均分</span><b>{{ report?.score?.avg ?? '—' }}</b></div>
        </div>
        <p class="detail-note">{{ report?.disclaimer }}</p>
      </el-collapse-item>
    </el-collapse>

    <div class="foot">
      <el-button size="small" :loading="exporting" @click="handleExport">导出 CSV</el-button>
      <span class="tip">导出全部明细指标，便于存档或与他人核对口径</span>
    </div>
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
import FreshnessTag from '@/components/FreshnessTag.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import { getStandardizationReport } from '@/api/stats'
import { submitNlpBatch as submitExtractBatch, getNlpBatchProgress, listNlpBatch } from '@/api/nlp'
import { recomputeQc as submitQcBatch, getQcBatch, listQcBatch } from '@/api/qc'
import { saveBlob } from '@/utils/download'

const report = ref(null)
const loading = ref(false)
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

// ---------------- 关键卡 ----------------
// 三张卡各回答一个业务问题：词典够不够、归一顺不顺、评分灵不灵
const kpis = computed(() => {
  const d = report.value
  if (!d) return []
  const gap = d.unmatched || {}
  const gapRate = gap.total ? gap.dictionaryGap / gap.total : 0
  const b = bottleneck.value
  const noCode = (d.dictQuality || []).filter((x) => x.codedCount === 0).length
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
    },
    {
      label: '国标编码',
      value: noCode === 0 ? '已覆盖' : `${noCode} 类缺`,
      note: noCode === 0 ? '词表已带编码' : '缺编码时术语无法与国标库对接',
      tone: noCode === 0 ? 'ok' : 'warn'
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

// ---------------- 一句话结论 ----------------
const headline = computed(() => {
  const d = report.value
  if (!d) return { icon: '·', tone: '', title: '正在读取…', desc: '' }
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
      desc: `${names} 的词表已经有词、也抽到了实体，却一条都没归上 —— `
        + `说明这些结构化数据是在词表建好之前算出来的。`
        + `不重跑解析，下面几条的效果都验证不了。`,
      owner: '抽取',
      tagType: 'warning'
    })
  }

  if (u.dictionaryGap > 0) {
    const real = realGapTypes.value
    const realTotal = real.reduce((a, c) => a + (c.total - c.normalized), 0)
    // 批次2：后端新给的「词表缺口 TOP15」（键=实体原文，值=次数）—— 让这条待办从
    // 「有 N 条没归上」变成「先补这几个词」，用户可直接拿它去补词表（不再需要自己去翻数据）
    const topWords = Object.entries(u.top || {})
      .sort((a, b) => b[1] - a[1])
      .slice(0, 8)
      .map(([w, n]) => `${w}（${n} 次）`)
      .join('、')
    list.push({
      title: b ? `补充${b.label}标准词表` : '补充标准词表',
      desc: `按国家/行业标准术语集补录，不从现有数据反推。`
        + `症状类现有 ${b ? b.termCount : 72} 条词，`
        + (real.length > 1
          ? `而未归一的 ${realTotal} 条分布在 ${real.map((c) => c.label).join('、')}。`
          : `未归一的 ${u.dictionaryGap} 条属于这一类。`)
        // 病因类自建库以来就没有独立词表（EntityTypes 的 8 个有词表类型里不含 cause），
        // 因此它的未归一是**结构性的**：用户看到病因近 100% 未归一，很容易以为是系统坏了。
        // 这句是恒定成立的事实，不随数据变化。
        + `另：病因类目前没有独立词表，其未归一属词表缺口而非解析问题。`
        + (topWords ? `最该先补的 ${topWords.split('、').length} 个（按出现次数）：${topWords}。` : '')
        + '补完后需重跑解析才能看到效果。',
      owner: '词表',
      tagType: 'warning'
    })
  }
  if (u.misrouted > 0) {
    list.push({
      title: '修复脉象、舌象被当成症状',
      desc: `有 ${u.misrouted} 条「脉细数」「左尺无力」这类脉象要素被归进了症状，`
        + '会让症状归一率被拉低，也可能影响完整性判定。',
      owner: '抽取',
      tagType: 'warning'
    })
  }
  const noCode = (d.dictQuality || []).filter((x) => x.codedCount === 0).length
  if (noCode > 0) {
    list.push({
      title: '补录国标编码',
      desc: `${noCode} 类词典尚无国标编码。编码是术语与国标/医保/ICD 对接的钥匙，`
        + '缺编码时词典只能用于本系统内部匹配。',
      owner: '词表',
      tagType: 'info'
    })
  }
  const s = d.score || {}
  if (s.total && s.capped / s.total > 0.3) {
    list.push({
      title: '放宽质控扣分上限',
      desc: `${pct(s.capped, s.total)} 的病历「未归一术语」一项被扣满上限，`
        + '后续再扣也不增加扣分，导致分数失去区分度。建议按未归一数量分段扣分。',
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
try {
      // 时间区间为空时把参数省略，不发 start=undefined 这类脏参数
      const range = currentRange()
      const res = await getStandardizationReport(range || undefined)
      report.value = res.data || null
    } catch {
      // 拦截器已提示，这里不叠加泛化文案
      report.value = null
    } finally {
      loading.value = false
    }
  }

/** 导出 CSV：BOM 头让 Excel 正确识别 UTF-8，否则中文全是乱码 */
const handleExport = () => {
  const r = report.value
  if (!r) return
  exporting.value = true
  try {
    const rows = [['区块', '指标', '数值', '说明']]
    r.dictQuality.forEach((d) => {
      rows.push(['词典质量', `${d.label} 词条数`, d.termCount, d.source || ''])
      rows.push(['词典质量', `${d.label} 有别名`, d.aliasedCount, `共 ${d.termCount} 条`])
      rows.push(['词典质量', `${d.label} 有国标编码`, d.codedCount, `共 ${d.termCount} 条`])
      rows.push(['词典质量', `${d.label} 别名重复`, d.selfAliasCount, '别名含标准词本身会自命中'])
    })
    rows.push(['词典质量', '同名术语跨词典', r.crossTypeDuplicates.length, '同一词出现在多本词典'])
    r.coverage.forEach((c) => {
      rows.push(['归一情况', `${c.label} 归一率`, c.total ? pct(c.normalized, c.total) : '未抽取',
        `抽取 ${c.total} 条`])
    })
    const u = r.unmatched
    rows.push(['未归一构成', '词表未收录', u.dictionaryGap, '责任：词表'])
    rows.push(['未归一构成', '脉/舌被当成症状', u.misrouted, '责任：抽取'])
    rows.push(['未归一构成', '体征被当成症状', u.physicalSign, '责任：抽取'])
    rows.push(['未归一构成', '抽取残词', u.fragment, '责任：抽取'])
    rows.push(['未归一构成', '合计', u.total, ''])
    const s = r.score
    rows.push(['评分', '扣分相同占比', pct(s.capped, s.total), `平均分 ${s.avg}，区间 ${s.min}~${s.max}`])
    // 时间维度一并导出：脱离区间的月度数据没有意义
    const rg = r.range || {}
    rows.push(['区间', '统计区间',
      `${rg.start || '不限'} ~ ${rg.end || '不限'}`, `区间内 ${rg.records} 条，排除 ${rg.excluded || 0} 条`])
    ;(r.byMonth || []).forEach((m) => {
      rows.push(['按月份', `${m.month} 病历数`, m.records, ''])
      rows.push(['按月份', `${m.month} 症状归一率`, m.symptomRate || '未抽取', ''])
      rows.push(['按月份', `${m.month} 词表缺口`, m.dictionaryGap, '补词表可解决'])
      rows.push(['按月份', `${m.month} 平均分`, m.avgScore, `扣分封顶 ${m.capped}`])
    })
    rows.push(['数据集', '病历总数', r.dataset.recordCount, ''])
    rows.push(['数据集', '主诉写法种类', r.dataset.chiefComplaintTemplates, '远小于病历数说明是测试数据'])
    rows.push(['数据集', '来自患者口语', r.dataset.recordsWithColloquialSymptom, '口语不是标准症状词'])
    rows.push(['声明', r.disclaimer, '', r.generatedAt])

    const csv = rows
      .map((x) => x.map((c) => `"${String(c ?? '').replace(/"/g, '""')}"`).join(','))
      .join('\r\n')
    saveBlob(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }),
      `标准化质量报告-${r.generatedAt.replace(/[-: ]/g, '')}.csv`)
  } finally {
    exporting.value = false
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
/* 质控未完成时的拦截提示：这是「结论不可信」的告知，不是报错 */
.gate {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: var(--sp-3);
  border-left: 3px solid var(--ochre);
  background: var(--ochre-surface);
  border-radius: 4px;
}
.gate-icon {
  flex: 0 0 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 50%;
  background: var(--ochre);
  color: var(--surface);
  font-size: 12px;
  font-weight: 700;
}
.gate-body { flex: 1 1 auto; min-width: 0; }
.gate-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.gate-desc {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}
.gate-ops {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  flex-wrap: wrap;
  margin-top: var(--sp-2);
}
/* 重跑进度：把「在跑」可视化，否则用户只能干等 */
.gate-progress {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}
.gp-label {
  flex: 0 0 auto;
  font-size: 12.5px;
  color: var(--text-sub);
  white-space: nowrap;
}
.gate-note {
  margin-top: var(--sp-2);
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-sub);
}
/* 时间区间条 */
.time-row {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  flex-wrap: wrap;
}
/* 数据来源提示：轻量，不拦截阅读 */
.src-note {
  display: flex;
  gap: var(--sp-2);
  align-items: flex-start;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: var(--sp-3);
  background: var(--surface-sub);
  border-radius: 4px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}
.src-icon {
  flex: 0 0 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  border-radius: 50%;
  background: var(--text-sub);
  color: var(--surface);
  font-size: 11px;
  font-weight: 600;
}
/* 结论条：一句话把「该做什么」说清 */
.headline {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: var(--sp-3);
  border-radius: 4px;
  border-left: 3px solid var(--ink-mid);
  background: var(--ink-light);
}
.headline.warn {
  border-left-color: var(--ochre);
  background: var(--ochre-surface);
}
.headline.ok {
  border-left-color: var(--success, #3a7d44);
  background: var(--surface-sub);
}
.hl-icon {
  flex: 0 0 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 700;
  color: var(--surface);
  background: var(--ink-mid);
}
.headline.warn .hl-icon { background: var(--ochre); }
.headline.ok .hl-icon { background: var(--success, #3a7d44); }
.hl-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.hl-desc {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-sub);
}
/* 关键卡 */
.kpi-row {
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: var(--sp-4);
}
.kpi {
  flex: 1 1 200px;
  min-width: 190px;
  padding: var(--sp-3);
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--surface);
}
.kpi.warn { border-left: 3px solid var(--ochre); }
.kpi.ok { border-left: 3px solid var(--success, #3a7d44); }
.kpi-label {
  font-size: 12.5px;
  color: var(--text-sub);
  margin-bottom: 2px;
}
.kpi-value {
  font-size: 21px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.3;
}
.kpi-note {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-sub);
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
  font-size: 12px;
  font-weight: 600;
}
.todo-body { flex: 1 1 auto; min-width: 0; }
.todo-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.todo-desc {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}
.empty-tip {
  padding: var(--sp-3) 0;
  font-size: 13px;
  color: var(--text-sub);
}
/* 明细折叠 */
.detail { margin-bottom: var(--sp-3); }
.ct { font-size: 13.5px; font-weight: 600; color: var(--ink); }
.ct-sub { margin-left: var(--sp-2); font-size: 12px; color: var(--text-sub); font-weight: 400; }
.detail-note {
  margin-top: var(--sp-2);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}
.misc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--sp-2) var(--sp-4);
  font-size: 12.5px;
}
.misc-grid span { color: var(--text-sub); margin-right: 6px; }
.misc-grid b { color: var(--ink); font-weight: 600; }
.warn { color: var(--ochre); font-weight: 600; }
/* 「解析早于词表」标记：这一种补词表无效，得重跑解析，所以要显式标出来 */
.stale-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--ochre-surface);
  color: var(--ochre);
  font-size: 12px;
  font-weight: 600;
}
.foot {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}
</style>