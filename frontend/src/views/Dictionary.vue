<template>
  <!-- 词典管理页：按术语类型（疾病 / 证候 / 症状 / 中药 / 方剂）查看与维护。
       页签：小组基线（只读）/ 我的词典（本地副本）/ 提案审核 / 归档版本。
       批量导入已剥离为独立页「术语批量导入」——普通成员走那里生成提案，
       管理员在那里还能选择直接生效。 -->
  <div>
    <!-- 页头：一句话交代四个页签各自管什么，避免新用户对着名词猜。
         页签划分依据是「谁能改」：基线=只读现状；我的词典=你自己的副本；
         提案审核=把改动交给组长；归档版本=历史与回滚。 -->
    <div class="dict-guide">
      <span class="dg-item"><b>小组基线</b>组织当前生效的词典（只读）</span>
      <span class="dg-item"><b>我的词典</b>你自己的副本，改完提交提案</span>
      <span class="dg-item"><b>提案审核</b>组长在此确认改动并合并</span>
      <span class="dg-item"><b>归档版本</b>历史快照，可回滚</span>
    </div>

    <!-- 术语类型筛选器。类型是**全局过滤**（切类型后四个页签的数据都跟着换），
         不是页面导航 —— 所以用 radio-group 而不是页签。 -->
    <div class="dict-head">
      <span class="dh-label">术语类型</span>
      <el-radio-group v-model="typeKey" size="small" aria-label="术语类型">
        <el-radio-button v-for="t in TYPE_OPTIONS" :key="t.value" :value="t.value">
          {{ t.label }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <!-- 术语查询：按当前类型 + 关键字模糊匹配（标准词与别名都参与匹配） -->
    <el-tabs v-model="tab" class="dict-tabs">

    <el-tab-pane label="小组基线" name="baseline">
    <PanelCard :title="`小组基线（${typeLabel(typeKey.value)}）`">
      <div class="search-row">
        <!-- 只给 placeholder 的搜索框没有无障碍名称，补 aria-label
             （Chrome 的「No label associated with a form field」检查不认 placeholder） -->
        <el-input
          v-model="keyword"
          aria-label="术语查询"
          placeholder="输入术语或别名关键字，模糊匹配"
          clearable
          style="width: 320px"
          @keyup.enter="loadTerms()"
        />
        <el-button type="primary" :loading="loadingTerms" @click="loadTerms()">查 询</el-button>
        <span class="tip">共 {{ total }} 条</span>
        <!-- 批次 26.17：零信息列默认隐藏，需要时在这里勾回来 -->
        <el-popover placement="bottom-end" :width="180" trigger="click">
          <template #reference>
            <el-button size="small" plain>列显示（{{ visibleCols.length }}/{{ OPTIONAL_COLS.length }}）</el-button>
          </template>
          <div class="col-picker">
            <el-checkbox-group v-model="visibleCols" @change="colTouched = true">
              <el-checkbox v-for="c in OPTIONAL_COLS" :key="c.prop" :value="c.prop">{{ c.label }}</el-checkbox>
            </el-checkbox-group>
            <div class="col-picker-actions">
              <el-button size="small" @click="resetCols">恢复默认</el-button>
            </div>
          </div>
        </el-popover>
      </div>
      <!-- 作用域提示：词典已按组织隔离（批次8b）。这一条不是装饰 —— 组织 A 导入的词
           只在 A 的归一里生效，管理员在此看到「基础层」时不能以为那就是全量生效词典 -->
      <div class="scope-hint">
        <el-tag size="small" :type="scopeTagType" effect="plain">{{ scopeLabel }}</el-tag>
        <span class="tip">{{ scopeTip }}</span>
      </div>
      <!-- 高度随分页大小联动（表头 40 + 每行 40 × 当前页大小 + 余量 8）：
           固定高度的目的是「选了多少条/页就能看到多少行」——当前页整页铺开，不再有隐藏行。
           原公式按 32px 估算，而本表未加 size="small"（表头与行高实测均为 40px），
           于是 20 条/页只放得下 16 行、表格内部仍滚 160px（M23 复测 2026-10-06 未过）。
           每页条数可调（20~200），行数多时由页面自身滚动 -->
<el-table v-loading="loadingTerms" element-loading-text="正在查询术语…" :data="terms" border stripe style="margin-top: var(--sp-3)" :max-height="termsTableHeight">
          <!-- 空态解释「为什么空、怎么才有内容」：走下方 #empty 插槽；:empty-text 是死代码已删 -->
          <el-table-column prop="standardTerm" label="标准术语" width="220" />
        <el-table-column v-if="visibleCols.includes('aliases')" label="别名">
          <template #default="{ row }">
            <el-tag
              v-for="a in row.aliases"
              :key="a"
              size="small"
              effect="plain"
              style="margin-right: 6px"
            >{{ a }}</el-tag>
            <!-- 空值与「国标编码」列统一写 —（原「无」），空列语义只有一种 -->
            <span v-if="!row.aliases?.length" class="tip">—</span>
          </template>
        </el-table-column>
        <!-- 批次 22：显示国标编码。没有编码的词条显式写「—」而不是留空，
             免得「空白」被误读成「这一列没加载出来」 -->
        <el-table-column v-if="visibleCols.includes('code')" prop="code" width="150">
          <template #header>
            <span>国标编码
              <el-tooltip content="国标编码待补：多数词条暂无对应的国标编码，故该列常为空" placement="top">
                <span class="hdr-info" tabindex="0" aria-label="国标编码说明">ⓘ</span>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <span v-if="row.code" class="code-cell">{{ row.code }}</span>
            <span v-else class="tip">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <!-- P5.8：空态必须解释「为什么空 / 怎么才有内容」；加载失败与真为空分开 -->
          <EmptyState
            :failed="termsFailed"
            :text="keyword ? `没有匹配「${keyword}」的术语：换个更短的关键词，或确认该类型已导入过词条` : '该词典暂无术语：使用「术语库导入」上传词典后可在此检索'"
            @retry="loadTerms(true)"
          />
        </template>
      </el-table>
      <!-- 分页：词典已从演示的十几条涨到上千条（如疾病 1357、证候 2080），
           一次全量渲染会卡且无法定位，故按页浏览 -->
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="PAGE_SIZES_WIDE"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: var(--sp-3); justify-content: flex-end"
        @current-change="loadTerms(false)"
        @size-change="handleSizeChange"
      />
    </PanelCard>

    <!-- 术语库导入：Excel / CSV / JSON 覆盖入库（导入前自动备份） -->

    <!-- ============================================================ 批次17：提案 + 归档 -->
    <!-- 原「版本回滚」面板已移除：dictionary_backups 表废弃，回滚改为
         「基于归档版本生成提案 → 组长审核」，历史列表改为「归档版本」。 -->

    <!-- 个人词典：拉取小组基线存本地，可在本地编辑后提交提案 -->
    </el-tab-pane>

    <el-tab-pane label="我的词典" name="mine">
    <PanelCard title="个人词典（本地）">
      <div class="tip" style="margin-bottom: var(--sp-2)">
        这是你自己的词典副本：可手动增删，也可<b>批量导入文件</b>或从小组基线拉取。
        它只存在这台电脑。
        <template v-if="isOwner">
          你是本组组长，改完点「提交更新提案」，再到「提案审核」点「通过」即合并入小组基线。
        </template>
        <template v-else>
          改动要生效必须提交提案、由组长审核通过。
        </template>
      </div>
      <div class="rv-row">
        <el-button size="small" :loading="baselineLoading" @click="loadBaseline">
          拉取小组基线
        </el-button>
        <el-button size="small" @click="$router.push('/dictionary/import')">
          批量导入文件
        </el-button>
        <el-button
          size="small"
          type="primary"
          :loading="submittingProposal"
          :disabled="!localTerms.length"
          @click="doSubmitProposal"
        >提交更新提案</el-button>
      </div>
      <div v-if="localLoadedAt" class="rv-meta">
        已拉取 {{ localTerms.length }} 条 · {{ localLoadedAt }} ·
        <span v-if="baselineTouched" class="rv-dirty">本地有未提交的改动</span>
        <span v-else>与基线一致</span>
      </div>
      <el-table :data="localPaged" border size="small" max-height="260" style="margin-top: var(--sp-3)"
        :empty-text="localTerms.length ? '' : '先点「拉取小组基线」把当前组织的词典下载到本地'">
        <el-table-column prop="standardTerm" label="标准词" min-width="160" />
        <el-table-column label="别名" min-width="200">
          <template #default="{ row }">{{ (row.aliases || []).join('、') }}</template>
        </el-table-column>
        <el-table-column prop="source" label="来源" min-width="120" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeLocalTerm(row.standardTerm)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="localTerms.length > LOCAL_PAGE_SIZE" class="rv-pager">
        <el-button size="small" :disabled="localPage <= 1" @click="localPage--">上一页</el-button>
        <span class="tip">{{ localPage }} / {{ localPageCount }}</span>
        <el-button size="small" :disabled="localPage >= localPageCount" @click="localPage++">
          下一页
        </el-button>
      </div>
      <div class="rv-add">
        <el-input v-model="newTerm" placeholder="新增标准词" style="width: 160px" size="small" />
        <el-button size="small" :disabled="!newTerm.trim()" @click="addLocalTerm">加入本地</el-button>
        <span class="tip">加入本地后同样需要提交提案才会进入小组基线</span>
      </div>
    </PanelCard>

    <!-- 提案列表 + 差异预览 + 审核 -->
    </el-tab-pane>

    <el-tab-pane label="提案审核" name="proposals">
      <DictionaryProposalReview
        ref="proposalRef"
        :type-key="typeKey"
        :is-owner="isOwner"
        :archive-full="archiveFull"
        :type-label="typeLabel"
        @audited="loadArchives"
      />
    </el-tab-pane>

    <el-tab-pane label="归档版本" name="archives">
    <PanelCard title="归档版本">
      <div class="rv-row">
        <el-button size="small" :loading="archivesLoading" @click="loadArchives">刷新归档</el-button>
        <span class="tip">
          每次基线合并后自动留一份快照；每个类型最多保留最近 {{ MAX_SNAPSHOTS }} 份快照，版本元信息永久保留。
          <template v-if="archives.length">
            当前可用快照 <b>{{ snapshotCount }}</b> / {{ MAX_SNAPSHOTS }} 份<template v-if="archiveFull">，已达上限</template>。
          </template>
        </span>
      </div>
      <el-table :data="archives" border size="small" max-height="220" style="margin-top: var(--sp-3)"
        empty-text="暂无归档版本。基线第一次变更后会自动生成">
        <el-table-column prop="versionNo" label="版本" width="80" />
        <el-table-column label="词条数" width="90">
          <template #default="{ row }">{{ row.termCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="合并时间" min-width="160">
          <template #default="{ row }">{{ fmtTime(row.mergeTime) }}</template>
        </el-table-column>
        <el-table-column prop="mergeUserId" label="合并人" width="120" />
        <el-table-column prop="comment" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column label="快照" width="100">
          <template #default="{ row }">
            <el-tooltip v-if="willBePurged(row)" content="快照已达 5 份上限，下一次基线合并会清理这一份（版本元信息保留）">
              <el-tag size="small" type="warning" effect="plain">将被清理</el-tag>
            </el-tooltip>
            <el-tag v-else-if="row.snapshotPresent" size="small" type="success" effect="plain">可用</el-tag>
            <el-tooltip v-else content="快照已被 5 份限额清理，仅保留版本元信息，无法用于回滚">
              <el-tag size="small" type="info" effect="plain">已清理</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column v-if="isOwner" label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="warning" size="small" :disabled="!row.snapshotPresent"
              @click="doRollback(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PanelCard>
    </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
// 词典管理页：类型切换会同时刷新「术语查询」与「版本回滚」两块数据。
// 导入只有一条路径 —— Excel / CSV / JSON 覆盖入库（导入前自动备份）。
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PanelCard from '@/components/PanelCard.vue'
import DictionaryProposalReview from '@/components/DictionaryProposalReview.vue'
import {
  getTerms, exportBaseline, submitProposal, listArchives, rollbackArchive
} from '@/api/dictionary'
import { confirmBox } from '@/utils/confirm'
import { useUserStore } from '@/stores/user'
import { PAGE_SIZES_WIDE } from '@/utils/constants'

const userStore = useUserStore()

// 提案审核子组件：提交提案/类型切换后需要它刷新列表
const proposalRef = ref(null)


/**
 * 术语类型的中文标签（与后端 EntityTypes.dictKeys() 同源）。
 *
 * <p><b>必须声明在 TYPE_OPTIONS 之前</b>：<code>const</code> 是块级作用域且有 TDZ，
 * 上层在初始化时读到下层的 const 会抛
 * {@code ReferenceError: Cannot access 'X' before initialization}，
 * 表现为整个组件 setup 失败、页面白屏 —— 而 {@code vite build} 不会报错。
 * 这就是批次 12 工作项 8「TDZ 调序」要防的那类问题。</p>
 */
// 术语类型的显示名；键名必须与后端 type 参数一致（EntityTypes 的 key）。
// 批次 20 起新增舌象/脉象/治法三类 —— 后端已给这三类建词典，此处同步暴露入口。
const TYPE_LABELS = {
  disease: '疾病',
  pattern: '证候',
  symptom: '症状',
  herb: '中药',
  formula: '方剂',
  tongue: '舌象',
  pulse: '脉象',
  treatment: '治法'
}

/** 术语类型选项（页头 radio-group 的数据源） */

// 术语词典写入入口（导入）：管理员 / 所有者 / 被授权成员三档；
// 只读浏览对所有登录用户开放。后端按同一三档校验（批次 6 落地授权位），前端只负责不展示无效入口。
const TYPE_OPTIONS = Object.keys(TYPE_LABELS).map((v) => ({ value: v, label: TYPE_LABELS[v] }))
const canWrite = computed(() => userStore.canWriteDictionaryEntry)

// 词典作用域（批次8b）：后端按「本组织自有词条 → 无则回退基础层」返回，
// 前端不额外判断层级，只把「看的是谁的词典」讲清楚，避免误以为改的是全局词典。
const scopeLabel = computed(() =>
  userStore.orgId ? `组织词典：${userStore.orgId}` : '基础层词典（全局共享）'
)
const scopeTagType = computed(() => (userStore.orgId ? 'primary' : 'info'))
const scopeTip = computed(() =>
  userStore.orgId
    ? '本组织没有自有词条时自动回退基础层；你导入的词只在本组织的解析与归一中生效'
    : '你当前不在任何组织中，看到并编辑的是全组织共享的基础层词典'
)

// 词典类型 → 界面文案；键名与后端 type 参数一致（disease / pattern / symptom / herb / formula）

// ===== 布局：与其它页一致，不做整页缩放（表格内部滚动）=====
/** 当前术语类型（页头 radio-group 的值，全局过滤） */
const typeKey = ref('herb')
/** 当前任务页签：baseline 小组基线 / mine 我的词典 / proposals 提案审核 / archives 归档版本 */
const tab = ref('baseline')
// 当前词典类型的中文名，用于面板标题、确认文案与导入提示
const typeLabel = (v) => (TYPE_LABELS[v] || v)

// 术语查询状态：keyword 为用户输入，terms 为当前页结果
const keyword = ref('')
const terms = ref([])
const loadingTerms = ref(false)
// 词条查询失败：与「确实没有匹配」区分开
const termsFailed = ref(false)
// 分页：page 从 1 起，size 为每页条数，total 为命中总数（驱动 el-pagination 算总页数）
const page = ref(1)
const size = ref(20)

// M23：术语表高度跟随分页大小（表头 40 + 行高 40 × 当前页 + 余量 8）。
// 行高取本表实际渲染值（该表未加 size="small"）；按 32 估算会让 20 条/页藏 4 行、内滚 160px。
// 两个加数与 size 同源，任何一个写小都会让 max-height 矮于内容，滚动条就又回来了。
const termsTableHeight = computed(() => 40 + (size.value || 10) * 40 + 8)
const total = ref(0)

// 批次 26.17：基线表「别名」「国标编码」两列在当前页常常一条数据都没有，
// 却合计占掉约 40% 横向宽度（零信息量）。这里把两列做成可显隐：
// 默认只显示「当前页确实有数据」的列；用户手动勾选后就不再自动重算（colTouched），
// 免得翻页时列自己跳来跳去。
const OPTIONAL_COLS = [
  { prop: 'aliases', label: '别名' },
  { prop: 'code', label: '国标编码' }
]
const colTouched = ref(false)
const visibleCols = ref([])
const hasAliasData = computed(() => terms.value.some((t) => t.aliases?.length > 0))
const hasCodeData = computed(() => terms.value.some((t) => t.code))
const colsWithData = () => {
  const cols = []
  if (hasAliasData.value) cols.push('aliases')
  if (hasCodeData.value) cols.push('code')
  return cols
}
// 数据变化（查询 / 翻页 / 换类型）后，未手动干预过就按「有数据才显示」重算
watch([terms, typeKey], () => {
  if (!colTouched.value) visibleCols.value = colsWithData()
})
const resetCols = () => {
  colTouched.value = false
  visibleCols.value = colsWithData()
}

// 查询当前类型下的术语（关键字命中标准词或别名）
// resetPage：切类型 / 搜索 / 导入后 / 回滚后都应回到第 1 页（数据集合已变），
// 只有「翻页」「改每页条数」这两个纯翻页动作才传 false。
const loadTerms = async (resetPage = true) => {
  // 1. 置加载态，并清掉上一次的失败标记；需要重置时先回到第 1 页
  if (resetPage) page.value = 1
  loadingTerms.value = true
  termsFailed.value = false
  try {
    // 2. 按当前词典类型 + 关键字查询（标准词与别名都参与匹配）
    //    page 与 size 永远成对传：后端 page>0 时按 size 切片，漏传 size 会让
    //    后端用默认值 100，与前端 el-pagination 显示的每页条数对不上。
    const res = await getTerms({
      type: typeKey.value,
      keyword: keyword.value,
      page: page.value,
      size: size.value
    })
    // 3. 回填当前页结果与命中总数
    terms.value = res.data.terms || []
    total.value = res.data.total ?? terms.value.length
  } catch {
    // 原来只有 try/finally：接口挂了列表还停在上一次的结果，用户会把旧数据当最新
    // 失败置失败态并清空列表，避免旧结果被当成最新
    termsFailed.value = true
    terms.value = []
    total.value = 0
  } finally {
    loadingTerms.value = false
  }
}

// 每页条数变化：回到第 1 页再查，否则会停在一个已越界的旧页码上
const handleSizeChange = () => {
  page.value = 1
  loadTerms(false)
}

// 切换词典类型：先清掉上一次的查询与导入状态，再拉新类型的数据
watch(typeKey, () => {
  // 切术语类型：本地词典按类型分开存，切回来要恢复；提案/归档同理
  restoreLocal()
  localPage.value = 1
  loadArchives()
  keyword.value = ''
  loadTerms()
})


// ================================================================ 批次17：个人词典 / 提案 / 归档

const isOwner = computed(() => userStore.orgRole === 'owner')

// ---- 个人词典（本地 localStorage，与小组基线解耦）----
const LOCAL_KEY = (org, type) => `dict.local.${org || 'base'}.${type}`
const LOCAL_PAGE_SIZE = 20

const localTerms = ref([])
const localLoadedAt = ref('')
const baselineTouched = ref(false)
const baselineLoading = ref(false)
const submittingProposal = ref(false)
const newTerm = ref('')
const localPage = ref(1)

const localPageCount = computed(() =>
  Math.max(1, Math.ceil(localTerms.value.length / LOCAL_PAGE_SIZE)))
const localPaged = computed(() => {
  const from = (localPage.value - 1) * LOCAL_PAGE_SIZE
  return localTerms.value.slice(from, from + LOCAL_PAGE_SIZE)
})

const localKey = computed(() => LOCAL_KEY(userStore.orgId, typeKey.value))

function saveLocal() {
  try {
    localStorage.setItem(localKey.value, JSON.stringify({
      at: new Date().toLocaleString(),
      terms: localTerms.value
    }))
  } catch (e) {
    // localStorage 满 / 隐私模式：给出提示，不静默丢数据
    ElMessage.warning('本地词典保存失败（浏览器存储不可用或已满），本次修改不会保留')
  }
}

/** 拉取小组基线到本地（覆盖本地已有内容） */
const loadBaseline = async () => {
  baselineLoading.value = true
  try {
    const res = await exportBaseline({ type: typeKey.value })
    const raw = localStorage.getItem(localKey.value)
    let at = ''
    if (raw) {
      try {
        at = (JSON.parse(raw) || {}).at || ''
      } catch (e) {
        at = ''
      }
    }
    localTerms.value = (res.data || []).map((t) => ({
      standardTerm: t.standardTerm,
      aliases: t.aliases || [],
      source: t.source || ''
    }))
    localLoadedAt.value = at || new Date().toLocaleString()
    baselineTouched.value = false
    saveLocal()
    ElMessage.success(`已拉取 ${localTerms.value.length} 条到本地个人词典`)
  } catch {
    // 拦截器已提示
  } finally {
    baselineLoading.value = false
  }
}

/** 从本地存储恢复上次编辑（切页签回来时用），没有就空着 */
function restoreLocal() {
  const raw = localStorage.getItem(localKey.value)
  if (!raw) {
    localTerms.value = []
    localLoadedAt.value = ''
    baselineTouched.value = false
    return
  }
  try {
    const obj = JSON.parse(raw) || {}
    localTerms.value = Array.isArray(obj.terms) ? obj.terms : []
    localLoadedAt.value = obj.at || ''
  } catch (e) {
    localTerms.value = []
    localLoadedAt.value = ''
  }
}

const addLocalTerm = () => {
  const t = newTerm.value.trim()
  if (!t) return
  if (localTerms.value.some((x) => x.standardTerm === t)) {
    ElMessage.warning('本地词典里已有该标准词')
    return
  }
  localTerms.value.push({ standardTerm: t, aliases: [], source: '本地新增' })
  baselineTouched.value = true
  newTerm.value = ''
  saveLocal()
}

const removeLocalTerm = (std) => {
  localTerms.value = localTerms.value.filter((x) => x.standardTerm !== std)
  baselineTouched.value = true
  saveLocal()
}

/** 提交提案：带上完整目标词典 */
const doSubmitProposal = async () => {
  // 1. 先取该类型的基线全量条数（不带关键字过滤）——不能直接用列表上的 total：
  //    它受页头关键词过滤影响，有过滤时会把它当成全量基线，删除条数就报错了
  let baselineTotal = null
  try {
    const r = await getTerms({ type: typeKey.value, page: 1, size: 1 })
    baselineTotal = r.data?.total ?? null
  } catch {
    // 取不到就不硬算：确认框退化成「只报提交条数」，不让一次统计失败挡住提交
  }
  // 2. 条数对比 + 二次确认：合并是整快照替换，缩水意味着要删基线里的词条，
  //    必须在提交前把「会删多少」说清楚（缩水超 20% 后端还会直接拒绝）
  const nextCount = localTerms.value.length
  const shrink = baselineTotal == null ? 0 : baselineTotal - nextCount
  const head = baselineTotal == null
    ? `将提交 ${nextCount} 条词条作为新的基线快照。`
    : `基线将由 ${baselineTotal} 条变为 ${nextCount} 条`
      + (shrink > 0 ? `（净删除 ${shrink} 条）` : '') + '。'
  const tail = shrink > 0
    ? '整快照替换会删掉旧基线里未出现在本次快照中的词条（缩水超过 20% 后端会直接拒绝）。确定提交？'
    : '通过后整快照替换当前基线并生成归档版本。确定提交？'
  if (!(await confirmBox(head + tail, '提交基线更新提案', {
    type: shrink > 0 ? 'warning' : 'info',
    confirmButtonText: '提交提案', cancelButtonText: '取消'
  }))) {
    return
  }
  submittingProposal.value = true
  try {
    const res = await submitProposal({
      type: typeKey.value,
      terms: localTerms.value.map((t) => ({
        standardTerm: t.standardTerm,
        aliases: t.aliases || [],
        source: t.source || ''
      }))
    })
    ElMessage.success(res.msg || '提案已提交，等待组长审核')
    baselineTouched.value = false
    proposalRef.value?.refresh()
  } catch {
    // 拦截器已提示（含「已有 5 条待审提案」这类业务提示）
  } finally {
    submittingProposal.value = false
  }
}

// ---- 归档版本 ----
const archives = ref([])
const archivesLoading = ref(false)
// 与后端 DictArchiveServiceImpl.MAX_SNAPSHOT_VERSIONS 对齐：超过即清理「最早且有快照」的那一份
const MAX_SNAPSHOTS = 5
const snapshotCount = computed(() => archives.value.filter((a) => a.snapshotPresent).length)
const archiveFull = computed(() => snapshotCount.value >= MAX_SNAPSHOTS)
// 下一次合并会清理的最老快照（only meaningful when archiveFull）
const oldestSnapshotVersionNo = computed(() => archives.value
  .filter((a) => a.snapshotPresent)
  .reduce((min, a) => (min === null || Number(a.versionNo) < Number(min) ? a.versionNo : min), null))
const willBePurged = (row) =>
  archiveFull.value && row.snapshotPresent && row.versionNo === oldestSnapshotVersionNo.value
const loadArchives = async () => {
  archivesLoading.value = true
  try {
    const res = await listArchives({ type: typeKey.value })
    archives.value = res.data || []
  } catch {
    archives.value = []
  } finally {
    archivesLoading.value = false
  }
}

/** 回滚：生成新提案，仍需审核后才会真正合并 */
const doRollback = async (row) => {
  if (!(await confirmBox(
    `基于归档 v${row.versionNo} 生成一份回滚提案？回滚同样需要审核通过后才会生效，且会生成新的归档版本。`,
    '版本回滚', { type: 'warning' }))) {
    return
  }
  try {
    const res = await rollbackArchive(row.versionNo, typeKey.value)
    ElMessage.success(res.msg || '已生成回滚提案，请审核')
    proposalRef.value?.focusPending()
  } catch {
    // 拦截器已提示（含「快照已被清理，无法回滚」）
  }
}

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

onMounted(() => {
  restoreLocal()
  loadArchives()
  loadTerms()
})
</script>

<style scoped>
/* 批次 22：国标编码用等宽字体，便于逐字符核对（编码错一位就查不出来了） */
.code-cell {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: var(--fs-xs);
  color: var(--text-sub);
}
/* 类型 tab：激活态与下划线改用主题墨色，替换 Element Plus 默认蓝 */
.dict-tabs {
  margin-bottom: var(--sp-1);
}
.dict-tabs :deep(.el-tabs__item.is-active) {
  color: var(--ink);
}
.dict-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--ink-mid);
}
/* 查询行 / 回滚行：单行水平排布 */
.search-row,
/* 「较当前」的增减配色：多=ochre、少=danger、无变化=次级色 */
.dl-up { color: var(--ochre); }
.dl-down { color: var(--danger); }
.dl-flat { color: var(--text-sub); }
/* 批次 26.17：基线表「列显示」选择器（照 Governance 的列选择器样式） */
.col-picker :deep(.el-checkbox-group) {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.col-picker :deep(.el-checkbox) { margin-right: 0; }
.col-picker-actions {
  margin-top: var(--sp-2);
  padding-top: var(--sp-2);
  border-top: 1px solid var(--line-soft);
  text-align: right;
}
/* 列头 ⓘ：可聚焦，键盘用户也能读出说明 */
.hdr-info {
  color: var(--text-sub);
  cursor: help;
  font-size: var(--fs-xs);
}

/* 词典作用域提示条：与查询区同一行基线，标签 + 说明一行排开 */
.scope-hint {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}
/* 导入区：左上传拖拽框、右操作列 */
.import-row {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}
/* 拖拽区内的主提示与副说明 */
.upload-tip {
  font-size: var(--fs-base);
  color: var(--text);
}
.upload-tip .sub {
  font-size: var(--fs-xs);
  color: var(--text-sub);
  margin-top: var(--sp-1);
}
/* 详细格式收进折叠说明，避免一上来把数据结构摊给用户；
   位置在 import-actions 内，不再落在上传热区*/
.fmt-detail {
  margin-top: 10px;
  font-size: var(--fs-xs);
  color: var(--text-sub);
}
.fmt-detail summary {
  display: inline-block;
  cursor: pointer;
  color: var(--ink-mid);
  list-style: none;
}
.fmt-detail summary::-webkit-details-marker {
  display: none;
}
.fmt-detail summary::before {
  content: '▸ ';
}
.fmt-detail[open] summary::before {
  content: '▾ ';
}
.fmt-body {
  margin-top: 6px;
  padding: var(--sp-2) var(--sp-3);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  text-align: left;
  line-height: 1.8;
}
/* 右侧操作列：占满剩余宽度 */
.import-actions {
  flex: 1;
}
/* 导入结果行：三张数字卡 + 失败明细并排 */
.import-result {
  display: flex;
  gap: var(--sp-3);
  margin-top: 14px;
  align-items: flex-start;
}
/* 失败明细占满剩余宽度 */
.failures {
  flex: 1;
}
/* 失败明细小标题 */
.ded-hd {
  font-size: var(--fs-base);
  color: var(--text-sub);
  margin-bottom: 6px;
}
/* 单条失败项：浅 ochre 底条，与正文区分 */
.ded-item {
  background: var(--ochre-light);
  border-radius: 2px;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: 6px;
  font-size: var(--fs-xs);
}

/* ===== 批次17：个人词典 / 提案 / 归档 ===== */
.rv-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.rv-meta {
  margin-top: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--text-sub);
}
.rv-dirty {
  color: var(--ochre);
  margin-left: var(--sp-2);
}
.rv-pager {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}
.rv-add {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
  flex-wrap: wrap;
}

/* ===== 页头：术语类型筛选器 ===== */
/* 一句话交代四个页签管什么：对���名词页签，新用户只能靠猜 */
.dict-guide {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2) var(--sp-4);
  margin-bottom: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  background: var(--surface-sub);
  border-radius: 4px;
  font-size: var(--fs-xs);
  color: var(--text-sub);
}
.dg-item b {
  color: var(--ink);
  margin-right: 2px;
}
.dict-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}
.dh-label {
  font-size: var(--fs-xs);
  color: var(--text-sub);
}

</style>