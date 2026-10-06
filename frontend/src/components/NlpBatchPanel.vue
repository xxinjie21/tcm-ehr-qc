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

    <div class="actions">
      <el-button type="primary" :loading="submitting" @click="submitBatch">开始批量解析</el-button>
      <el-button :disabled="submitting" @click="loadBatchList">刷新任务列表</el-button>
    </div>

    <!-- 当前 / 选中任务 -->
    <div v-if="activeTask" class="batch-progress">
      <div class="bp-hd"><b>{{ statusText(activeTask) }}</b></div>
      <el-progress
        :percentage="activeTask.total ? Math.round((activeTask.done / activeTask.total) * 100) : 0"
        :stroke-width="10"
      />
      <div class="bp-sub">
        共 {{ activeTask.total }} 条 · 已处理 {{ activeTask.done }} · 成功 {{ activeTask.success }} · 失败 {{ activeTask.failed }}
        <span v-if="activeTask.current"> · 当前 {{ activeTask.current }}</span>
      </div>
      <div v-if="isActive(activeTask)" class="actions">
        <el-button size="small" @click="cancelBatch(activeTask.id)">取消任务</el-button>
      </div>

      <div v-if="activeTask.failures && activeTask.failures.length" class="batch-failures">
        <div class="bf-hd">
          失败清单（{{ activeTask.failures.length }} 条{{ activeTask.failureTruncated ? '，仅显示前 500 条' : '' }}）
        </div>
        <el-table :data="activeTask.failures" border size="small" max-height="240">
          <el-table-column prop="label" label="病历" width="200" show-overflow-tooltip />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
        </el-table>
      </div>
    </div>

    <!-- 最近任务 -->
    <div v-if="batchTasks.length" class="batch-list">
      <div class="bf-hd">
        最近任务（最多 {{ taskListLimit }} 条）<span v-if="taskListTruncated" class="bf-trunc">已省略更早的任务</span>
        <!-- 批次4：长任务要能一眼找到「哪些批次出过错」，不必逐条看状态 -->
        <el-checkbox v-model="onlyFailedTasks" size="small" style="margin-left: var(--sp-3)">只看有失败的</el-checkbox>
      </div>
      <el-table :data="onlyFailedTasks ? batchTasks.filter((t) => t.failed > 0) : batchTasks" border size="small" max-height="260">
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ fmtDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="240">
          <template #default="{ row }">{{ statusText(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button v-if="isActive(row)" link type="danger" @click="cancelBatch(row.id)">取消</el-button>
            <template v-else>
              <el-button link type="primary" @click="viewTask(row.id)">查看</el-button>
              <!-- 与「开始批量解析」同一条路径：按当前表单范围重新提交，不是重跑那一条任务 -->
              <el-button link type="primary" @click="submitBatch">按当前范围重新提交</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </PanelCard>
</template>

<script setup>
// 批量结构化解析面板（从 NlpExtract.vue 页内抽出，28.16）：
// 提交后台批量任务、轮询进度、列出最近任务。页签用 lazy 挂载，
// 因此组件挂载即首屏加载任务列表，切走时由 active 属性停掉轮询。
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { submitNlpBatch, getNlpBatchProgress, cancelNlpBatch, listNlpBatch } from '@/api/nlp'
import { searchRecords } from '@/api/records'
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
const activeTask = ref(null)
const batchTasks = ref([])
// 列表是否被服务端截断（true = 还有更早的任务没返回）；上限条数由后端下发，别在前端写死
// ⚠️ 名字不能叫 batchLimit —— 那个已被「本次只处理前 N 条」的输入框占用（语义完全不同）
const taskListTruncated = ref(false)
const taskListLimit = ref(50)
let pollTimer = null

const ACTIVE_STATUS = ['QUEUED', 'RUNNING']
// 任务是否仍在跑（排队 / 进行中）：决定是否显示取消按钮、是否继续轮询
const isActive = (t) => !!t && ACTIVE_STATUS.includes(t.status)

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

