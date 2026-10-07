<template>
  <!-- 质控页：范围查询（整页口径）+ 评分标准 + 规则配置（三档授权）+ 扣分构成 + AI 预检列表 + 扣分明细弹窗 -->
  <div>
    <!-- 规则配置（管理员 / 所有者 / 被授权成员）：句子清单 + 就地编辑，保存即生效 -->
    <QcRulesDialog
      v-model="rulesVisible"
      :rules="rules"
      :catalog-elements="catalogElements"
      :catalog-formats="catalogFormats"
      @saved="applyRules"
    />

    <!-- 本范围扣分构成：范围内各病历扣分明细聚合。
         顶部工具条 = 原独立的「范围查询」PanelCard（审查报告 L2）：那张卡只有 137px 高、
         里面仅一行筛选，八成面积是空的。去掉卡壳降级为工具条后，筛选与它实际驱动的
         两块数据在视觉上更近，也省掉一整张卡的卡头与内边距。
         整页口径的语义不变：一次「查询」同时刷新扣分构成与 AI 预检列表
         （此前 RangeFilter 只喂图谱，而「AI 预检列表」另挂独立分级下拉，同一页两个口径）。 -->
    <div class="grid-2">
      <PanelCard title="本范围扣分构成">
        <!-- 工具条：下边框与正文分隔，避免筛选与统计内容糊在一起 -->
        <div class="panel-toolbar">
          <div class="filter-bar">
            <RangeFilter v-model="filters" />
            <!-- F7：与同排 RangeFilter 的 32px 输入框等高，按钮改 default 档；
                 同排的重算 / 取消重算一并改，否则底对齐后会出现 24px 与 32px 混排 -->
            <el-button type="primary" size="default" :loading="queryLoading" @click="applyFilters">查 询</el-button>
            <el-button size="default" :disabled="queryLoading" @click="resetFilters">重置</el-button>
            <!-- 重算是异步任务（§七 L5）：提交后按钮立刻解锁，进度单独显示，别让按钮一直转圈 -->
            <el-button
              type="warning"
              size="default"
              :loading="recomputing"
              :disabled="isActiveTask(recomputeProgress)"
              @click="handleRecompute"
            >
              {{ recomputeButtonText }}
            </el-button>
            <!-- 28.3：cancelQcBatch 后端早已就绪，前端却一直没入口 ——
                 一旦提交就只剩等，想停只能刷新页面（任务仍在后台跑）。
                 只在运行中出现，避免常态多一个永远点不到的按钮 -->
            <el-button
              v-if="isActiveTask(recomputeProgress)"
              size="default"
              :loading="cancelling"
              @click="handleCancelRecompute"
            >
              取消重算
            </el-button>
            <span class="tip">范围对本页各块同时生效；「质控评分计算」按当前范围重算评分与分级</span>
          </div>
          <!-- I7：批量重算此前只有按钮文案（「重算中 1234/40000…」），看不出推进快慢。
               数据早已在 recomputeProgress 里，补一条进度条：只在任务运行时渲染，
               终态由 v-if 自动移除，不留占位 -->
          <el-progress
            v-if="isActiveTask(recomputeProgress)"
            class="recompute-progress"
            :percentage="recomputePercent"
            :stroke-width="10"
          />
        </div>
        <div v-loading="dedLoading" element-loading-text="正在统计扣分分布…">
          <template v-if="dedStats">
            <!-- 按扣分类型聚合：横条长度按最大扣分点数归一（见 barWidth） -->
            <div v-if="dedStats.byType.length" class="dist">
              <div v-for="t in dedStats.byType" :key="t.type" class="dist-row">
                <span class="dr-l">{{ t.type }}</span>
                <div class="dr-bar"><i :style="{ width: barWidth(t.points) }"></i></div>
                <span class="dr-v">{{ t.count }} 份 · -{{ t.points }}</span>
              </div>
            </div>
            <div v-else class="ok">本范围内没有扣分项（全部病历未触发任何扣分规则）</div>

            <!-- 扣分项明细：受影响病历数 + 合计扣分，用于定位主要扣分来源 -->
            <template v-if="dedStats.byItem.length">
              <div class="sub-hd">Top 扣分项</div>
              <el-table :data="dedStats.byItem" border size="small" max-height="240">
                <el-table-column prop="type" label="类型" width="130" />
                <el-table-column prop="item" label="项" width="110" />
                <el-table-column prop="count" label="受影响病历数" width="110" />
                <el-table-column prop="points" label="合计扣分" width="100" />
              </el-table>
              <!-- P5.2：明细被截断时告知，避免「为什么只看到 20 条」的隔屏疑问 -->
              <div v-if="dedStats.itemsTruncated" class="trunc-hint">扣分项较多，仅展示扣分最高的 20 项</div>
            </template>

            <!-- 分级分布；扫描份数可能被上限截断，截断时下方另有提示 -->
            <div class="sub-hd">分级分布（扫描 {{ dedStats.scanned }} 份）</div>
            <div class="grade-chips">
              <span v-for="(v, k) in dedStats.gradeDist" :key="k" class="gc">{{ k }} {{ v }}</span>
            </div>
            <div v-if="dedStats.truncated" class="trunc-hint">超出扫描上限，仅统计前 {{ dedStats.scanned }} 份</div>
          </template>
          <EmptyState v-else-if="!dedLoading" text="当前范围暂无可统计的评分结果" :image-size="70" />
        </div>
      </PanelCard>

      <!-- AI 预检列表：与「病历数据」共用同一套 searchRecords 查询，扣分范围沿用上方筛选。
           与左侧「本范围扣分构成」并排（grid-2）：原来 4 张卡纵向堆到 1235px，
           第 4 块整块在屏外，先用并排把上半页收成一行 -->
      <PanelCard title="AI 预检列表 / 扣分明细">
        <!-- M20：原「点击行查看…」提示条独占一行（约 40px）。并进卡头后，
             首条扣分明细在 1366×768 下从 y=776 提前到屏内（标题行本来就有 48px 高） -->
        <template #header>
          <span>AI 预检列表 / 扣分明细</span>
          <span class="tip" style="font-weight: normal">点击行查看规则扣分明细；扣分范围沿用上方「范围查询」</span>
        </template>

        <!-- 并排后本表只有半幅宽（约 660px），固定列合计 716px 必然横向滚动 ——
             theme.css 已放开 EP 横向滚动条，滚动是可达的；操作列仍 fixed 在右侧。
             max-height 360 → 420：半幅宽下行数不变但表更窄，略增高度让右栏
             与左侧统计栏的底边基本齐平，不至于一高一矮 -->
        <RecordTable
            ref="precheckTableRef"
            :rows="precheckRows"
            :loading="precheckLoading"
            loading-text="正在预检待复核项…"
            :max-height="420"
            :action-width="100"
            :row-class-name="precheckRowClass"
          >
            <template #action="{ row }">
              <el-button link type="primary" @click="openDetail(row.id)">扣分明细</el-button>
            </template>
            <!-- 文案与「病历数据」「结构化解析」两页统一：这张表就是同一份 searchRecords 查询 -->
            <template #empty>
              <EmptyState :failed="precheckFailed" :loading="precheckLoading"
                text="筛选范围内没有病历" @retry="() => loadPrecheck(1)" />
            </template>
          </RecordTable>
        <!-- 分页：切换每页条数时回到第 1 页（见 handleSizeChange） -->
        <el-pagination
          v-model:current-page="precheckPage"
          v-model:page-size="precheckSize"
          :page-sizes="PAGE_SIZES_STANDARD"
          :total="precheckTotal"
          layout="total, sizes, prev, pager, next, jumper"
          style="margin-top: var(--sp-3); justify-content: flex-end"
          @current-change="loadPrecheck"
          @size-change="handleSizeChange"
        />
      </PanelCard>
    </div>

    <!-- 质控评分标准（M20 复测 2026-10-06：低频阅读内容 → 移到结果区之后并默认收起）。
         此前它占首屏 332px（y282~614），把承载结论的「AI 预检列表 / 扣分明细」推到 y≈989、
         首条扣分 y≈1095（1366×768 视口只有 716px）。收起时摘要行保留关键口径，
         规则配置入口仍在卡头，不因收起而找不到。 -->
    <PanelCard title="质控评分标准">
      <template #header>
        <span>质控评分标准</span>
        <!-- 无写权限时不隐藏按钮，而是禁用并常驻写明原因：
             藏起来用户只会以为「这页没有这个功能」，永远不知道是权限问题 -->
        <el-button link type="primary" class="hd-action" :disabled="!canWriteRules" @click="openRules">规则配置</el-button>
        <span v-if="!canWriteRules" class="tip">需管理员、组织所有者或被授权成员才能改规则</span>
      </template>
      <details class="std-panel">
        <summary class="std-sum">
          <span v-if="rules">合格 ≥ {{ rules.thresholds.qualified }} 分 · 无效 &lt; {{ rules.thresholds.invalid }} 分或真缺失 ≥ {{ rules.thresholds.seriousFullMissing }} · 病历应包含 {{ rules.completeness.elements.length }} 项</span>
          <span v-else>标准加载中…</span>
          <span class="tip">展开查看完整评分口径</span>
        </summary>
      <div v-if="rules" class="std-grid">
        <div class="st">
          <span class="st-k">病历应包含</span>
          <span class="st-v">
            <span v-for="e in rules.completeness.elements" :key="e.name" class="chip">{{ e.name }}</span>
          </span>
        </div>
        <div class="st">
          <span class="st-k">缺失扣分</span>
          <span class="st-v">完全缺失 -{{ rules.completeness.elements[0]?.weightFull ?? 12 }} ／ 未结构化 -{{ rules.completeness.elements[0]?.weightPartial ?? 6 }}</span>
        </div>
        <div class="st">
          <span class="st-k">格式</span>
          <span class="st-v">{{ rules.format.map((f) => f.label || f.field).join('、') || '未配置' }}</span>
        </div>
        <div class="st">
          <span class="st-k">一致性</span>
          <span class="st-v">{{ rules.consistencySummary || '—' }}</span>
        </div>
        <div class="st">
          <span class="st-k">术语标准化</span>
          <span class="st-v">{{ rules.standardization.enabled
            ? ('开 · 未命中词典的每个 -' + rules.standardization.weightEach
               + '；超过 ' + rules.standardization.cap + ' 个后，每再满 '
               + rules.standardization.cap + ' 个追加一档（分段扣分，避免大量未归一时触顶、分数失去区分度）')
            : '已关闭' }}</span>
        </div>
        <div class="st">
          <span class="st-k">重复</span>
          <span class="st-v">-{{ rules.duplicateWeight }}</span>
        </div>
        <div class="st">
          <span class="st-k">分级</span>
          <span class="st-v">合格 ≥{{ rules.thresholds.qualified }} ／ 无效 &lt;{{ rules.thresholds.invalid }} 或真缺失 ≥{{ rules.thresholds.seriousFullMissing }}</span>
        </div>
      </div>
      <!-- 完整规则说明收进折叠区：默认只看摘要，需要细节时再展开 -->
      <el-collapse v-if="descriptions.length" class="std-detail">
        <el-collapse-item title="查看完整规则说明" name="d">
          <div v-for="(l, i) in descriptions" :key="i" class="std-desc">· {{ l }}</div>
        </el-collapse-item>
      </el-collapse>
      <div v-if="ruleWarnings.length" class="trunc-hint">规则告警：{{ ruleWarnings.join('；') }}</div>
      <EmptyState v-if="!rules" text="标准加载中…" :image-size="60" />
      </details>
    </PanelCard>

    <QcDeductionDialog
      v-model="detailVisible"
      :detail="detail"
      :structured-data="detailStructured"
      :qualified="qualified"
    />
  </div>
