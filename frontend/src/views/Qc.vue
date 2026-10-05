<template>
  <!-- 质控页：范围查询（整页口径）+ 评分标准 + 规则配置（三档授权）+ 扣分构成 + AI 预检列表 + 扣分明细弹窗 -->
  <div>
    <!-- 范围查询提升为整页生效：此前 RangeFilter 只喂图谱，
         而「AI 预检列表」另挂一个独立的分级下拉，同一页存在两个互不相干的范围口径 -->
    <PanelCard title="范围查询">
      <div class="filter-bar">
        <RangeFilter v-model="filters" />
        <el-button type="primary" size="small" :loading="queryLoading" @click="applyFilters">查 询</el-button>
        <el-button size="small" :disabled="queryLoading" @click="resetFilters">重置</el-button>
        <!-- 重算是异步任务（§七 L5）：提交后按钮立刻解锁，进度单独显示，别让按钮一直转圈 -->
        <el-button
          type="warning"
          size="small"
          :loading="recomputing"
          :disabled="isActiveTask(recomputeProgress)"
          @click="handleRecompute"
        >
          {{ recomputeButtonText }}
        </el-button>
        <span class="tip">范围对本页各块同时生效；「质控评分计算」按当前范围重算评分与分级</span>
      </div>
    </PanelCard>

    <!-- 质控评分标准：直接展示自然语言描述（与规则同源） -->
    <PanelCard title="质控评分标准">
      <template #header>
        <span>质控评分标准</span>
        <!-- 无写权限时不隐藏按钮，而是禁用并常驻写明原因：
             藏起来用户只会以为「这页没有这个功能」，永远不知道是权限问题 -->
        <el-button link type="primary" class="hd-action" :disabled="!canWriteRules" @click="openRules">规则配置</el-button>
        <span v-if="!canWriteRules" class="tip">需管理员、组织所有者或被授权成员才能改规则</span>
      </template>
      <!-- 标准摘要：把当前生效的规则用自然语言摊开，改规则即随之变化（与规则同源） -->
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
    </PanelCard>

    <!-- 规则配置（管理员 / 所有者 / 被授权成员）：句子清单 + 就地编辑，保存即生效 -->
    <el-dialog v-model="rulesVisible" title="规则配置（改完点保存即生效）" width="min(1000px, 96vw)" top="4vh">
      <!-- 句子式编辑器：每段就是一句可读的话，直接在句子里改数字 / 选项，不暴露 JSON -->
      <div v-if="form.rules" class="rc">
        <div class="rc-tip">下面就是当前生效的规则，直接在句子里改即可。</div>

        <div class="rc-hd">① 病历应有这些要素（完整性）</div>
        <div class="rc-line">
          病历应有
          <el-select v-model="form.elementNames" multiple filterable collapse-tags size="small" style="min-width: 320px">
            <el-option v-for="e in catalogElements" :key="e.name" :label="e.name" :value="e.name" />
          </el-select>
          ；完全缺失每项扣
          <el-input-number v-model="form.fullWeight" size="small" :min="0" :controls="false" />
          分，仅有原始记录每项扣
          <el-input-number v-model="form.partialWeight" size="small" :min="0" :controls="false" />
          分。
        </div>

        <div class="rc-hd">② 格式检查（勾选即可，无需填写规则）</div>
        <div class="rc-flow">
          <div v-for="t in catalogFormats" :key="t.field" class="rc-fmt" :class="{ on: !!fmtOf(t.field) }">
            <el-checkbox :model-value="!!fmtOf(t.field)" @change="(v) => toggleFormat(t, v)" />
            <span class="rc-fmt-l">{{ t.label }}</span>
            <template v-if="fmtOf(t.field)">
              不合规扣
              <el-input-number
                :model-value="fmtOf(t.field).weight"
                size="small"
                :min="0"
                :controls="false"
                @update:model-value="(v) => setFmtWeight(t.field, v)"
              />
              分
            </template>
          </div>
        </div>
        <div v-for="(f, i) in customFormats" :key="f.uid" class="rc-line">
          【{{ f.label || f.field }}】不合规扣
          <el-input-number v-model="f.weight" size="small" :min="0" :controls="false" />
          分
          <el-button link type="danger" @click="removeCustomFormat(i)">删</el-button>
        </div>

        <div class="rc-hd">③ 一致性规则（触发类型 → 期望类型，期望值取自词典）</div>
        <div v-for="(c, i) in form.consistency" :key="c.uid" class="rc-block">
          <div class="rc-line">
            若
            <el-select v-model="c.triggerType" filterable size="small" style="width: 120px">
              <el-option v-for="t in catalogElements" :key="t.typeKey || t.source" :label="t.name" :value="t.typeKey || t.source" />
            </el-select>
            含
            <!-- 用 el-select-v2（虚拟滚动）：证候词典已 2080 条，普通 el-select 一次挂载
                 2000+ 个 el-option 会卡。allow-create 已移除 —— 候选表完整后从列表选即可，
                 避免敲入词典外的错词导致一致性规则永不匹配。
                 候选改为**远程检索**（remote + remote-method）：原先为喂一个下拉
                 一次性把五类词典全量拉进内存（≈3589 个 option），现在展开才取前 50 条。 -->
            <el-select-v2
              v-model="c.triggerValues"
              :options="termOptionsOf(c.triggerType).options.value"
              :remote-method="termOptionsOf(c.triggerType).search"
              :loading="termOptionsOf(c.triggerType).loading.value"
              :visible-change="(v) => v && termOptionsOf(c.triggerType).preload()"
              :remote-show-suffix="false"
              multiple filterable remote collapse-tags size="small" style="min-width: 220px"
              placeholder="从词典中选（可输入搜索）"
            />
          </div>
          <div class="rc-line">
            则
            <el-select v-model="c.expectType" filterable size="small" style="width: 120px">
              <el-option v-for="t in catalogElements" :key="t.typeKey || t.source" :label="t.name" :value="t.typeKey || t.source" />
            </el-select>
            应为
            <el-select-v2
              v-model="c.expectValues"
              :options="termOptionsOf(c.expectType).options.value"
              :remote-method="termOptionsOf(c.expectType).search"
              :loading="termOptionsOf(c.expectType).loading.value"
              :visible-change="(v) => v && termOptionsOf(c.expectType).preload()"
              :remote-show-suffix="false"
              multiple filterable remote collapse-tags size="small" style="min-width: 220px"
              placeholder="从词典中选（可输入搜索）"
            />
            冲突扣
            <el-input-number v-model="c.weight" size="small" :min="0" :controls="false" />
            分
            <el-button link type="danger" @click="form.consistency.splice(i, 1)">删</el-button>
          </div>
        </div>
        <el-button size="small" @click="addConsistency">+ 添加一致性规则</el-button>

        <div class="rc-hd">④ 其它</div>
        <div class="rc-line">
          <el-switch v-model="form.rules.standardization.enabled" />
          术语标准化：未命中词典的每个扣
          <el-input-number v-model="form.rules.standardization.weightEach" size="small" :min="0" :controls="false" />
          分；未归一条数超过
          <el-input-number v-model="form.rules.standardization.cap" size="small" :min="0" :controls="false" />
          后按档累加（每满该条数再加扣一档，<b>不再封顶</b>，避免大量未归一时分数失去区分度）。
          保存即生效；已评过的病历需重跑质控才会更新。
        </div>
        <div class="rc-line">
          重复病历扣
          <el-input-number v-model="form.rules.duplicateWeight" size="small" :min="0" :controls="false" />
          分。
        </div>
        <div class="rc-line">
          合格线
          <el-input-number v-model="form.rules.thresholds.qualified" size="small" :min="0" :max="100" :controls="false" />
          分；无效线
          <el-input-number v-model="form.rules.thresholds.invalid" size="small" :min="0" :max="100" :controls="false" />
          分；核心真缺失
          <el-input-number v-model="form.rules.thresholds.seriousFullMissing" size="small" :min="1" :controls="false" />
          项判无效。
        </div>
      </div>
      <template #footer>
        <el-button @click="resetRules">恢复默认</el-button>
        <el-button @click="rulesVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRules" @click="saveRules">保存并生效</el-button>
      </template>
    </el-dialog>

    <!-- 本范围扣分构成：范围内各病历扣分明细聚合 -->
    <PanelCard title="本范围扣分构成">
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

    <!-- AI 预检列表：与「病历数据」共用同一套 searchRecords 查询，扣分范围沿用上方筛选 -->
    <PanelCard title="AI 预检列表 / 扣分明细">
      <div class="precheck-bar">
        <span class="tip">点击行查看规则扣分明细；扣分范围沿用上方「范围查询」</span>
      </div>

      <!-- max-height 360：表头 32 + 10 行 × 32 + 余量，表格内部滚动 -->
      <RecordTable
          ref="precheckTableRef"
          :rows="precheckRows"
          :loading="precheckLoading"
          loading-text="正在预检待复核项…"
          :max-height="360"
          :action-width="120"
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
        layout="total, sizes, prev, pager, next"
        style="margin-top: var(--sp-3); justify-content: flex-end"
        @current-change="loadPrecheck"
        @size-change="handleSizeChange"
      />
    </PanelCard>

    <!-- 扣分明细弹窗：评分/分级 + 评分构成瀑布 + 扣分明细表 -->
    <el-dialog
      v-model="detailVisible"
      title="规则预检单（扣分明细）"
      width="min(1080px, 94vw)"
      top="7vh"
    >
      <!-- 弹窗内容单栏：评分 / 分级 → 评分构成瀑布 → 扣分明细表 -->
      <div v-if="detail">
        <div class="ded-col">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="评分">{{ detail.score }}</el-descriptions-item>
            <el-descriptions-item label="分级">{{ detail.grade }}</el-descriptions-item>
          </el-descriptions>

          <!-- 评分构成瀑布：100 分起逐项扣到最终分，一眼看分扣在哪 -->
          <div class="sd-title">评分构成（100 分起，逐项扣）</div>
          <div class="wf">
            <div class="wf-item"><span class="wf-l">总分</span><b class="wf-num">100</b></div>
            <div v-for="(d, i) in detail.deductions" :key="i" class="wf-item">
              <span class="wf-l">{{ d.type }} · {{ d.item }}</span>
              <b class="wf-num neg">-{{ d.points }}</b>
            </div>
            <div class="wf-item end">
              <span class="wf-l">最终得分</span>
              <b class="wf-num">{{ detail.score }}</b>
              <span class="wf-grade" :class="gradeClass(detail.grade)">{{ detail.grade }}</span>
            </div>
          </div>
          <div class="wf-note">
            合格线 {{ qualifiedText }} 分
            <template v-if="distanceToQualified != null">，距合格线还差 {{ distanceToQualified }} 分</template>
          </div>

          <div class="sd-title">扣分明细（合计 -{{ detailTotal }} 分）</div>
          <el-table :data="detail.deductions" border size="small" max-height="340">
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column prop="item" label="项" width="90" />
            <el-table-column prop="points" label="扣分" width="70">
              <template #default="{ row }">-{{ row.points }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" show-overflow-tooltip />
            <template #empty><div class="ok">无扣分项</div></template>
          </el-table>
        </div>
      </div>
      <template #footer>
        <el-button @click="closeDetail">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 质控页：范围查询是整页口径 —— 一次「查询」同时刷新「扣分构成」与「AI 预检列表」两块。