// 单次轮询：按当前任务 id 取最新进度；任务已结束或请求失败即停止轮询
const poll = async () => {
  // 1. 取当前任务 id，没有则无需轮询
  const id = activeTask.value?.id
  if (!id) return
  try {
    // 2. 取最新进度并回填当前任务
    const res = await getNlpBatchProgress(id)
    activeTask.value = res.data
    // 3. 任务已结束（非排队 / 进行中）则停止轮询
    if (!isActive(activeTask.value)) stopPoll()
  } catch {
    // 4. 请求失败也停止轮询，避免空转
    stopPoll()
  }
}

// 启动轮询（先停旧定时器）：每 2 秒刷新任务详情与最近任务列表，供提交后与查看任务共用
const startPoll = () => {
  stopPoll()
  pollTimer = setInterval(() => {
    if (document.visibilityState !== 'visible') {
      // P4.8：页面不可见时暂停轮询（省请求），回可见后再前进度
      return
    }
    poll()
    loadBatchList()
  }, 10000)
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
const describeBatchScope = async () => {
  // 1. 把已设的筛选条件拼成一句可读范围
  const parts = []
  if (batchFilters.department) parts.push(`科室＝${batchFilters.department}`)
  if (batchFilters.dateRange && batchFilters.dateRange.length === 2) {
    parts.push(`${batchFilters.dateRange[0]}~${batchFilters.dateRange[1]}`)
  }
  if (batchFilters.pattern) parts.push(`证候＝${batchFilters.pattern}`)
  if (batchFilters.grade) parts.push(`分级＝${batchFilters.grade}`)
  // 2. 查该范围条数，取不到就退化成「未知」，不阻塞确认
  let count = '未知'
  try {
    const res = await searchRecords({ ...batchFilters, page: 1, pageSize: 1 })
    count = res.data?.total ?? '未知'
  } catch {
    // 取不到条数不阻塞确认，退化成「未知」
  }
  // 3. 返回范围描述与条数，供提交前二次确认
  return { scope: parts.length ? parts.join(' · ') : '全部病历（未设筛选）', count }
}

// 提交批量任务：先算出范围描述与条数做二次确认（空范围等于全库，代价大），确认后提交并开始轮询
const submitBatch = async () => {
  // 1. 先算出当前范围的可读描述与条数
  const { scope, count } = await describeBatchScope()
  const limited = batchMode.value === 'limit' ? `（本次只处理前 ${batchLimit.value} 条）` : ''
  // 2. 二次确认：空范围等于全库，代价大且会覆盖已有数据
  const ok = await confirmBox(
    `将对「${scope}」范围内约 ${count} 条病历执行批量解析${limited}，耗时较长，`
      + '且会覆盖这些病历已有的结构化数据。确定提交？',
    '批量解析'
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
    const res = await submitNlpBatch({
      filters: { ...batchFilters },
      limit: batchMode.value === 'limit' ? batchLimit.value : 0,
      requestKey: crypto.randomUUID()
    })
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
    // 4. 取消的正是当前任务则停止轮询并刷新其状态
    if (activeTask.value?.id === id) {
      activeTask.value = res.data
      stopPoll()
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
  padding: var(--sp-3) 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.bf-title { font-size: var(--fs-md); color: var(--text-sub); margin-bottom: var(--sp-2); }
.batch-row { display: flex; align-items: center; gap: 10px; margin: 14px 0 var(--sp-1); }
.batch-progress {
  margin-top: 14px;
  padding: 10px 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.bp-hd { font-size: var(--fs-md); color: var(--text); margin-bottom: var(--sp-2); }
.bp-hd b { color: var(--ink); }
.bp-sub { margin-top: var(--sp-2); font-size: var(--fs-sm); color: var(--text-sub); }
.batch-failures { margin-top: 14px; border-top: 1px dashed var(--line-soft); padding-top: var(--sp-3); }
.batch-list { margin-top: var(--sp-4); border-top: 1px dashed var(--line-soft); padding-top: var(--sp-3); }
.bf-hd { font-size: var(--fs-md); color: var(--text-sub); margin-bottom: var(--sp-2); }
/* 截断提示：用的是次要色而不是警示色 —— 列表被截断是正常上限行为，不是错误 */
.bf-trunc { margin-left: var(--sp-2); color: var(--ochre); }
</style>
