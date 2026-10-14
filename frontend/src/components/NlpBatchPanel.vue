<template>
  <PanelCard title="批量结构化解析">
    <div class="tip">
      选定范围后提交，由<b>后台任务</b>逐条抽取并写入病历。提交后可关闭本页，任务在服务端继续；
      进度与失败清单保存在服务端，随时回来查看。
    </div>

    <div class="batch-filter">
      <div class="bf-title">筛选范围</div>
      <RangeFilter v-model="batchFilters" />
    </div>

    <div class="batch-row">
      <span>处理范围</span>
      <el-radio-group v-model="batchMode" :disabled="submitting" size="small">
        <el-radio value="all">全部</el-radio>
        <el-radio value="limit">指定前 N 条</el-radio>
      </el-radio-group>
      <el-input-number
        v-if="batchMode === 'limit'"
        v-model="batchLimit"
        :min="1"
        :max="40000"
        :step="100"
        size="small"
        :disabled="submitting"
      />
      <span class="tip">条数多时后台跑得久，可关闭页面稍后回来</span>
    </div>

    <div class="batch-row">
      <!-- 只对「批量解析」生效：只抽从未解析过的病历，不重跑已解析的几万条 -->
      <el-checkbox v-model="onlyUnparsed" :disabled="submitting" size="small">
        只解析未解析的（跳过已有结构化数据的病历）
      </el-checkbox>
    </div>

    <div class="actions">
      <el-button type="primary" :loading="submitting" @click="submitBatch">开始批量解析</el-button>
      <el-button :loading="submitting" @click="submitNormalizeOnly">开始批量归一</el-button>
      <el-button :disabled="submitting" @click="loadBatchList">刷新任务列表</el-button>
    </div>

    <div class="tip">
      <b>批量解析</b>＝实体抽取 + 术语归一（会重新调用 NLP 服务）；
      <b>批量归一</b>＝只按最新词典重跑归一，不重新抽取实体（补词典后用，快很多）。
    </div>

    <!-- 当前 / 选中任务 -->
    <div v-if="activeTask" class="batch-progress">
      <div class="bp-hd"><b>{{ statusText(activeTask) }}</b></div>
      <el-progress v-bind="progressBar" :stroke-width="10" />
      <div class="bp-sub">
        共 {{ activeTask.total }} 条 · 已处理 {{ activeTask.done }} · 成功 {{ activeTask.success }} · 失败 {{ activeTask.failed }}
        <span v-if="activeTask.current"> · 当前 {{ activeTask.current }}</span>
      </div>
      <!-- 长任务最需要的是「还要多久」：只有 done 前进过才显示（见 updateEta），
           估不出来时整行不出现，不给凭空的数字 -->
      <div v-if="etaText" class="bp-sub">{{ etaText }}</div>
      <div v-if="isActive(activeTask)" class="actions">
        <el-button size="small" @click="cancelBatch(activeTask.id)">取消任务</el-button>
      </div>

      <div v-if="activeTask.failures && activeTask.failures.length" class="batch-failures">
        <div class="bf-hd">
          失败清单（{{ activeTask.failures.length }} 条{{ activeTask.failureTruncated ? '，仅显示前 500 条' : '' }}）
        </div>
        <!-- V8b：去掉原先写死的 max-height="240" —— 卡内再开一层滚动，与 main 的页内滚动
             叠成「两层嵌套」：失败清单内容高可达上千 px，卡内却只能滚 5 屏，且滚动条
             藏在卡片底部很难发现。改为随内容撑高，由页面（main）统一滚动；
             上方计数与截断说明保留，不丢信息。 -->
        <el-table :data="activeTask.failures" border size="small">
          <el-table-column prop="label" label="病历" width="200" show-overflow-tooltip />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column label="详情" width="90">
            <template #default="{ row }">
              <el-button v-if="row.recordId" link type="primary" @click="openRecordDetail(row.recordId)">查看</el-button>
              <span v-else class="bf-muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!isActive(activeTask) && activeTask.failed > 0" class="actions">
          <!-- 仅重跑失败那批（复用原任务模式），不重跑全部 -->
          <el-button type="warning" :loading="submitting" @click="retryFailed(activeTask.id)">
            仅重试失败（{{ activeTask.failed }} 条）
          </el-button>
        </div>
      </div>
    </div>

    <!-- 最近任务 -->
    <div v-if="batchTasks.length" class="batch-list">
      <div class="bf-hd">
        最近任务（最多 {{ taskListLimit }} 条）<span v-if="taskListTruncated" class="bf-trunc">已省略更早的任务</span>
        <!-- 批次4：长任务要能一眼找到「哪些批次出过错」，不必逐条看状态 -->
        <el-checkbox v-model="onlyFailedTasks" size="small" style="margin-left: var(--sp-3)">只看有失败的</el-checkbox>
      </div>
      <!-- V8b：同失败清单 —— 去掉写死的 max-height="260"，卡内不再自开滚动层，
           表格随任务条数撑高，与 main 页内滚动合一；标题行的条数上限与截断说明保留。 -->
      <el-table
        :data="onlyFailedTasks ? batchTasks.filter((t) => t.failed > 0) : batchTasks"
        border
        size="small"
      >
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ fmtDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ row.mode === 'NORMALIZE_ONLY' ? '仅归一' : '解析' }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="240">
          <template #default="{ row }">{{ statusText(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button v-if="isActive(row)" link type="danger" @click="cancelBatch(row.id)">取消</el-button>
            <template v-else>
              <el-button link type="primary" @click="viewTask(row.id)">查看</el-button>
              <el-button v-if="row.failed > 0" link type="warning" @click="retryFailed(row.id)">重试失败</el-button>
              <!-- 与「开始批量解析」同一条路径：按当前表单范围重新提交，不是重跑那一条任务 -->
              <el-button link type="primary" @click="submitBatch">重新提交</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <RecordDetailDialog v-model="detailVisible" :record="detailRecord" />
  </PanelCard>