// 有写权限者另有「规则配置」弹窗，保存后规则立即生效，无需重启后端。
import VisitTimeCell from '@/components/cells/VisitTimeCell.vue'
import AgeGenderCell from '@/components/cells/AgeGenderCell.vue'
import { reactive, ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useTermOptions } from '@/composables/useTermOptions'
import { ElMessage } from 'element-plus'
import { confirmBox } from '@/utils/confirm'
import EmptyState from '@/components/EmptyState.vue'
import { usePagedList } from '@/composables/usePagedList'
import RecordTable from '@/components/RecordTable.vue'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { recomputeQc, getQcBatch, qcScore, getQcRules, getDeductionStats, updateQcRules, resetQcRules } from '@/api/qc'
import { searchRecords } from '@/api/records'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'
import { PAGE_SIZES_STANDARD } from '@/utils/constants'
import { gradeClass as gradeClassOf, THRESHOLD_PLACEHOLDER } from '@/utils/grade'

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
// 分级 → 样式类：读 utils/grade.js 的唯一副本，不再在本页另写一份映射
const gradeClass = gradeClassOf

// 阈值未就绪时统一显示「—」，也算不出「距合格线还差多少」
const qualifiedText = computed(() =>
  qualified.value == null ? THRESHOLD_PLACEHOLDER : qualified.value
)
const distanceToQualified = computed(() =>
  qualified.value == null ? null : Math.max(0, qualified.value - (detail.value?.score ?? 0))
)

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