</template>

<script setup>
// 质控页：范围查询是整页口径 —— 一次「查询」同时刷新「扣分构成」与「AI 预检列表」两块。
// 有写权限者另有「规则配置」弹窗，保存后规则立即生效，无需重启后端。
import VisitTimeCell from '@/components/cells/VisitTimeCell.vue'
import AgeGenderCell from '@/components/cells/AgeGenderCell.vue'
import { reactive, ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmBox } from '@/utils/confirm'
import EmptyState from '@/components/EmptyState.vue'
import { usePagedList } from '@/composables/usePagedList'
import { useUrlFilters } from '@/composables/useUrlFilters'
import RecordTable from '@/components/RecordTable.vue'
import QcRulesDialog from '@/components/QcRulesDialog.vue'
import QcDeductionDialog from '@/components/QcDeductionDialog.vue'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { recomputeQc, getQcBatch, cancelQcBatch, qcScore, getQcRules, getDeductionStats } from '@/api/qc'
import { searchRecords, getRawRecord } from '@/api/records'
import { GRADE_OK } from '@/utils/grade'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'
import { PAGE_SIZES_STANDARD } from '@/utils/constants'

// 规则写入口按 admin / owner / 授权成员 三档判定（与后端 qc/rules 写接口一致）
const userStore = useUserStore()
const canWriteRules = computed(() => userStore.canWriteQcRulesEntry)