</template>

<script setup>
// 批量结构化解析面板（从 NlpExtract.vue 页内抽出，28.16）：
// 提交后台批量任务、轮询进度、列出最近任务。页签用 lazy 挂载，
// 因此组件挂载即首屏加载任务列表，切走时由 active 属性停掉轮询。
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import RecordDetailDialog from '@/components/RecordDetailDialog.vue'
import { submitNlpBatch, submitNlpNormalizeBatch, getNlpBatchProgress, cancelNlpBatch, retryNlpBatchFailed, listNlpBatch } from '@/api/nlp'
import { searchRecords, countRecords, getRawRecord } from '@/api/records'
import { fmtDateTime } from '@/utils/format'
import { confirmBox } from '@/utils/confirm'

const props = defineProps({
  // 当前页签是否为「批量解析」：切走时停轮询，避免单条页签下空转发请求
  active: { type: Boolean, default: false }
})

// 批次4：批量任务列表的「只看有失败的」开关（报告 §2.11 第 3 行：长任务要能下钻到失败）
const onlyFailedTasks = ref(false)

// §七 L4：上限与目标数据集规模对齐（40000），同时是后端 NlpBatchDTO.limit 的 @Max
// 默认值也从 1000 提到 40000：用户要批量解析时常常就是「全部」，
// 默认 1000 会让他以为只解析了 1000 条。上限由后端强制，这里只是提示。
const batchLimit = ref(40000)
const batchMode = ref('all')
const submitting = ref(false)
// 批量范围条件（科室 / 就诊时间 / 证候 / 分级），与病历数据页同一套筛选
const batchFilters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })
// 只解析未解析的（仅对「批量解析」生效；默认开，避免重跑已解析的几万条）
const onlyUnparsed = ref(true)
const activeTask = ref(null)
const batchTasks = ref([])
// 列表是否被服务端截断（true = 还有更早的任务没返回）；上限条数由后端下发，别在前端写死
// ⚠️ 名字不能叫 batchLimit —— 那个已被「本次只处理前 N 条」的输入框占用（语义完全不同）
const taskListTruncated = ref(false)
const taskListLimit = ref(50)
let pollTimer = null
/**
 * 进度轮询间隔（毫秒）。
 *
 * 原先 10 秒 —— 而单条抽取只要几百毫秒，10 秒足够推进十几条，界面却停在上一次的数字上，
 * 长任务看起来就像卡死。进度接口是按 id 取单条，很轻，2 秒一次不构成压力；
 * 页面不可见时整体暂停（见 startPoll），不产生后台请求。
 */
const POLL_INTERVAL_MS = 2000