// ===== 规则配置（有写权限者）：句子清单 + 就地编辑 =====
// 规则配置弹窗状态
const rulesVisible = ref(false)
const savingRules = ref(false)
// 各词典类型的标准词（供一致性"期望值"下拉，仅从词典选）
// 编辑用的表单副本：由 rules 克隆而来，保存时才组装回写服务端
const form = reactive({
  rules: null,
  elementNames: [],
  fullWeight: 12,
  partialWeight: 6,
  format: [],
  consistency: []
})
// 深拷贝：避免编辑时直接改动 rules（取消后 rules 必须保持原样）
const clone = (o) => JSON.parse(JSON.stringify(o))
// 可编辑行的稳定主键：v-for 不用下标做 key，删中间行后其余行的 DOM / 输入框不会错位。
// 只在本弹窗内唯一即可，用自增序号而非 randomUUID —— 非安全上下文（如局域网 http）下
// crypto.randomUUID 可能不存在
let uidSeq = 0
const nextUid = () => 'u' + ++uidSeq
// 取某词典类型的下拉选项（el-select-v2 的 {label,value} 结构）；未加载时返回空数组
// 每类词典一个取数器（composable 内部有模块级缓存，同一类型的多个下拉共用一份，
// 不会重复打同一接口）。模板里用 termOptionsOf(type) 取。
const TERM_OPTION_HOLDERS = {}