// 整页共用的筛选条件，由上方 RangeFilter 通过 v-model 维护
const filters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })

// ===== 评分标准 / 扣分构成=====
// 评分标准：rules 为当前生效规则，descriptions 为自然语言说明，catalog* 为可选项目录
const rules = ref(null)
const ruleWarnings = ref([])
const descriptions = ref([])
const catalogElements = ref([])
const catalogFormats = ref([])
const dedStats = ref(null)
const dedLoading = ref(false)

// 读取当前生效规则与说明文案
const loadRules = async () => {
  try {
    // 1. 拉取当前生效的规则、说明文案与可选项目录
    const res = await getQcRules()
    // 2. 回填规则本体与自然语言说明
    rules.value = res.data?.rules || null
    descriptions.value = res.data?.descriptions || []
    // 3. 回填要素/格式目录与规则告警（供规则配置弹窗使用）
    catalogElements.value = res.data?.catalogElements || []
    catalogFormats.value = res.data?.catalogFormats || []
    ruleWarnings.value = res.data?.warnings || []
  } catch {
    // 拦截器已提示
  }
}

// 标准里的合格线。规则未就绪时给 null（显示「—」），不再退回 90 ——
// 那个 90 是后端出厂默认值的拷贝，管理员改过合格线后，等待期里界面会按 90
// 算出「距合格线还差 N 分」，与服务端结论矛盾。
const qualified = computed(() => rules.value?.thresholds?.qualified ?? null)

