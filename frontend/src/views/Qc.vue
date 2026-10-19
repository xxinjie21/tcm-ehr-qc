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

    <!-- 页面动作区（P0-1）：业务**动作**与筛选彻底分离。
         「质控评分计算 / 取消重算」原先挤在筛选条里，用户第一眼分不清
         「这是查询还是操作」；现固定在内容区右上，反馈（进度条 / 失败清单）紧随其后，
         筛选条只留筛选。动作口径仍是「按页首范围查询的范围」重算。 -->
    <div class="page-actions">
      <span class="tip">「质控评分计算」按页首「范围查询」的范围重算评分与分级</span>
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
    </div>

    <!-- 动作反馈：进度与失败清单属于「上面这个动作」，不属于筛选条 ——
         原先它们夹在筛选条里（进度条甚至把筛选条撑成两行），现跟随动作区。 -->
    <!-- I7：批量重算此前只有按钮文案（「重算中 1234/40000…」），看不出推进快慢。
         数据早已在 recomputeProgress 里，补一条进度条：只在任务运行时渲染，
         终态由 v-if 自动移除，不留占位 -->
    <el-progress
      v-if="isActiveTask(recomputeProgress)"
      class="recompute-progress"
      v-bind="recomputeBar"
      :stroke-width="10"
    />
    <!-- T2：运行中也显示失败计数 —— 后端每 25 条落库一次进度，轮询即可见；
         此时不回明细（长任务每 2 秒重渲染整张长表不值当），只给一行计数，
         与 NLP 批量面板的运行中口径一致。终态仍走下面的失败清单。 -->
    <div
      v-if="isActiveTask(recomputeProgress) && recomputeProgress.failed > 0"
      class="recompute-failures"
    >
      <div class="rf-hd">
        已失败 {{ recomputeProgress.failed }} 条 · 任务结束后显示清单并支持重试
      </div>
    </div>
    <!-- 失败清单 + 仅重试失败：终态且确有失败时显示（用户要看到失败的是哪条、为什么、并可只重试失败） -->
    <div
      v-if="!isActiveTask(recomputeProgress) && recomputeProgress && recomputeProgress.failures && recomputeProgress.failures.length"
      class="recompute-failures"
    >
      <div class="rf-hd">
        失败清单（{{ recomputeProgress.failures.length }} 条{{ recomputeProgress.failureTruncated ? '，仅显示前 500 条' : '' }}）
      </div>
      <el-table :data="recomputeProgress.failures" border size="small" max-height="240">
        <el-table-column prop="recordId" label="病历ID" width="290" show-overflow-tooltip />
        <el-table-column prop="reason" label="失败原因" show-overflow-tooltip />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.recordId)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="rf-actions">
        <el-button
          type="warning"
          size="small"
          :loading="retrying"
          @click="retryFailed(recomputeProgress.id)"
        >
          仅重试失败（{{ recomputeProgress.failed }} 条）
        </el-button>
      </div>
    </div>

    <!-- 范围查询（整页口径 · 共享）：一次「查询」同时刷新下方「本范围扣分构成」与
         「AI 预检列表 / 扣分明细」两块数据。
         此前它被并进「本范围扣分构成」卡内当工具条（审查报告 L2 为省掉一张 137px 高的空卡
         所改）。但那两张卡吃的都是这一个范围 —— 挂在其中一张卡里，会被读成
         「只作用于那张卡」。这里保留「不占一张空卡」的结论，只把它放回两张卡的共同上方：
         去卡壳的细工具栏，形态与语义对齐「清洗与导出」页的 .scope-bar。
         P0-1：壳子改用共享 ScopeBar 组件（外形与位置全站一份定义）。 -->
    <ScopeBar>
      <RangeFilter v-model="filters" />
      <!-- F7：与同排 RangeFilter 的 32px 输入框等高，按钮 default 档；
           底对齐后不会再出现 24px 与 32px 混排 -->
      <el-button type="primary" size="default" :loading="queryLoading" @click="applyFilters">查 询</el-button>
      <el-button size="default" :disabled="queryLoading" @click="resetFilters">重置</el-button>
      <span class="tip">范围对下方「扣分构成」与「AI 预检列表」同时生效</span>
    </ScopeBar>

    <!-- 本范围扣分构成：范围内各病历扣分明细聚合（只放聚合结果，范围查询已提到页首共享），
         与右侧「AI 预检列表」并排；两卡底边由 grid 行拉平（等高），
         卡内要「吃满剩余高度」的表格由 CSS 弹性 + 百分比高度决定，不再用 JS 量像素 -->
    <div class="grid-2">
      <PanelCard title="本范围扣分构成">
        <div>
          <!-- P0-3：原 v-loading 整块遮罩会把卡内已有文字（数字、表头）一起盖住 ——
               改为「还没有聚合结果时用骨架占位、已有结果时保持可见」，不再挂遮罩。
               刷新时的动效由页首「查 询」按钮的 loading 圈承担（它才是触发者）。 -->
          <LoadingSkeleton v-if="dedLoading && !dedStats" variant="chart" :rows="5" label="正在统计扣分分布…" />
          <template v-else-if="dedStats">
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
              <!-- P0-2：撑满卡内剩余高度改由 CSS 决定（.fill-table 吃满弹性空间、
                    表格自身 height:100%），不再用 JS 量卡片像素反算 —— 见本文件样式段 -->
              <div class="fill-table fill-table--left">
                <el-table :data="dedStats.byItem" border size="small" max-height="560">
                  <el-table-column prop="type" label="类型" width="130" />
                  <el-table-column prop="item" label="项" width="110" />
                  <el-table-column prop="count" label="受影响病历数" width="110" />
                  <el-table-column prop="points" label="合计扣分" width="100" />
                </el-table>
              </div>
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
          <EmptyState v-else text="当前范围暂无可统计的评分结果" :image-size="70" />
        </div>
      </PanelCard>

      <!-- AI 预检列表：与「病历数据」共用同一套 searchRecords 查询，扣分范围沿用页首筛选。
            与左侧「本范围扣分构成」并排（grid-2）：原来 4 张卡纵向堆到 1235px，
            第 4 块整块在屏外，先用并排把上半页收成一行 -->
      <PanelCard title="AI 预检列表 / 扣分明细">
        <!-- M20：原「点击行查看…」提示条独占一行（约 40px）。并进卡头后，
             首条扣分明细在 1366×768 下从 y=776 提前到屏内（标题行本来就有 48px 高） -->
        <template #header>
          <span>AI 预检列表 / 扣分明细</span>
          <span class="tip" style="font-weight: normal">点击行查看规则扣分明细；扣分范围沿用页首「范围查询」</span>
        </template>

        <!-- 并排后本表只有半幅宽（约 660px），固定列合计 716px 必然横向滚动 ——
             theme.css 已放开 EP 横向滚动条，滚动是可达的；操作列仍 fixed 在右侧。
             P0-2：高度改由 CSS 决定（外层 .fill-table 吃满卡内剩余高度、表格 height:100%），
             左栏扣分构成高时右栏跟着长、左栏矮时右栏也不再缩在卡顶，两栏底边始终齐平；
             min-height 420 与改造前写死的档位一致，不会比那时更矮 -->
        <div class="fill-table fill-table--right">
          <RecordTable
              ref="precheckTableRef"
              :rows="precheckRows"
              :loading="precheckLoading"
              loading-text="正在预检待复核项…"
              :height="790"
              :action-width="100"
              :row-class-name="precheckRowClass"
              @sort-change="onPrecheckSortChange"
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
        </div>
        <!-- 分页：切换每页条数时回到第 1 页（见 handleSizeChange） -->
        <el-pagination
          v-model:current-page="precheckPage"
          v-model:page-size="precheckSize"
          :page-sizes="PAGE_SIZES_STANDARD"