// 取某词典类型的候选取数器；type 为空时给一个「永不请求」的空壳，
// 避免在下拉类型还没选时就去拉「未知类型」的词典
const termOptionsOf = (type) => {
  const key = type || '__none__'
  if (!TERM_OPTION_HOLDERS[key]) {
    TERM_OPTION_HOLDERS[key] = useTermOptions(() => type || 'disease')
  }
  return TERM_OPTION_HOLDERS[key]
}

// 打开规则配置：把当前规则克隆进表单，并确保词典候选已就绪
const openRules = async () => {
  // 1. 规则尚未加载时先补拉一次，保证弹窗有内容可编辑
  if (!rules.value) {
    await loadRules()
  }
  // 2. 深拷贝一份进表单：编辑期间不动生效中的 rules，取消即可原样丢弃
  const r = clone(rules.value || {})
  // 3. 回填完整性要素：名称清单 + 完全缺失 / 未结构化两档权重
  const els = r.completeness?.elements || []
  form.elementNames = els.map((e) => e.name)
  form.fullWeight = els[0]?.weightFull ?? 12
  form.partialWeight = els[0]?.weightPartial ?? 6
  // 4. 回填格式规则，补齐字段默认值以便直接编辑
  form.format = (r.format || []).map((f) => ({
    uid: nextUid(), field: f.field, type: f.type || 'regex', expr: f.expr || '', values: f.values || [],
    label: f.label, weight: f.weight ?? 5, reason: f.reason
  }))
  // 5. 回填一致性规则，同样补齐默认值
  form.consistency = (r.consistency || []).map((c) => ({
    uid: nextUid(),
    name: c.name,
    triggerType: c.triggerType || 'pattern',
    triggerValues: c.triggerValues || [],
    expectType: c.expectType || 'herb',
    expectValues: c.expectValues || [],
    weight: c.weight ?? 10
  }))
  // 6. 回填标准化 / 重复扣分 / 分级阈值
  //    ⚠️ 这里**不许再写业务数字**：原先写成 `r.thresholds || { qualified: 90, invalid: 60,
  //    seriousFullMissing: 3 }`、`weightEach: 1, cap: 5`、`duplicateWeight ?? 5`。
  //    后端正常都会下发这些值，所以那些字面量平时是死分支；一旦真走到（后端漏字段 /
  //    版本不齐），表单会显示一套「后端没说过」的数字，用户一保存就把它们写进本组织规则 ——
  //    静默改口径。故缺失时退到本页已从后端取到的生效规则，仍无则留空让用户看见。
  form.rules = {
    standardization: r.standardization || rules.value?.standardization || null,
    duplicateWeight: r.duplicateWeight ?? rules.value?.duplicateWeight ?? null,
    thresholds: r.thresholds || rules.value?.thresholds || null
  }
  // 7. 打开弹窗，并预热词典候选（供「期望值」下拉）
  rulesVisible.value = true
  // 词典候选改为「展开下拉时按需预载」（见 useTermOptions），不再在此全量拉取
}