// 拉取当前范围的扣分聚合（按类型、按项、分级分布）
const loadDedStats = async () => {
  // 1. 置加载态：本范围扣分构成整块进入 loading
  dedLoading.value = true
  try {
    // 2. 按当前范围拉取扣分聚合（按类型 / 按项 / 分级分布）
    const res = await getDeductionStats(params())
    // 3. 回填聚合结果，供横条图与明细表渲染
    dedStats.value = res.data
  } catch {
    // 拦截器已提示
  } finally {
    dedLoading.value = false
  }
}

// 最大扣分点数：原先在 barWidth 里每次调用都 Math.max 一遍，
// 而 barWidth 在模板里每行调一次 —— 渲染 N 行就是 N 次全量扫描。
const maxDeductPoints = computed(
  () => Math.max(1, ...(dedStats.value?.byType || []).map((t) => t.points))
)

// 条形宽度：按最大扣分点数归一
const barWidth = (points) => Math.round((points / maxDeductPoints.value) * 100) + '%' 

// 组装接口参数：dateRange 是 [起, 止] 两元素数组，缺任一个都视为未选
const params = () => {
  // 1. 取日期区间（RangeFilter 给出的是 [起, 止] 两元素数组）
  const d = filters.dateRange
  // 2. 组装接口参数：起止缺任一端都按未选处理，空串交给后端忽略
  return {
    department: filters.department || '',
    start: d && d.length === 2 ? d[0] : '',
    end: d && d.length === 2 ? d[1] : '',
    pattern: filters.pattern || '',
    grade: filters.grade || ''
  }
}

// ===== 规则配置（有写权限者）=====
// 弹窗本体抽为 components/QcRulesDialog.vue：本页只负责打开，以及保存后刷新标准摘要
const rulesVisible = ref(false)
// 打开规则配置：规则尚未加载时先补拉一次，保证弹窗有内容可编辑
const openRules = async () => {
  if (!rules.value) {
    await loadRules()
  }
  rulesVisible.value = true
}
// 保存 / 恢复默认成功后就地刷新标准与说明（无需重进页面）
const applyRules = (res) => {
  rules.value = res.data?.rules || rules.value
  descriptions.value = res.data?.descriptions || descriptions.value
  ruleWarnings.value = res.data?.warnings || []
}
// ===== 预检列表 / 扣分明细 =====
// 分级不再单独持有：统一由上方「范围查询」的 filters.grade 驱动，
// 否则同一页会出现两个互不相干的分级口径
// 预检列表状态
const precheckPage = ref(1)
const precheckSize = ref(10)