/**
 * 轮询节拍计数，用来让「进度」与「任务列表」以不同频率刷新（P2-17）。
 *
 * 进度必须跟得上任务推进，而任务列表只是「最近提交过什么」的旁栏 ——
 * 每次都要整表取回最多 50 条，跟着进度一起刷纯属浪费（每 15 拍 = 30 秒一次）。
 * 这里不另起一个定时器：暂停（页面不可见）、切页签、卸载三处都要各自启停一份，
 * 两个定时器就是两套容易漏管的生命周期；同一个节拍器按次数分流更不容易出错。
 */
const LIST_REFRESH_EVERY_TICKS = 15
let pollTick = 0

/** 进度文案里的速度/剩余时间（见 updateEta）；空串表示还估不出来 */
const etaText = ref('')
/** 上一次进度采样，用来算速度。只存最近一次，不需要历史序列 */
let lastSample = null

const ACTIVE_STATUS = ['QUEUED', 'RUNNING']
// 任务是否仍在跑（排队 / 进行中）：决定是否显示取消按钮、是否继续轮询
const isActive = (t) => !!t && ACTIVE_STATUS.includes(t.status)

/**
 * 进度条的两态配置（统一口径见 Governance.vue 的清洗进度条）。
 *
 * 排队中（total 还没算出来）、或一条都还没处理完时，percentage 会算成 0 ⇒
 * 填充宽度就是 0（el-progress 的填充宽度恒等于 percentage%，见 Element Plus
 * progress.vue 的 barStyle），条子静止且什么都看不见。这段时间改用不确定流动条
 * 只表达「在推进」，不显示百分比数字，避免暗示一个并不存在的精确进度。
 *
 * 有可信数字后切回真实百分比，条纹继续流动表示仍在处理；
 * 终态任务（从任务列表回看历史任务）不加任何动画 —— 已经结束的条不该还在动。
 */
const progressBar = computed(() => {
  const t = activeTask.value
  const active = isActive(t)
  const done = (t && t.done) || 0
  // 1. 仍在跑但还没有任何推进 ⇒ 没有可信数字，走不确定态
  if (active && !done) {
    // percentage 必须给 100：不确定动画是在「100% 宽」的填充上左右滑动，给 0 就看不见
    return { percentage: 100, indeterminate: true, striped: false, stripedFlow: false, showText: false }
  }
  // 2. 有数字（或任务已结束）⇒ 真实百分比；只有仍在跑时才让条纹流动
  return {
    percentage: t && t.total ? Math.min(100, Math.round((done / t.total) * 100)) : 0,
    indeterminate: false,
    striped: active,
    stripedFlow: active,
    showText: true
  }
})

// 状态文案：一句一个事实，精确到数字/原因
const statusText = (t) => {
  if (!t) return ''
  switch (t.status) {
    case 'QUEUED': return '排队中（等待工作线程）'
    case 'RUNNING': return `进行中 ${t.done}/${t.total} · 成功 ${t.success} 失败 ${t.failed}`
    case 'COMPLETED': return `已完成：成功 ${t.success}，失败 ${t.failed}`
    case 'CANCELLED': return `已取消（已处理 ${t.done}，剩余未处理）`
    // 25.5：INTERRUPTED 有两个来源，不能一律归因「服务重启」——
    // 后端 nothingDone（total>0 却 0 处理）也落这个状态，那是分流/明细读取异常，
    // 是一起真事故（500 条筛选任务 0 处理），说成「重启」会把用户引到错误方向。
    case 'INTERRUPTED': return t.total > 0 && (t.done || 0) === 0
      ? '异常中断（一条都没处理，请重跑；若重复出现请联系管理员）'
      : '已中断（服务重启），可重跑'
    case 'FAILED': return '失败'
    default: return t.status
  }
}

// 拉取最近任务列表；失败静默（拦截器已提示），不阻塞当前进度展示
// 返回的是 { tasks, truncated, limit }：truncated 为真说明还有更早的任务没返回，
// 页面必须说出来 —— 原先标题写死「最多显示最近 50 条」，不足 50 条时那句话本身就是错的
const loadBatchList = async () => {
  try {
    const res = await listNlpBatch()
    batchTasks.value = res.data?.tasks || []
    taskListTruncated.value = res.data?.truncated === true
    taskListLimit.value = res.data?.limit || 50
  } catch {
    // 拦截器已提示
  }
}