// 按 field 查一条格式规则
// 格式：模板勾选即用（无需写正则）；非模板项作为历史自定义规则展示
// 格式规则按 field 建索引：fmtOf 在模板里每行调 3 次，
// 原来是每次都 Array.find 一遍 form.format，行数一多就是 N×3×M。
const fmtMap = computed(() => {
  const map = new Map()
  for (const f of form.format) map.set(f.field, f)
  return map
})
const fmtOf = (field) => fmtMap.value.get(field)
// 非模板格式规则（历史遗留或手工添加）：不在 catalogFormats 目录里的项，单独列出供编辑
const customFormats = computed(() => form.format.filter((f) => !catalogFormats.value.some((t) => t.field === f.field)))
// 勾选 / 取消格式模板：勾选即按模板补一条规则，取消则移除
const toggleFormat = (t, on) => {
  if (on) {
    if (!fmtOf(t.field)) {
      form.format.push({
        uid: nextUid(),
        field: t.field, type: t.type || 'regex', expr: t.expr || '', values: clone(t.values || []),
        label: t.label, weight: t.weight ?? 5, reason: t.reason
      })
    }
  } else {
    const i = form.format.findIndex((f) => f.field === t.field)
    if (i >= 0) form.format.splice(i, 1)
  }
}
// 修改某条格式规则的扣分权重
const setFmtWeight = (field, v) => {
  const f = fmtOf(field)
  if (f) f.weight = v
}
// 删除非模板的历史自定义格式规则（入参是 customFormats 的下标，需换算回 form.format）
const removeCustomFormat = (i) => {
  const target = customFormats.value[i]
  const idx = form.format.indexOf(target)
  if (idx >= 0) form.format.splice(idx, 1)
}
// 新增一条空白的一致性规则
const addConsistency = () => {
  form.consistency.push({
    uid: nextUid(),
    name: '自定义规则', triggerType: 'pattern', triggerValues: [],
    expectType: 'herb', expectValues: [], weight: 10
  })
}

// 保存规则：把表单组装成后端结构后提交，成功后就地刷新页面上的标准与说明
const saveRules = async () => {
  // 1. 置保存态：按钮转圈，避免重复提交
  savingRules.value = true
  try {
    // 2. 组装完整性要素：按目录补齐来源与兜底别名，权重取表单统一值
    const payload = buildRulesPayload()
    // 5. 提交后端，成功后就地刷新标准与说明，无需重进页面
    const res = await updateQcRules(payload)
    applyRules(res)
    // 6. 提示并收起弹窗
    ElMessage.success('规则已保存并生效')
    rulesVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    // 无论成败都复位保存态，否则按钮会一直转圈
    savingRules.value = false
  }
}

// 组装保存用的规则 payload（P3.4 从 saveRules 抽出）
const buildRulesPayload = () => {
  const elements = form.elementNames.map((name) => {
    const preset = catalogElements.value.find((e) => e.name === name) || {}
    return {
      name,
      source: preset.source || name,
      fallback: preset.fallback || [],
      weightFull: form.fullWeight,
      weightPartial: form.partialWeight
    }
  })
  const consistency = form.consistency
    .filter((c) => (c.triggerValues || []).length && (c.expectValues || []).length)
    .map((c) => ({
      name: c.name || '自定义规则',
      triggerType: c.triggerType,
      triggerValues: c.triggerValues,
      expectType: c.expectType,
      expectValues: c.expectValues,
      weight: c.weight
    }))
  return {
    completeness: { elements },
    format: form.format.map(({ uid, ...f }) => f),
    consistency,
    standardization: clone(form.rules.standardization),
    duplicateWeight: form.rules.duplicateWeight,
    thresholds: clone(form.rules.thresholds)
  }
}