// 28.12：预检列表把「非合格」行整行高亮 —— 质控场景就是来找缺陷病历的，
// 逐行看分级标签不如整行底色来得快。分级名口径来自 utils/grade，不另立标准。
const precheckRowClass = ({ row }) => (row.grade && row.grade !== GRADE_OK ? 'qc-defect-row' : '')
// 预检列表加载失败：与「范围内确实没有病历」区分开（三态统一）

// 加载预检列表；传数字即跳到该页
// latest-wins：发起时取号，回来时号不是最新就整体丢弃 —— 快速连点翻页时慢的旧响应
// 不覆盖新结果，也不提前收掉 loading（范式同 components/TermInput.vue）
// 列表骨架统一走 usePagedList：失败保留已有行并标记失败（空态据此给重试入口）
const {
  list: precheckRows, total: precheckTotal, loading: precheckLoading,
  failed: precheckFailed, load: loadPrecheckList
} = usePagedList({
  fetcher: () => searchRecords({ ...filters, page: precheckPage.value, pageSize: precheckSize.value }),
  extract: (res) => ({ list: res.data?.records, total: res.data?.total }),
  clearOnFailure: false
})

// 对标 E5「可分享视图」：整页筛选 + 预检列表分页同步到 URL。
// filters 是整页共用条件（同时喂给批量提交与预检列表），precheckPage/Size 是预检列表分页 ——
// 正好是组合式期望的形状。在 setup 阶段同步还原，onMounted 的首次加载自动带上条件。
useUrlFilters(filters, precheckPage, precheckSize)

// 加载预检列表；传数字即跳到该页
const loadPrecheck = async (p) => {
  if (typeof p === 'number') precheckPage.value = p
  await loadPrecheckList()
}

// 每页条数变化回到第 1 页
const handleSizeChange = () => {
  precheckPage.value = 1
  loadPrecheck()
}

// 查询按钮 loading：两块数据任意一块在加载就转（范围查询是整页口径，刷新时各块必须一起走）
const queryLoading = computed(() => precheckLoading.value || dedLoading.value)
// 应用筛选：两块一起刷新（整页口径）
const applyFilters = () => {
  loadPrecheck(1)
  loadDedStats()
}
// 重置筛选并立即重新查询
const resetFilters = () => {
  filters.department = ''
  filters.dateRange = null
  filters.pattern = ''
  filters.grade = ''
  applyFilters()
}

// ===== 质控评分计算（§七 L5/L6：异步任务）=====
// 重算已从「同步等结果」改为「提交拿 taskId → 2s 轮询进度 → 终态提示分级汇总」。
// 同步跑 40000 条会把请求挂到超时，用户关页面任务也还在跑；异步后可以离开再回来。
const recomputing = ref(false)
// 取消请求在途：按钮进入 loading，避免连点发出第二次 cancel
const cancelling = ref(false)
// 当前任务进度：{ done, total, status, ... }，用于按钮上的进度文案
const recomputeProgress = ref(null)
// 轮询句柄；null 表示当前没有在轮询
let pollTimer = null

// 终态判定：不再变化的状态
const isActiveTask = (t) => !!t && (t.status === 'QUEUED' || t.status === 'RUNNING')

// 按钮文案：运行中显示进度，终态回到常态
const recomputeButtonText = computed(() => {
  const t = recomputeProgress.value
  if (!isActiveTask(t)) return '质控评分计算'
  return t.status === 'QUEUED' ? '重算排队中…' : `重算中 ${t.done} / ${t.total}…`
})

// 工具条进度条百分比（I7）：按钮文案只有「x / y」，看不出推进快慢。
// total 为 0（刚提交、分母还没算出来）时给 0，避免除零；上限 100 防后端回包越界
const recomputePercent = computed(() => {
  const t = recomputeProgress.value
  if (!t || !t.total) return 0
  return Math.min(100, Math.round((t.done / t.total) * 100))
})