:total="precheckTotal"
            layout="total, sizes, prev, pager, next, jumper"
            :disabled="precheckLoading"
            style="margin-top: var(--sp-3); justify-content: flex-end"
            @current-change="loadPrecheck"
          @size-change="handleSizeChange"
        />
      </PanelCard>
    </div>

    <!-- T1：最近重算任务清单。此前刷新/切页返回只能接上「正在跑」的那条，
         已结束的任务详情无处可看；这里补上与 NLP 批量面板同口径的清单，
         点「查看」拉详情（含失败明细）。截断标记由后端下发，不足上限时不出现。
         它是**任务监控**、不是哪张结果卡的内容 —— 所以拿出来单独成卡（原挂在
         「本范围扣分构成」卡尾，会把该卡撑到比右栏高出一截），且放在结果区之后。 -->
    <PanelCard v-if="taskList.length" title="最近重算任务">
      <div class="rt-hd">
        最多 {{ taskListLimit }} 条<span v-if="taskListTruncated" class="rt-trunc">已省略更早的任务</span>
        <el-checkbox v-model="onlyFailedTasks" size="small" style="margin-left: var(--sp-3)">只看有失败的</el-checkbox>
      </div>
      <!-- 2026-10-18 用户指令：最多50条也要内滚，固定 max-height 360（约9行），超出表体内滚、不再随条数无限撑长 -->
      <el-table
        :data="onlyFailedTasks ? taskList.filter((t) => t.failed > 0) : taskList"
        border
        size="small"
        max-height="360"
      >
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ fmtDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">{{ taskStatusText(row) }}</template>
        </el-table-column>
        <el-table-column prop="total" label="条数" width="90" />
        <el-table-column prop="success" label="成功" width="90" />
        <el-table-column prop="failed" label="失败" width="90" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewTask(row.id)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PanelCard>

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
          <span v-if="rules">合格 ≥ {{ rules.thresholds.reviewUpper }} 分 · 无效 &lt; {{ rules.thresholds.invalid }} 分 · 病历应包含 {{ rules.completeness.elements.length }} 项</span>
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
          <span class="st-k">术语归一</span>
          <span class="st-v">{{ normTypeNames.length ? normTypeNames.join('、') : '全部不归一' }}</span>
        </div>
        <div class="st">
          <span class="st-k">术语标准化</span>
          <span class="st-v">{{ rules.standardization.enabled
            ? ('开 · 计分类型 ' + (scoredTypeNames.length ? scoredTypeNames.join('、') : '无')
               + '；未命中词典的每个 -' + rules.standardization.weightEach
               + '；超过 ' + rules.standardization.cap + ' 个后，每再满 '
               + rules.standardization.cap + ' 个追加一档（分段扣分，避免大量未归一时触顶、分数失去区分度）'
               + (excludedTypeNames.length ? '；已勾选但未开归一、不计分：' + excludedTypeNames.join('、') : ''))
            : '已关闭' }}</span>
        </div>
        <div class="st">
          <span class="st-k">重复</span>
          <span class="st-v">-{{ rules.duplicateWeight }}</span>
        </div>
        <div class="st">
          <span class="st-k">分级</span>
          <span class="st-v">合格 ≥{{ rules.thresholds.reviewUpper }} ／ 无效 &lt;{{ rules.thresholds.invalid }}</span>
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
import LoadingSkeleton from '@/components/LoadingSkeleton.vue'
import QcRulesDialog from '@/components/QcRulesDialog.vue'
import QcDeductionDialog from '@/components/QcDeductionDialog.vue'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import ScopeBar from '@/components/ScopeBar.vue'
import { recomputeQc, listQcBatch, getQcBatch, cancelQcBatch, retryQcBatchFailed, qcScore, getQcRules, getDeductionStats } from '@/api/qc'
import { searchRecords, getRawRecord } from '@/api/records'
import { GRADE_OK } from '@/utils/grade'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'
import { PAGE_SIZES_STANDARD } from '@/utils/constants'