// 停掉轮询定时器并清空句柄，防止重复启动或组件卸载后继续发请求
const stopPoll = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/**
 * 按两次采样的差值估算速度与剩余时间。
 *
 * 只在 done 真的前进过时才更新：轮询失败、任务卡住、或刚提交（done 还是 0）时
 * 都估不出速度，这时宁可什么都不显示，也不要给一个凭空的「剩余 X 分钟」。
 */
const updateEta = (t) => {
  if (!isActive(t) || !t.total) {
    etaText.value = ''
    lastSample = null
    return
  }
  const now = Date.now()
  // 换了任务（点「查看」切到另一条）不能沿用上一条的采样，否则会算出一个假速度
  if (lastSample && lastSample.id === t.id && t.done > lastSample.done && now > lastSample.ts) {
    const speed = ((t.done - lastSample.done) * 1000) / (now - lastSample.ts)
    if (speed > 0) {
      const remain = Math.max(0, t.total - t.done) / speed
      etaText.value = `约 ${speed.toFixed(1)} 条/秒 · 预计剩余 ${fmtDuration(remain)}`
    }
  }
  lastSample = { id: t.id, done: t.done, ts: now }
}

/** 秒 → 人话时长：不足 1 分钟只报秒，超过 1 小时报「小时 + 分」 */
const fmtDuration = (sec) => {
  if (!Number.isFinite(sec) || sec <= 0) return '不到 1 秒'
  const total = Math.round(sec)
  if (total < 60) return `${total} 秒`
  const m = Math.floor(total / 60)
  if (m < 60) return `${m} 分 ${total % 60} 秒`
  return `${Math.floor(m / 60)} 小时 ${m % 60} 分`
}

// 单次轮询：按当前任务 id 取最新进度；任务已结束或请求失败即停止轮询
const poll = async () => {
  // 1. 取当前任务 id，没有则无需轮询
  const id = activeTask.value?.id
  if (!id) return
  try {
    // 2. 取最新进度
    const res = await getNlpBatchProgress(id)
    // 3. 回包竞态守卫：await 期间用户可能切到了别的任务（viewTask），或本次请求超过一个节拍 ——
    //    旧响应若直接回填，会把界面拉回上一个任务、或让进度倒退。id 对不上就丢弃这次响应。
    if (activeTask.value?.id !== id) return
    activeTask.value = res.data
    updateEta(activeTask.value)
    // 4. 任务已结束（非排队 / 进行中）则停止轮询，并立刻刷新一次任务列表 ——
    //    列表里的状态要马上与这里对上，不能等下一个 30 秒节拍
    if (!isActive(activeTask.value)) {
      stopPoll()
      loadBatchList()
    }
  } catch {
    // 5. 请求失败也停止轮询，避免空转；但若失败的是「已切走的旧任务」，不能连累当前任务的轮询
    if (activeTask.value?.id === id) stopPoll()
  }
}

// 启动轮询（先停旧定时器）：每 POLL_INTERVAL_MS 刷新任务详情；
// 任务列表每 LIST_REFRESH_EVERY_TICKS 个节拍（30 秒）刷一次。
const startPoll = () => {
  stopPoll()
  // 每次重新开始都让第一个节拍就刷新列表：用户刚提交/刚点刷新，列表必须马上是新的
  pollTick = 0
  pollTimer = setInterval(() => {
    if (document.visibilityState !== 'visible') {
      // P4.8：页面不可见时暂停轮询（省请求），回可见后再前进度
      return
    }
    poll()
    // 第 1 拍刷一次（启动后立刻可见），之后每 LIST_REFRESH_EVERY_TICKS 拍一次
    pollTick += 1
    if (pollTick % LIST_REFRESH_EVERY_TICKS === 1) loadBatchList()
  }, POLL_INTERVAL_MS)
}

// 切走「批量解析」页签就停轮询。
// 原来只在 onBeforeUnmount 里停，于是「提交批量 → 切到单条解析」之后，
// 定时器还在每 10 秒打一次进度与任务列表：单条页签根本不显示这些数据，
// 白白发请求，还会让用户以为页面「在忙」。
watch(() => props.active, (on) => {
  if (!on) stopPoll()
})