// 停掉轮询并清空句柄，防止重复启动或组件卸载后继续发请求
const stopPoll = () => {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

// 进度轮询：2s 一次（与 NlpExtract 的批量解析同频率），终态自动停
const pollTask = async (id) => {
  // P4.8 同款（NlpExtract.vue:780）：页面不可见时不发请求，但仍把定时器续上 ——
  // 后台标签页每 2s 打一次接口纯属浪费；直接 return 会让轮询永久停摆，故重排而非丢弃
  if (document.visibilityState !== 'visible') {
    pollTimer = setTimeout(() => pollTask(id), 2000)
    return
  }
  try {
    const res = await getQcBatch(id)
    const t = res.data || {}
    recomputeProgress.value = t
    if (!isActiveTask(t)) {
      // 终态：提示分级汇总 + 刷新两块依赖评分的列表
      stopPoll()
      ElMessage.success(
        t.status === 'COMPLETED'
          ? `重算完成：合格 ${t.qualified}，待复核 ${t.pendingReview}，无效 ${t.invalid}，失败 ${t.failed}`
          : `重算${t.status === 'CANCELLED' ? '已取消' : t.status === 'INTERRUPTED' ? '被中断（服务重启，可重新提交）' : '失败'}：已处理 ${t.done} / ${t.total}`
      )
      loadPrecheck(1)
      loadDedStats()
      return
    }
    pollTimer = setTimeout(() => pollTask(id), 2000)
  } catch {
    // 轮询失败不重试：任务多半已被清理或服务异常，停止即可，用户可刷新页面看结果
    stopPoll()
  }
}

// 质控评分计算：二次确认后提交异步任务，轮询进度直到终态
const handleRecompute = async () => {
  if (!(await confirmBox(
      `将按质控规则重算当前筛选范围内 ${precheckTotal.value} 条病历的评分与分级（覆盖现有分数），确认？`,
      '质控评分计算',
      { type: 'warning', confirmButtonText: `确认重算 ${precheckTotal.value} 条`, cancelButtonText: '取消' }))) {
    return
  }
  // 2. 置重算态：按钮进入 loading，避免重复触发
  recomputing.value = true
  recomputeProgress.value = null
  stopPoll()
  try {
    // 3. 按当前范围提交重算（只拿 taskId，不等结果）
    //    批次5：带幂等键 —— 一次用户动作一个键；网关/代理重放同一请求时键相同，
    //    服务端会返回同一条任务，不会又建一条。用户再点一次是新意图，故每次执行都新生成。
    const res = await recomputeQc({ filters: { ...filters }, requestKey: crypto.randomUUID() })
    const t = res.data || {}
    recomputeProgress.value = t
    // 4. 空范围提交出来的是 total=0 的任务：直接当完成，不必轮询
    if (isActiveTask(t)) {
      pollTask(t.id)
    } else {
      ElMessage.success('重算完成：范围内没有需要重算的病历')
    }
  } catch {
    // 拦截器已提示（超上限 / 已有任务在跑）
    stopPoll()
  } finally {
    // 4. 按钮立刻解锁：进度由 recomputeProgress 单独表达，不该让按钮一直转圈
    recomputing.value = false
  }
}

// 取消重算：只发取消信号，不自己把状态改成 CANCELLED ——
// 终态以服务端轮询结果为准，避免本地先行乐观更新后与真实状态分叉。
const handleCancelRecompute = async () => {
  const t = recomputeProgress.value
  if (!isActiveTask(t)) return
  if (!(await confirmBox(
      '取消后已处理完的病历会保留结果，剩余病历不再重算。确认取消？',
      '取消重算',
      { type: 'warning', confirmButtonText: '取消重算', cancelButtonText: '继续计算' }))) {
    return
  }
  cancelling.value = true
  try {
    await cancelQcBatch(t.id)
    ElMessage.info('已请求取消，正在收敛…')
    // 轮询若已停（失败分支）则重新接上，否则等下一次 2s 轮询拿到 CANCELLED 终态
    if (!pollTimer) pollTask(t.id)
  } catch {
    // 拦截器已提示（任务已结束 / 无权取消）
  } finally {
    cancelling.value = false
  }
}

// 组件卸载时停掉轮询，避免离开页面后还在发请求
onBeforeUnmount(stopPoll)

// 扣分明细改回弹窗；关闭时只收起、不清数据，避免关闭动画期间内容闪空
const detail = ref(null)
const detailVisible = ref(false)
// 该病历的结构化数据（供弹窗列出「未命中标准词典」的具体词）。拿不到就保持 null，
// 弹窗退化为只显示原因行 —— 列表是增强，不能因为取数失败挡住弹窗本身。
const detailStructured = ref(null)

// 打开扣分明细：按病历 ID 单独取一次评分结果；评分与结构化数据并行拉取
const openDetail = async (recordId) => {
  detailStructured.value = null
  try {
    // 1. 按病历 ID 单独取一次评分结果
    const res = await qcScore({ recordId })
    // 2. 回填明细数据（分数 / 分级 / 扣分列表）
    detail.value = res.data
    // 3. 并行拉原始病历（含 structuredData），失败不阻断弹窗
    getRawRecord(recordId)
      .then((r) => {
        detailStructured.value = r?.data?.structuredData ?? null
      })
      .catch(() => {})
    // 4. 打开弹窗
    detailVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

// 只收起弹窗、不清 detail，避免关闭动画期间内容闪空

// 进页面：规则 + 预检列表 + 扣分构成并行拉取
onMounted(() => {
  loadRules()
  loadPrecheck(1)
  loadDedStats()
})
</script>

<style scoped>
/* 页面级范围查询条：与 RangeFilter 同一行，控件底对齐 */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
/* 面板顶部工具条：原「范围查询」独立面板并入后，用下边框 + 间距与正文分隔，
   视觉上仍是「一行筛选」，但不再多占一张卡的卡头与内边距 */
.panel-toolbar {
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--line);
  margin-bottom: var(--sp-3);
}
/* 重算进度条：贴在按钮行下方，限宽避免横贯整块面板 */
.recompute-progress {
  max-width: 420px;
  margin-top: var(--sp-2);
}
/* 扣分构成与 AI 预检左右并排（写法照抄 Dashboard 的 .grid-2）：
   四张卡纵向堆到 1235px、第 4 块落在屏外（审查报告 L2）。
   面板自带 14px 下边距，网格内改由 gap 供间距（:deep 置 0），避免双重留白；
   容器自己补 14px，与卡间节奏一致 */
.grid-2 {
  display: grid;
  /* ⚠️ 列必须用 minmax(0, …)：右栏 AI 预检表列宽合计 ~936px，其 min-content 会把
     默认的 1fr（= minmax(auto,1fr)）撑破 —— 整行越出 main 右缘、页面出现横向滚动条
     （1366/1600 两档实测踩到）。minmax(0,…) 允许列收缩，表内容由表格自身的横向滚动
     承接（滚动条已全局放开，可见可拖）。 */
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.45fr);
  gap: var(--sp-3);
  margin-bottom: 14px;
}
.grid-2 :deep(.panel) {
  margin-bottom: 0;
}
/* 报告口径：≥1600 档才左右并排；1366 档半幅宽连一行筛选都放不下，收回单列 */
@media (max-width: 1599px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
/* 截断 / 告警提示条：浅黄底，与错误红区分 */
.trunc-hint {
  background: var(--ochre-surface);
  border: 1px solid #ecd9b0;
  color: var(--ochre-text);
  border-radius: 6px;
  padding: var(--sp-2) var(--sp-3);
  font-size: var(--fs-xs);
  margin-bottom: var(--sp-2);
}
/* 预检列表上方说明行 */
.precheck-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.precheck-bar > span:first-child {
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
}
/* 「无扣分项」等正向文案 */
.ok {
  padding: var(--sp-2);
  color: var(--ink-mid);
  font-size: var(--fs-xs);
}
/* 面板内二级标题（左竖线） */
.sub-hd {
  margin: var(--sp-4) 0 var(--sp-2);
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: var(--sp-2);
}

/* ===== 评分标准面板===== */
/* 以下 .std-title / .std-body / .std-cols / .std-col / .std-dim / .std-grade / .rule-card
   为旧版标准面板样式；当前模板已改用 .std-grid / .st / .chip，这些类暂无引用（保留待清理） */
.std-title {
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
}
.std-body {
  padding-top: var(--sp-1);
}
.std-cols {
  display: flex;
  gap: 28px;
  align-items: flex-start;
}
.std-col {
  flex: 1;
  min-width: 0;
}
.std-dim {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: var(--sp-2) 0;
  border-bottom: 1px dashed var(--line);
  font-size: var(--fs-xs);
}
.std-dim .sd-name {
  font-weight: bold;
  color: var(--ink);
  flex: 0 0 96px;
}
.std-dim .sd-desc {
  flex: 1 1 auto;
  color: var(--text-sub-strong);
}
.std-dim .sd-w {
  flex: 0 0 auto;
  color: var(--ochre-text);
  font-weight: bold;
}
.std-grade {
  padding: var(--sp-2) var(--sp-3);
  border-radius: 4px;
  font-size: var(--fs-xs);
  margin-bottom: 6px;
}
.std-grade.ok {
  background: var(--ink-light);
  color: var(--ink);
}
.std-grade.mid {
  background: var(--ochre-light);
  color: #8a6a44;
}
.std-grade.bad {
  background: var(--danger-surface);
  color: #8a3d33;
}
.rule-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: 6px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--ink-mid);
  border-radius: 4px;
  background: var(--paper);
  font-size: var(--fs-xs);
  color: var(--ink);
}
.rule-card.tongue {
  border-left-color: var(--danger);
}