// 保存成功后就地刷新标准与说明（P3.4 从 saveRules 抽出）
const applyRules = (res) => {
  rules.value = res.data?.rules || rules.value
  descriptions.value = res.data?.descriptions || descriptions.value
  ruleWarnings.value = res.data?.warnings || []
}

// 恢复默认规则：二次确认后调后端重置
const resetRules = async () => {
  if (!(await confirmBox('确定恢复默认质控规则吗？当前自定义规则将被覆盖。', '恢复默认', { type: 'warning' }))) {
    return
  }
  try {
    // 2. 调后端恢复默认规则
    const res = await resetQcRules()
    // 3. 就地刷新规则、说明与告警
    rules.value = res.data?.rules || null
    descriptions.value = res.data?.descriptions || []
    ruleWarnings.value = res.data?.warnings || []
    // 4. 提示成功并收起弹窗
    ElMessage.success('已恢复默认规则')
    rulesVisible.value = false
  } catch {
    // 拦截器已提示
  }
}

// ===== 预检列表 / 扣分明细 =====
// 分级不再单独持有：统一由上方「范围查询」的 filters.grade 驱动，
// 否则同一页会出现两个互不相干的分级口径
// 预检列表状态
const precheckPage = ref(1)
const precheckSize = ref(10)
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