// 规则写入口按 admin / owner / 授权成员 三档判定（与后端 qc/rules 写接口一致）
const userStore = useUserStore()
const canWriteRules = computed(() => userStore.canWriteQcRulesEntry)

// 整页共用的筛选条件，由上方 RangeFilter 通过 v-model 维护。
// 默认不带时间范围：进页面即看全库（两端都空 = 不按时间过滤）。
// 想看「刚导进来的这批」用筛选条上的「今天」按钮临时圈定。
const filters = reactive({ department: '', dateRange: null, grade: '', sortBy: '', sortOrder: '', importRange: null })

// ===== 评分标准 / 扣分构成=====
// 评分标准：rules 为当前生效规则，descriptions 为自然语言说明，catalog* 为可选项目录
const rules = ref(null)
const ruleWarnings = ref([])
const descriptions = ref([])
const catalogElements = ref([])
const catalogFormats = ref([])
const dedStats = ref(null)
const dedLoading = ref(false)

// ===== 并排两栏的等高 =====
// P0-2：不再在前端按卡片实测高度反算表格像素（那套 JS 量取依赖 DOM 时机，
// 换档/数据变化就要重量，漏一次就塌）；改为纯布局：
//   · 两卡等高 —— .grid-2 是 grid，行内两项默认 stretch，天然齐平；
//   · 卡内表格吃满剩余高度 —— .fill-table（flex:1）+ 表格 height:100%，
//     高度链全部由 CSS 定（见样式段），无需 JS 参与。

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