/* ===== 本范围扣分构成 ===== */
/* 按类型的扣分分布 */
.dist {
  margin-bottom: var(--sp-2);
}
/* 单行：类型名 + 横条 + 数值 */
.dist-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
  font-size: var(--fs-xs);
}
.dist-row .dr-l {
  flex: 0 0 130px;
  color: var(--ink);
}
.dist-row .dr-bar {
  flex: 1 1 auto;
  height: 12px;
  background: var(--paper);
  border-radius: 6px;
  overflow: hidden;
}
.dist-row .dr-bar i {
  display: block;
  height: 100%;
  background: var(--ochre);
}
.dist-row .dr-v {
  flex: 0 0 130px;
  text-align: right;
  color: var(--text-sub-strong);
}
/* 分级分布：胶囊标签 */
.grade-chips {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.grade-chips .gc {
  font-size: var(--fs-xs);
  padding: var(--sp-1) var(--sp-3);
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--ink);
}

/* ===== 规则配置弹窗 ===== */
.hd-action {
  margin-left: var(--sp-3);
}
/* 折叠区内的单条规则说明 */
.std-desc {
  font-size: var(--fs-xs);
  line-height: 1.9;
  color: var(--ink);
  padding: var(--sp-1) 0;
}
/* 标准：展开后横向吃宽度（auto-fit 多列），而不是竖着把页面拉长 ——
   原 flex-direction:column 每项独占一行，7 项 + 说明折叠区把展开态撑出一屏 */