/**
 * 当前批量范围的可读描述 + 条数。
 *
 * 「开始批量解析」与行内「按当前范围重新提交」是同一个动作、两个入口，
 * 原来都没有二次确认，而空范围等于全库（约 90 秒、覆盖已有结构化数据）。
 */
const describeBatchScope = async (isNormalize) => {
  // 1. 把已设的筛选条件拼成一句可读范围
  const parts = []
  if (batchFilters.department) parts.push(`科室＝${batchFilters.department}`)
  if (batchFilters.dateRange && batchFilters.dateRange.length === 2) {
    parts.push(`${batchFilters.dateRange[0]}~${batchFilters.dateRange[1]}`)
  }
  if (batchFilters.pattern) parts.push(`证候＝${batchFilters.pattern}`)
  if (batchFilters.grade) parts.push(`分级＝${batchFilters.grade}`)
  // 2. 查该范围条数（未解析过滤只对解析生效，口径与提交一致），取不到退化为「未知」
  let count = '未知'
  try {
    const countPayload = { ...batchFilters }
    if (!isNormalize && onlyUnparsed.value) countPayload.onlyUnparsed = true
    const res = await countRecords(countPayload)
    count = res.data ?? '未知'
  } catch {
    // 取不到条数不阻塞确认，退化成「未知」
  }
  // 3. 返回范围描述与条数，供提交前二次确认
  return { scope: parts.length ? parts.join(' · ') : '全部病历（未设筛选）', count }
}

// 提交批量解析任务（实体抽取 + 归一）
const submitBatch = () => submitBatchTask('EXTRACT')

// 提交「仅归一」任务：不重新抽取实体，只对已有结构化数据重跑归一（补词典后让新词生效）
const submitNormalizeOnly = () => submitBatchTask('NORMALIZE_ONLY')

// 提交批量任务：先算出范围描述与条数做二次确认（空范围等于全库，代价大），确认后提交并开始轮询
const submitBatchTask = async (mode) => {
  const isNormalize = mode === 'NORMALIZE_ONLY'
  const actionName = isNormalize ? '批量归一' : '批量解析'
  const skipParsed = !isNormalize && onlyUnparsed.value
  // 1. 先算出当前范围的可读描述与条数
  const { scope, count } = await describeBatchScope(isNormalize)
  const limited = batchMode.value === 'limit' ? `（本次只处理前 ${batchLimit.value} 条）` : ''
  // 2. 二次确认：空范围等于全库，代价大
  const detail = isNormalize
    ? '这是仅归一：不重新抽取实体，只对已有结构化数据重跑词典归一，耗时较短；尚无结构化数据的病历会被记为失败。'
    : (skipParsed ? '只解析尚未解析的病历（已有结构化数据的会跳过）。' : '耗时较长，且会覆盖这些病历已有的结构化数据。')
  const ok = await confirmBox(
    `将对「${scope}」范围内约 ${count} 条病历执行${actionName}${limited}。${detail}确定提交？`,
    actionName
  )
  // 3. 用户取消则直接返回，不提交
  if (!ok) {
    return
  }
  // 4. 进入提交态，防重复点击
  submitting.value = true
  try {
    // 5. 提交异步任务并提示计划条数
    //    批次5：带幂等键。用户的一次「开始」= 一个键；网关/代理重放同一个请求时会沿用同一个键，
    //    服务端据此返回同一条任务，不会又建一条。用户再主动点一次属于新意图，故本函数每次执行都新生成。
    const filters = { ...batchFilters }
    if (skipParsed) filters.onlyUnparsed = true
    const payload = {
      filters,
      limit: batchMode.value === 'limit' ? batchLimit.value : 0,
      requestKey: crypto.randomUUID()
    }
    const res = isNormalize
      ? await submitNlpNormalizeBatch(payload)
      : await submitNlpBatch(payload)
    ElMessage.success(`已提交，计划 ${res.data.total} 条`)
    // 6. 把新任务设为当前任务并开始轮询进度
    activeTask.value = res.data
    loadBatchList()
    startPoll()
  } catch {
    // 拦截器已提示（未开启抽取 / 无权限 / 服务异常）
  } finally {
    // 7. 无论成败都收掉提交态
    submitting.value = false
  }
}