// 停掉轮询并清空句柄，防止重复启动或组件卸载后继续发请求
const stopPoll = () => {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

// 进度轮询：2s 一次（与 NlpExtract 的批量解析同频率），终态自动停
const pollTask = async (id) => {
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

// 组件卸载时停掉轮询，避免离开页面后还在发请求
onBeforeUnmount(stopPoll)

// 扣分明细改回弹窗；关闭时只收起、不清数据，避免关闭动画期间内容闪空
const detail = ref(null)
const detailVisible = ref(false)

// 扣分合计：弹窗里直接给出，省得用户在长表里自己加
// 扣分合计：两栏弹窗里直接给出，省得用户在长表里自己加
const detailTotal = computed(() =>
  (detail.value?.deductions || []).reduce((s, d) => s + (d.points || 0), 0)
)

// 打开扣分明细：按病历 ID 单独取一次评分结果
const openDetail = async (recordId) => {
  try {
    // 1. 按病历 ID 单独取一次评分结果
    const res = await qcScore({ recordId })
    // 2. 回填明细数据（分数 / 分级 / 扣分列表）
    detail.value = res.data
    // 3. 打开弹窗
    detailVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

// 只收起弹窗、不清 detail，避免关闭动画期间内容闪空
const closeDetail = () => {
  detailVisible.value = false
}

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
/* 次级说明文字 */
.tip {
  font-size: var(--fs-md);
  color: var(--text-sub);
}
/* 截断 / 告警提示条：浅黄底，与错误红区分 */
.trunc-hint {
  background: var(--ochre-surface);
  border: 1px solid #ecd9b0;
  color: var(--ochre);
  border-radius: 6px;
  padding: 6px var(--sp-3);
  font-size: var(--fs-md);
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
  color: var(--text-sub);
}
/* 弹窗内的小节标题 */
.sd-title {
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  margin: 14px 0 var(--sp-2);
}
/* 扣分明细弹窗的内容列 */
.ded-col {
  min-width: 0;
}
.ded-col .sd-title:first-child {
  margin-top: 0;
}
/* 「无扣分项」等正向文案 */
.ok {
  padding: var(--sp-2);
  color: var(--ink-mid);
  font-size: var(--fs-md);
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
  padding: 6px 0;
  border-bottom: 1px dashed var(--line);
  font-size: var(--fs-md);
}
.std-dim .sd-name {
  font-weight: bold;
  color: var(--ink);
  flex: 0 0 96px;
}
.std-dim .sd-desc {
  flex: 1 1 auto;
  color: var(--text-sub);
}
.std-dim .sd-w {
  flex: 0 0 auto;
  color: var(--ochre);
  font-weight: bold;
}
.std-grade {
  padding: 6px 10px;
  border-radius: 4px;
  font-size: var(--fs-md);
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
  padding: 6px 10px;
  margin-bottom: 6px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--ink-mid);
  border-radius: 4px;
  background: var(--paper);
  font-size: var(--fs-md);
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
  font-size: var(--fs-md);
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
  color: var(--text-sub);
}
/* 分级分布：胶囊标签 */
.grade-chips {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.grade-chips .gc {
  font-size: var(--fs-md);
  padding: 3px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--ink);
}

/* ===== 评分构成瀑布（弹窗内） ===== */
.wf {
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 6px 10px;
  margin-bottom: var(--sp-2);
  background: var(--paper);
}
/* 瀑布单行：标签 + 数值（扣分用 danger） */
.wf-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 3px 0;
  font-size: var(--fs-md);
}
.wf-item .wf-l {
  flex: 1 1 auto;
  color: var(--ink);
}
.wf-item .wf-num {
  flex: 0 0 auto;
  color: var(--ink);
}
.wf-item .wf-num.neg {
  color: var(--danger);
}
.wf-item.end {
  border-top: 1px solid var(--line);
  margin-top: var(--sp-1);
  padding-top: 6px;
}
/* P4.11：最终得分要突出扫读，字号比扣分项（默认）放大一档加粗 */
.wf-item.end .wf-num {
  font-size: 17px;
  font-weight: 700;
}
/* 最终得分旁的分级胶囊 */
.wf-grade {
  font-size: var(--fs-sm);
  padding: 1px var(--sp-2);
  border-radius: 6px;
}
.wf-grade.is-ok {
  background: var(--ink-light);
  color: var(--ink);
}
.wf-grade.is-mid {
  background: var(--ochre-light);
  color: #8a6a44;
}
.wf-grade.is-bad {
  background: var(--danger-surface);
  color: #8a3d33;
}
/* 合格线说明 */
.wf-note {
  font-size: var(--fs-sm);
  color: var(--text-sub);
  margin-bottom: 10px;
}

/* ===== 规则配置弹窗 ===== */
.hd-action {
  margin-left: var(--sp-3);
}
/* 折叠区内的单条规则说明 */
.std-desc {
  font-size: var(--fs-md);
  line-height: 1.9;
  color: var(--ink);
  padding: 2px 0;
}
/* 标准：紧凑一行一项（标签 + 值） */
.std-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
/* 一行：标签固定宽 + 值自适应 */
.st {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: var(--fs-md);
  line-height: 1.8;
}
.st-k {
  flex: 0 0 110px;
  color: var(--text-sub);
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
  font-size: var(--fs-sm);
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
/* 格式模板勾选 */
.rc-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
/* 格式模板胶囊：勾选态（.on）加深边框与底色 */
.rc-fmt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--sp-1) var(--sp-3);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: var(--fs-md);
  color: var(--text-sub);
}
.rc-fmt.on {
  border-color: var(--ink-mid);
  background: var(--ink-light);
  color: var(--ink);
}
.rc-fmt-l {
  font-weight: bold;
}
/* 规则配置弹窗主体：超 72vh 内部滚动，页脚按钮始终可见 */
.rc {
  max-height: 72vh;
  overflow-y: auto;
}
/* 弹窗顶部说明 */
.rc-tip {
  font-size: var(--fs-sm);
  color: var(--text-sub);
  margin-bottom: 6px;
}
/* 句子式编辑行：文字与控件同行排布，窄屏换行 */
.rc-line {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin: var(--sp-2) 0;
  font-size: var(--fs-md);
  color: var(--ink);
  line-height: 2;
}
/* 一致性规则卡片 */
.rc-block {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px var(--sp-3);
  margin-bottom: var(--sp-2);
  background: var(--paper);
}
/* ①②③④ 分段标题 */
.rc-hd {
  margin: var(--sp-4) 0 var(--sp-2);
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: var(--sp-2);
}
/* 预留的行式布局：当前模板用的是 .rc-line，本类暂无引用 */
.rc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
  font-size: var(--fs-md);
  color: var(--ink);
}
</style>