.std-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: var(--sp-2) var(--sp-4);
}
/* 一行：标签固定宽 + 值自适应 */
.st {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: var(--fs-xs);
  line-height: 1.8;
}
.st-k {
  flex: 0 0 110px;
  color: var(--text-sub-strong);
}
.st-v {
  flex: 1 1 auto;
  color: var(--ink);
}
/* 要素标签（描边胶囊） */
.chip {
  display: inline-block;
  margin: 0 6px 2px 0;
  padding: 0 var(--sp-2);
  font-size: var(--fs-xs);
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--ink-light);
  color: var(--ink);
}
/* 完整说明的折叠区 */
.std-detail {
  margin-top: var(--sp-2);
  border-top: 1px dashed var(--line);
}
/* M20：评分标准默认收起（低频阅读），摘要行只保留关键口径；
   展开后 .std-grid 与「完整规则说明」才有内容 —— 展开状态不写死，用户点一次即可 */
.std-panel {
  border-top: 1px dashed var(--line);
  padding-top: var(--sp-2);
}
.std-sum {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--sp-2);
  list-style: none;
  cursor: pointer;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.std-sum::-webkit-details-marker {
  display: none;
}
.std-sum::before {
  content: '▸ ';
  color: var(--ink-mid);
}
.std-panel[open] .std-sum::before {
  content: '▾ ';
}
.std-panel[open] .std-sum {
  margin-bottom: var(--sp-2);
}
.std-sum:hover {
  color: var(--ink);
}
/* 28.12：预检列表的缺陷行（分级非「合格」）整行浅赭石底。
   RecordTable 是本组件的子组件，行 DOM 在其内部，故用 :deep 穿透。 */
:deep(.qc-defect-row) > td.el-table__cell {
  background: var(--ochre-light);
}
</style>