// 标准里的合格线。规则未就绪时给 null（显示「—」），不再退回出厂默认 ——
// 那个默认是后端出厂值的拷贝，管理员改过合格线后，等待期里界面会按旧值
// 算出「距合格线还差 N 分」，与服务端结论矛盾。
const qualified = computed(() => rules.value?.thresholds?.reviewUpper ?? null)

// 术语类型 key → 中文名：用目录，目录没有（后端新增类型 / 未加载）时回退 key 本身。
const typeName = (k) => catalogElements.value.find((e) => e.typeKey === k)?.name || k
// 术语归一的类型（哪些做「原文 → 标准词」归一）
const normTypeNames = computed(() => (rules.value?.normalization?.elementTypes || []).map(typeName))
// 术语标准化里勾选、但未开归一 → 其实体不写 normLevel、不计分（QcScorer 取交集）
const excludedTypeNames = computed(() => {
  const norm = new Set(rules.value?.normalization?.elementTypes || [])
  return (rules.value?.standardization?.elementTypes || []).filter((t) => !norm.has(t)).map(typeName)
})
// 实际计分类型 = 标准化勾选 ∩ 归一范围 —— 面板显示这一份，才是服务端真正用的口径
const scoredTypeNames = computed(() => {
  const norm = new Set(rules.value?.normalization?.elementTypes || [])
  return (rules.value?.standardization?.elementTypes || []).filter((t) => norm.has(t)).map(typeName)
})

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
  // 1.1 取导入时间区间：独立维度，同样两端齐全才发
  const ir = filters.importRange
  // 2. 组装接口参数：起止缺任一端都按未选处理，空串交给后端忽略
  return {
    department: filters.department || '',
    start: d && d.length === 2 ? d[0] : '',
    end: d && d.length === 2 ? d[1] : '',
    importStart: ir && ir.length === 2 ? ir[0] : '',
    importEnd: ir && ir.length === 2 ? ir[1] : '',
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
const precheckSize = ref(20)

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
  fetcher: (signal) => searchRecords({ ...filters, page: precheckPage.value, pageSize: precheckSize.value }, { signal }),
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

// B3 列头排序（评分 / 接诊时间）：写入 filters 回第 1 页重查。
// sortBy/sortOrder 随 useUrlFilters 入 URL；这两个键随重算/扣分统计发给 FiltersDTO
// 端点时被 Jackson / Spring 绑定忽略（未知字段），不影响范围语义。
const onPrecheckSortChange = ({ sortBy, sortOrder }) => {
  filters.sortBy = sortBy
  filters.sortOrder = sortOrder
  loadPrecheck(1)
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
// 重置筛选并立即重新查询：清空全部条件（含导入时间），回到「全库」口径 —— 与初始进入页面一致
const resetFilters = () => {
  filters.department = ''
  filters.dateRange = null
  filters.importRange = null
  filters.grade = ''
  applyFilters()
}

// ===== 质控评分计算（§七 L5/L6：异步任务）=====
// 重算已从「同步等结果」改为「提交拿 taskId → 2s 轮询进度 → 终态提示分级汇总」。
// 同步跑 40000 条会把请求挂到超时，用户关页面任务也还在跑；异步后可以离开再回来。
const recomputing = ref(false)
// 取消请求在途：按钮进入 loading，避免连点发出第二次 cancel
const cancelling = ref(false)
// 仅重试失败请求在途
const retrying = ref(false)
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

/**
 * 进度条的两态配置（统一口径见 Governance.vue 的清洗进度条）。
 *
 * 排队中（total 还没算出来）、或一条都还没处理完时，recomputePercent 为 0 ⇒
 * 填充宽度就是 0（el-progress 的填充宽度恒等于 percentage%，见 Element Plus
 * progress.vue 的 barStyle），条子静止且什么都看不见。这段时间改用不确定流动条
 * 只表达「在推进」，不显示百分比数字，避免暗示一个并不存在的精确进度；
 * 有可信数字后切回真实百分比，条纹继续流动表示仍在处理。
 *
 * 本条只在任务运行时渲染（模板里的 v-if），故不必再判终态。
 */
const recomputeBar = computed(() => {
  const t = recomputeProgress.value
  // 1. 还没有任何推进 ⇒ 没有可信数字，走不确定态
  if (!t || !t.done) {
    // percentage 必须给 100：不确定动画是在「100% 宽」的填充上左右滑动，给 0 就看不见
    return { percentage: 100, indeterminate: true, striped: false, stripedFlow: false, showText: false }
  }
  // 2. 有可信数字 ⇒ 真实百分比 + 条纹流动
  return {
    percentage: recomputePercent.value,
    indeterminate: false,
    striped: true,
    stripedFlow: true,
    showText: true
  }
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
      loadTaskList()
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
    loadTaskList()
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

// 仅重试失败：按原任务失败集合重新提交一个只跑这批病历的重算任务，随后轮询新任务
const retryFailed = async (taskId) => {
  if (!(await confirmBox('将对本次失败的那批病历重新提交重算（不重跑已成功的），确定？', '仅重试失败'))) {
    return
  }
  retrying.value = true
  try {
    const res = await retryQcBatchFailed(taskId)
    const t = res.data || {}
    recomputeProgress.value = t
    loadTaskList()
    if (isActiveTask(t)) {
      pollTask(t.id)
    }
    ElMessage.success(`已提交，计划重试 ${t.total} 条`)
  } catch {
    // 拦截器已提示（无失败记录 / 已有任务在跑）
  } finally {
    retrying.value = false
  }
}

// ===== 最近重算任务清单（T1）=====
// 此前页面只表达「当前/最近一次」任务：刷新或切页返回能接上在跑的那条，
// 但已结束的任务详情无处可看。后端列表接口回包是 { tasks, truncated, limit }（不是裸数组），
// 截断标记要显式说出来 —— 页面不能自己写死「最多 50 条」，不足 50 条时那句话本身就是错的。
const taskList = ref([])
const taskListTruncated = ref(false)
const taskListLimit = ref(50)
// 「只看有失败的」：长任务要能一眼找到出过错的批次，不必逐条看状态
const onlyFailedTasks = ref(false)

// 拉取最近任务；失败静默（拦截器已提示），不阻塞页面其余部分。返回任务数组供复用。
const loadTaskList = async () => {
  try {
    const res = await listQcBatch()
    const tasks = res.data?.tasks || []
    taskList.value = tasks
    taskListTruncated.value = res.data?.truncated === true
    taskListLimit.value = res.data?.limit || 50
    return tasks
  } catch {
    // 拦截器已提示
    return []
  }
}

// 状态文案：与上方进度提示同一口径，避免列表里出现 QUEUED/RUNNING 这类裸码
const taskStatusText = (t) => {
  switch (t.status) {
    case 'QUEUED': return '排队中'
    case 'RUNNING': return `进行中 ${t.done}/${t.total}`
    case 'COMPLETED': return '已完成'
    case 'CANCELLED': return '已取消'
    case 'INTERRUPTED': return '已中断（可重跑）'
    case 'FAILED': return '失败'
    default: return t.status
  }
}

// 回看某条历史任务的进度与失败明细：列表接口不带 failures（省流量），明细走详情接口。
// 若点到的其实还在跑，则接上轮询；否则停轮询（已结束的任务不该再动）。
const viewTask = async (id) => {
  try {
    const res = await getQcBatch(id)
    recomputeProgress.value = res.data || null
    if (isActiveTask(recomputeProgress.value)) {
      pollTask(id)
    } else {
      stopPoll()
    }
  } catch {
    // 拦截器已提示
  }
}

// 回到本页时接上正在跑的重算任务。
//
// 重算是长任务（几千条要跑几分钟），用户切到别的页面再回来时本组件已重新挂载，
// recomputeProgress 回到 null —— 不接的话页面看起来「什么都没发生」，而任务其实还在后台跑，
// 且此时点「重算」只会被幂等保护拒绝（「已有重算任务在排队或运行中」），用户无从知道它跑到哪了。
// 列表接口本就只返回本组织的任务，直接找状态为排队/进行中的那条接着轮询即可
// （后端保证同一组织同时只有一条重算任务在跑，故取首条即可）。
const resumeRunningTask = async () => {
  const tasks = await loadTaskList()
  const running = tasks.find((t) => isActiveTask(t))
  if (!running) {
    return
  }
  recomputeProgress.value = running
  pollTask(running.id)
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
      // 吞掉失败：结构化数据只是弹窗里的补充展示，接口报错时保持「未解析」形态即可，弹窗照常打开
      .catch(() => {})
    // 4. 打开弹窗
    detailVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

// 只收起弹窗、不清 detail，避免关闭动画期间内容闪空

// 进页面：规则 + 预检列表 + 扣分构成并行拉取；另接上可能仍在跑的重算任务
onMounted(() => {
  loadRules()
  loadPrecheck(1)
  loadDedStats()
  resumeRunningTask()
})
</script>

<style scoped>
/* 页面动作区（P0-1）：业务动作不混进筛选条，固定在内容区右上。
   与下方筛选条（.scope-bar，基类在 theme.css）同一水平内边距，视觉上仍属同一条动线。 */
.page-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}
/* 重算进度条：贴在按钮行下方，限宽避免横贯整块面板 */
.recompute-progress {
  max-width: 420px;
  margin-top: var(--sp-2);
}
/* 失败清单：终态后展示失败的是哪条、为什么，并提供「仅重试失败」入口 */
.recompute-failures {
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--line-soft);
}
.recompute-failures .rf-hd {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-bottom: var(--sp-2);
}
.recompute-failures .rf-actions {
  margin-top: var(--sp-2);
}
/* 最近重算任务清单（T1）：2026-10-18 用户指令「最近任务用滑动条、不要无限增加长度」—— 表体固定 max-height 360（约9行），超出内滚，不再随任务条数撑高到页面底部 */
.rt-hd {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-bottom: var(--sp-2);
}
.rt-trunc {
  margin-left: var(--sp-2);
  color: var(--ochre-text);
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
  display: flex;
  flex-direction: column;
}
/* ===== P0-2：两卡等高 + 卡内表格吃满剩余高度，全部交给布局本身 =====
   等高：.grid-2 是 grid，行内两项默认 align-items: stretch，行高取两者较大的
        内容高，矮的那张被拉平 —— 不需要任何 JS 参与。
   吃满：① 两张卡都改成纵列弹性容器（.panel 本身是块状、正文高度由内容决定，
           只给正文加 flex 没有「可分配空间」—— flex-basis:0 会把正文压成 0 高、
            内容溢出到卡外，卡片只剩卡头）；
         ② 正文（.panel-bd）拿到确定高度后自己也是纵列容器，把确定高度传给
            卡内需要「吃满」的那块（.fill-table：flex:1 吃剩余空间）；
         ③ .fill-table 里的 el-table 用 height="100%"（EP 把这个字符串直接写进
            根元素 style，纯 CSS 解析），数据不满时空白落在表体内、行多时表体内滚。
   ⚠️ 这条链上任一环的高度必须是「确定的」：环断裂（例如中间夹一层没有 flex/高度的
      普通 div）时百分比高度会静默退化成 auto，表现为表格缩回内容高、卡片底部空一块。 */
.grid-2 :deep(.panel-bd) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}
/* 左卡正文（v-loading 包装层）：内容居中，保证「本范围内没有扣分项 / 暂无可统计的
   评分结果」这类空态落在卡片中间，而不是顶在卡头下面把下面四百多像素全空着
   （1366/1680 两档实测 450px+）；safe center 让内容超出时退回顶部、不被推出可视区。
   有数据时 .fill-table 靠 flex:1 吃满剩余高度，居中自然无效、不影响常态。 */
.grid-2 > :deep(.panel:first-child) .panel-bd > * {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  /* 单列布局（<1600px）时卡片高度由内容决定，正文在「统计中」这段还没有内容，
     卡片会缩成卡头一条、v-loading 的遮罩与转圈被压成 0 高。给正文一个下限，
     加载态与「范围内无扣分项」态都有正常的一屏内高度。 */
  min-height: 132px;
}
/* 加载态骨架占满卡内剩余高度：行从顶上排（safe center 对「撑满的子项」无效），
   读起来是「内容正在一条条来」，而不是悬在卡片正中的一截色带。 */
.grid-2 > :deep(.panel:first-child) .panel-bd .load-skel {
  flex: 1 1 auto;
}
/* 卡内「吃满剩余高度」的表格容器：flex 吃掉卡内剩余空间，表格自身 height="100%"。
   min-height 与改造前写死的档位一致（左 200 / 右 420），保证单列（<1600px，卡片高度
   由内容决定、百分比退化为 auto）时不会比那时更矮。 */
.fill-table {
  flex: 0 0 auto;
  min-height: 0;
}
.fill-table--left {
  min-height: 200px;
}
.fill-table--right {
  min-height: 420px;
}
/* 2026-10-18 用户反馈「空数据时把 el-table__empty-block 长度调大、左右要对称」：
   有数据时右表由 .fill-table flex:1 撑高（现状不回归）；空态时表内容少、el-table 只按
   内容高 299px 缩起，表底与分页之间留 121px 空白。这里给右卡 el-table 一个 420px 下限，
   空态 empty-block（height:100%）撑满居中、表与分页间空隙消失；内容高于 420 时下限不生效。 */
.grid-2 > :deep(.panel:last-child) .el-table {
  min-height: 420px;
}
/* 返工第2次（2026-10-18 实测）：el-table 根 420 是 min-height 撑出来的、并非确定高度，
   百分比 height:100% 对无确定高度父级不生效（被当 auto），inner-wrapper 仍按内容高 299 缩起、
   body-wrapper/empty-block 仍 267、根内 625→746 仍有 121px 空。
   改为给 inner-wrapper 直接下 min-height:420：inner-wrapper 内部是 flex 列（header ~32 +
   body-wrapper flex:1）→ body-wrapper 自动撑到 420-32=388 → empty-block(height:100%) 撑到
   388 居中显示「筛选范围内没有病历」，根内 121px 空消失。
   el-table 根的 min-height:420 保留（两者叠加根仍 420、表底与分页 12px 空隙、左右卡等高 563 不变）；
   有数据态（20 行 ~740px）内容高于 420，min-height 不绑定、无回归。 */
.grid-2 > :deep(.panel:last-child) .el-table__inner-wrapper {
  min-height: 420px;
}
/* 返工第3次（2026-10-18 实测）：根/inner/body-wrapper 全由 min-height/flex 撑出、均为非确定高度，
   empty-block 的 inline height:100% 逐级解析失败、停在内容高 267（文本上方对齐、下方露 121px 空）。
   直接对 empty-block 下确定高度绕开百分比级联：定高 388（=body-wrapper 420 - 表头 ~32）撑满，
   el-empty 在其中垂直居中；!important 必须，用于覆盖 EP 的 inline height:100%。
   空态专属节点，有数据态不存在、零影响。 */
.grid-2 > :deep(.panel:last-child) .el-table__empty-block {
  height: 388px !important;
}
/* 空态左卡：显示「本范围内没有扣分项」(.ok) 时，紧随其后的「分级分布」标题(.sub-hd)原本
   悬在卡中部（.grade-chips 为空 h=0）。用 margin-top:auto 把它连同空 chips 一起推到卡底，
   形成顶部「无扣分项」+ 底部「分级分布」的沉底分布，与右栏空态对称；有数据时 .ok 不存在、
   此规则不命中，不回归。 */
.grid-2 > :deep(.panel:first-child) .panel-bd > div .ok + .sub-hd {
  margin-top: auto;
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
  border: 1px solid var(--cmp-qc-warn-border);
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
  color: var(--ochre-strong);
}
.std-grade.bad {
  background: var(--danger-surface);
  color: var(--el-color-danger-dark-2);
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