// 取消指定任务：确认后调接口；若取消的正是当前任务则停止轮询并刷新其状态，已处理的条目不回滚
const cancelBatch = async (id) => {
  // 1. 二次确认：已处理的条目不回滚
  if (!(await confirmBox('确定取消该批量解析任务吗？已处理的不回滚。', '取消任务'))) {
    // 2. 取消则直接返回，不发请求
    return
  }
  try {
    // 3. 调接口取消任务
    const res = await cancelNlpBatch(id)
    // 4. 取消的正是当前任务：回填响应状态。
    //    只有落在终态才停轮询 —— 「刚被 worker 认领」时取消接口返回的是 RUNNING
    //    （取消位已置，worker 会在条/页边界自行收尾），此时停轮询会让顶部卡片
    //    永远停在「进行中」，而列表里已经是「已取消」，两处对不上。
    if (activeTask.value?.id === id) {
      activeTask.value = res.data
      if (!isActive(res.data)) {
        stopPoll()
      } else if (!pollTimer) {
        // 取消的是刚被认领的任务：轮询可能已经停过，接回去等它收敛到终态
        startPoll()
      }
    }
    // 5. 刷新最近任务列表
    loadBatchList()
  } catch {
    // 拦截器已提示
  }
}

// 查看历史任务详情：载入后立即启动轮询，让进度自刷新（否则只赋值、页面不会更新）
const viewTask = async (id) => {
  try {
    // 1. 拉取该历史任务的详情
    const res = await getNlpBatchProgress(id)
    // 2. 设为当前任务，供任务卡与进度展示
    activeTask.value = res.data
    // 原来只赋值、不启动轮询，进度不会自刷新
    startPoll()
  } catch {
    // 拦截器已提示
  }
}

// 病历详情弹窗（失败行「查看」用）
const detailVisible = ref(false)
const detailRecord = ref(null)

// 打开失败病历详情：按 ID 取原始病历（含结构化数据），拿不到就不打开
const openRecordDetail = async (recordId) => {
  try {
    const res = await getRawRecord(recordId)
    detailRecord.value = res?.data ?? null
    if (detailRecord.value) {
      detailVisible.value = true
    }
  } catch {
    // 拦截器已提示（病历可能已被删除）
  }
}

// 仅重试失败：对原任务失败那批记录重新提交（沿用原任务模式），随后轮询新任务
const retryFailed = async (id) => {
  if (!(await confirmBox('将对本次失败的那批病历重新提交任务（不重跑已成功的），确定？', '仅重试失败'))) {
    return
  }
  submitting.value = true
  try {
    const res = await retryNlpBatchFailed(id)
    ElMessage.success(`已提交，计划重试 ${res.data.total} 条`)
    activeTask.value = res.data
    loadBatchList()
    startPoll()
  } catch {
    // 拦截器已提示（无失败记录 / 已有任务在跑）
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadBatchList()
})

onBeforeUnmount(stopPoll)
</script>

<style scoped>
.tip { line-height: 1.7; }
.actions { margin-top: var(--sp-3); display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.batch-filter {
  margin: 14px 0 var(--sp-1);
  padding: var(--sp-3) var(--sp-4);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.bf-title { font-size: var(--fs-xs); color: var(--text-sub-strong); margin-bottom: var(--sp-2); }
.batch-row { display: flex; align-items: center; gap: 10px; margin: 14px 0 var(--sp-1); }
.batch-progress {
  margin-top: 14px;
  padding: var(--sp-3) var(--sp-4);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.bp-hd { font-size: var(--fs-xs); color: var(--text); margin-bottom: var(--sp-2); }
.bp-hd b { color: var(--ink); }
.bp-sub { margin-top: var(--sp-2); font-size: var(--fs-xs); color: var(--text-sub-strong); }
.batch-failures { margin-top: 14px; border-top: 1px dashed var(--line-soft); padding-top: var(--sp-3); }
.batch-list { margin-top: var(--sp-4); border-top: 1px dashed var(--line-soft); padding-top: var(--sp-3); }
.bf-hd { font-size: var(--fs-xs); color: var(--text-sub-strong); margin-bottom: var(--sp-2); }
/* 截断提示：用的是次要色而不是警示色 —— 列表被截断是正常上限行为，不是错误 */
.bf-trunc { margin-left: var(--sp-2); color: var(--ochre-text); }
.bf-muted { color: var(--text-sub-strong); }
</style>
