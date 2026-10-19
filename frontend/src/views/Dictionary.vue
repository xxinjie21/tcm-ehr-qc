<template>
  <!-- 词典管理页：按术语类型（疾病 / 证候 / 症状 / 中药 / 方剂）查看与维护。
       页签：小组基线（只读）/ 提案审核 / 归档版本。
       批量导入与「我的词典」均已剥离为独立页——普通成员走导入页生成提案，
       管理员在那里还能选择直接生效；「我的词典」入口在页头引导条行末。 -->
  <div class="dict-page">
    <!-- 页头：一句话交代三个页签各自管什么，避免新用户对着名词猜。
         页签划分依据是「谁能改」：词典查询=只读浏览（可切三层）；
         提案审核=把改动交给组长；归档版本=历史与回滚。
         「我的词典」已摘出为独立页，入口是行末那颗按键（统一入口，
         不在每个术语类型下各放一个）。 -->
    <div class="dict-guide">
      <span class="dg-item"><b>词典查询</b>只读浏览：系统默认 / 组内 / 本地三层可切</span>
      <span class="dg-item"><b>提案审核</b>组长在此确认改动并合并</span>
      <span class="dg-item"><b>归档版本</b>历史快照，可回滚</span>
      <!-- 词典范围与「我的词典」是全站唯一入口，就在这一行末尾：点开可对 9 类术语词典各选一层。
           按钮文字带当前层名，所以它同时是状态显示，页面上不再另摆一排 radio。 -->
      <el-button class="dg-action" size="small" plain
        @click="$router.push({ path: '/dictionary/mine', query: { type: typeKey } })">
        我的词典
      </el-button>
      <el-button class="dg-action" size="small" plain @click="openLayerDialog">
        词典范围：{{ scopeLabel }}
      </el-button>
    </div>

    <!-- 术语类型筛选器。类型是**全局过滤**（切类型后三个页签的数据都跟着换），
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

    <el-tab-pane label="词典查询" name="baseline">
    <PanelCard :title="`词典查询（${typeLabel(typeKey)}）`" class="q-card">
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
      </div>
      <!-- 高度随分页大小联动（表头 40 + 每行 40 × 当前页大小 + 余量 8）：
           固定高度的目的是「选了多少条/页就能看到多少行」——当前页整页铺开，不再有隐藏行。
           原公式按 32px 估算，而本表未加 size="small"（表头与行高实测均为 40px），
           于是 20 条/页只放得下 16 行、表格内部仍滚 160px（M23 复测 2026-10-06 未过）。
           每页条数可调（20~200），行数多时由页面自身滚动 -->
        <!-- P0-3：去掉 v-loading 遮罩（它把表头与上一页术语一起盖住了）。
             加载中由下方 #empty 的 EmptyState 渲染骨架屏，表头保持可见 -->
      <!-- 等高链：术语表吃满卡内剩余高度（原 :max-height 随每页条数硬算，
           选少了卡底仍空一块）。height="100%"，当前页整铺开、行多时表体内滚 -->
      <div class="fill-table">
        <el-table :data="terms" border stripe height="100%">
          <!-- 空态解释「为什么空、怎么才有内容」：走下方 #empty 插槽；:empty-text 是死代码已删 -->
          <el-table-column prop="standardTerm" label="标准术语" min-width="240" />
        <el-table-column label="别名">
          <template #default="{ row }">
            <el-tag
              v-for="a in row.aliases"
              :key="a"
              size="small"
              effect="plain"
              style="margin-right: 6px"
            >{{ a }}</el-tag>
            <!-- 空值与「别名」列统一写 —（原「无」），空列语义只有一种 -->
            <span v-if="!row.aliases?.length" class="tip">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <!-- P5.8：空态必须解释「为什么空 / 怎么才有内容」；加载失败与真为空分开 -->
          <EmptyState
            :failed="termsFailed"
            :loading="loadingTerms"
            loading-text="正在查询术语…"
            :text="emptyText"
            @retry="loadTerms(true)"
          />
        </template>
      </el-table>
      </div>
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
    </el-tab-pane>

    <!-- 提案列表 + 差异预览 + 审核 -->

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
    <PanelCard title="归档版本" class="arch-card">
      <div class="rv-row">
        <el-button size="small" :loading="archivesLoading" @click="loadArchives">刷新归档</el-button>
        <span class="tip">
          每次基线合并后自动留一份快照；每个类型最多保留最近 {{ MAX_SNAPSHOTS }} 份快照，版本元信息永久保留。
          <template v-if="archives.length">
            当前可用快照 <b>{{ snapshotCount }}</b> / {{ MAX_SNAPSHOTS }} 份<template v-if="archiveFull">，已达上限</template>。
          </template>
        </span>
      </div>
      <div class="fill-table">
        <el-table :data="archives" border size="small" height="100%"
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
      </div>
    </PanelCard>
    </el-tab-pane>
    </el-tabs>

    <!-- 词典范围 —— 全站唯一入口（页头引导条那一行的那颗按钮）。
         弹窗里对 9 类术语词典**逐个**选层，每类各记各的（scopeByType），互不影响。 -->
    <el-dialog
      v-model="layerDialog"
      class="layer-dialog"
      title="词典范围与归一"
      width="min(1020px, 96vw)"
      top="6vh"
    >
      <!-- 一、先回答用户真正的问题：归一到底拿的是哪份 -->
      <div class="ld-answer">
        归一与输入联想实际使用的是 <b>系统默认词典 ∪ 组内词典</b>，同一个标准词两边都有时
        <b>以组内那条为准</b>。这里选的只是「页面上看哪一层」，<b>不会改变归一结果</b>。
      </div>
      <div class="ld-note">
        <b>本地词典不参与归一</b>：它只存在这台电脑上，是给「我的词典」编辑、
        提交提案用的工作副本；提交提案并经组长审核后，才会进入组内词典。
      </div>

      <!-- 二、9 类逐个调控。每一行的三档是独立的：把「症状」切到组内，不影响「中药」 -->
      <el-table :data="layerRows" border size="small" class="ld-table">
        <el-table-column label="术语词典" min-width="110">
          <template #default="{ row }">
            <span>{{ row.label }}</span>
            <el-tag v-if="row.value === typeKey" size="small" effect="plain" class="ld-cur">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="查看哪一层" min-width="300">
          <template #default="{ row }">
            <el-radio-group v-model="scopeByType[row.value]" size="small" :aria-label="`${row.label}词典范围`">
              <el-radio-button
                v-for="s in SCOPE_OPTIONS"
                :key="s.value"
                :value="s.value"
                :disabled="s.value === 'org' && !userStore.orgId"
              >
                {{ s.label }}
              </el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
        <el-table-column label="归一实际用（系统默认 ∪ 组内）" min-width="200">
          <template #default="{ row }">
            <span v-if="row.effective === null" class="tip">—</span>
            <span v-else>{{ row.effective }} 条</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goType(row.value)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="tip ld-foot">
        条数读不到时显示「—」（可能未登录或服务不可用），上面的说明不受影响。
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
// 词典管理页：类型切换会同时刷新「术语查询」「提案审核」与「归档版本」三块数据。
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PanelCard from '@/components/PanelCard.vue'
import DictionaryProposalReview from '@/components/DictionaryProposalReview.vue'
import {
  getTerms, listArchives, rollbackArchive
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
// 批次 20 起新增舌象/脉象/治法，本批次新增病因 —— 后端均已建词典，此处同步暴露入口。
const TYPE_LABELS = {
  disease: '疾病',
  pattern: '证候',
  symptom: '症状',
  herb: '中药',
  formula: '方剂',
  tongue: '舌象',
  pulse: '脉象',
  cause: '病因',
  treatment: '治法'
}

/** 术语类型选项（页头 radio-group 的数据源） */

// 术语词典写入入口（导入）：管理员 / 所有者 / 被授权成员三档；
// 只读浏览对所有登录用户开放。后端按同一三档校验（批次 6 落地授权位），前端只负责不展示无效入口。
const TYPE_OPTIONS = Object.keys(TYPE_LABELS).map((v) => ({ value: v, label: TYPE_LABELS[v] }))
const canWrite = computed(() => userStore.canWriteDictionaryEntry)

// ===== 词典范围（三档：本地 / 组内 / 系统默认）=====
// 三档对应三个**不同的数据源**，不是同一个数据源的三种视图：
//   本地词典   = localStorage（「我的词典」页那份），纯前端，不参与解析与归一
//   组内词典   = 组织层（org_id = 本组），只含本组自己维护的词条，不含系统默认
//   系统默认词典 = 基础层（org_id = ''），随系统发布的初始词典，全组织共享只读
// 三者不是互斥的替换关系：解析与归一看的是「系统默认 ∪ 组内」的并集（同标准词以组内为准）。
// 默认停「系统默认词典」——它是所有用户开箱即用的那一层。
const SCOPE_OPTIONS = [
  { value: 'local', label: '本地词典' },
  { value: 'org', label: '组内词典' },
  { value: 'base', label: '系统默认词典' }
]

/**
 * 每一类术语词典**各记各的**查看层，互不影响。
 *
 * <p>做成 per-type 而不是全局一个值：八类词典的数据量差着数量级（证候两千多条、
 * 舌象几十条），用户很可能想「症状看组内、中药看系统默认」—— 一个全局档位做不到这件事，
 * 每切一次类型就得重选一次。</p>
 */
const scopeByType = reactive(
  Object.fromEntries(Object.keys(TYPE_LABELS).map((k) => [k, 'base']))
)
/** 当前类型的查看层（按钮文字、查询请求、本地档判定都用它） */
const viewScope = computed(() => scopeByType[typeKey.value] || 'base')

/** 当前查看层的名字（页头那颗按钮的文字用） */
const scopeLabel = computed(
  () => (SCOPE_OPTIONS.find((s) => s.value === viewScope.value) || {}).label || ''
)

// 词典类型 → 界面文案；键名与后端 type 参数一致（disease / pattern / symptom / herb / formula）

// ===== 布局：与其它页一致，不做整页缩放（表格内部滚动）=====
/** 当前术语类型（页头 radio-group 的值，全局过滤）
 *  默认取列表首项（TYPE_OPTIONS[0] = disease 疾病），而非硬编码某一类 ——
 *  否则「点进去显示的类型」与「列表第一个」对不上（曾硬编码 herb 中药）。 */
const typeKey = ref(TYPE_OPTIONS[0].value)
/** 当前任务页签：baseline 小组基线（只读查询）/ proposals 提案审核 / archives 归档版本
 *  （「我的词典」已摘出为独立页，入口在页头引导条行末） */
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
// 术语表高度改由 .fill-table + height="100%" 在卡内吃满（等高链），
// 不再按每页条数硬算 max-height（M23 那套随分页涨高的写法已被取代）。
const total = ref(0)

// 查询当前类型下的术语（关键字命中标准词或别名）
// resetPage：切类型 / 搜索 / 导入后 / 回滚后都应回到第 1 页（数据集合已变），
// 只有「翻页」「改每页条数」这两个纯翻页动作才传 false。
const loadTerms = async (resetPage = true) => {
  // 1. 置加载态，并清掉上一次的失败标记；需要重置时先回到第 1 页
  if (resetPage) page.value = 1
  loadingTerms.value = true
  termsFailed.value = false
  // 2a. 本地词典档：数据就在浏览器里（localTerms），不发请求、不分页到后端 ——
  //     自己按当前页切一刀即可，否则会去问后端「本地词典」，拿到的是空集。
  if (viewScope.value === 'local') {
    const kw = keyword.value.trim()
    const hit = kw
      ? localTerms.value.filter(
        (t) => String(t.standardTerm || '').includes(kw)
          || (t.aliases || []).some((a) => String(a).includes(kw))
      )
      : localTerms.value
    total.value = hit.length
    const from = (page.value - 1) * size.value
    terms.value = hit.slice(from, from + size.value)
    loadingTerms.value = false
    return
  }
  try {
    // 2b. 按当前词典类型 + 关键字 + 词典范围查询（标准词与别名都参与匹配）
    //    page 与 size 永远成对传：后端 page>0 时按 size 切片，漏传 size 会让
    //    后端用默认值 100，与前端 el-pagination 显示的每页条数对不上。
    //    scope 交给后端分三路读：base 只读基础层、org 只读组织层、effective 读并集。
    const res = await getTerms({
      type: typeKey.value,
      keyword: keyword.value,
      page: page.value,
      size: size.value,
      scope: viewScope.value
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

// 空态文案按当前范围分档：三层「为什么空、怎么才有内容」的答案不一样，
// 只写一句通用的会让用户以为是自己查询方式错了。
const emptyText = computed(() => {
  if (keyword.value) {
    return `没有匹配「${keyword.value}」的术语：换个更短的关键词，或确认该层词典已导入过词条`
  }
  if (viewScope.value === 'local') {
    return '本地词典为空：点页头的「我的词典」，用「拉取组内词典」或「批量导入文件」把词条加到本地'
  }
  if (viewScope.value === 'org') {
    return '组内词典为空：本组还没有自己的词条。可在「我的词典」批量导入文件或手动新增，再提交提案由组长审核'
  }
  return '系统默认词典暂无该类型术语：确认系统已导入过词条'
})

// 切换词典类型：先清掉上一次的查询状态，再拉新类型的数据
watch(typeKey, () => {
  // 切术语类型：本地词典按类型分开存，切回来要恢复；提案/归档同理
  restoreLocal()
  loadArchives()
  keyword.value = ''
  loadTerms()
})

// 切换词典范围：只影响本页签的查询结果，不动本地副本、也不动提案与归档。
// 清掉关键词再查 —— 关键词往往是针对上一层挑的，留着会让人误以为新范围是空的。
watch(viewScope, () => {
  keyword.value = ''
  loadTerms()
})

// 切回「词典查询」页签时，本地档再取一次：本地词典的写入方已挪到独立的
// 「我的词典」页（同页内不会再改），这里只是切页签回来时的兜底刷新 ——
// 只在本地档做，系统默认 / 组内两档没这个必要，也免得每次切页签都多打一次接口。
watch(tab, (v) => {
  if (v === 'baseline' && viewScope.value === 'local') loadTerms()
})


// ================================================================ 批次17：个人词典 / 提案 / 归档

const isOwner = computed(() => userStore.orgRole === 'owner')

// ---- 本地词典只读镜像（供「词典查询」页签的本地档展示）----
// 写入方（增删 / 拉取 / 提交提案）已全部挪到独立的「我的词典」页
// （/dictionary/mine）；本页只按类型把 localStorage 读出来给查询表格用 ——
// 在那边改完回到本页会重新挂载，onMounted 自然取到最新，不会看到旧内容。
const LOCAL_KEY = (org, type) => `dict.local.${org || 'base'}.${type}`

const localTerms = ref([])

const localKey = computed(() => LOCAL_KEY(userStore.orgId, typeKey.value))
// ---- 词典范围弹窗：对 9 类术语词典逐个选层 ----
const layerDialog = ref(false)
/** 各类词典「归一实际用」的条数（系统默认 ∪ 组内）；取不到时该行显 — */
const effectiveCounts = reactive(
  Object.fromEntries(Object.keys(TYPE_LABELS).map((k) => [k, null]))
)

const layerRows = computed(() => TYPE_OPTIONS.map((t) => ({
  value: t.value,
  label: t.label,
  effective: effectiveCounts[t.value]
})))

/**
 * 打开弹窗，并取八类词典「归一实际用」的条数。
 *
 * <p>每类只取 1 条（{@code size: 1}）—— 只需要 {@code total}，不必把几千条词条拉下来。
 * 八条请求并行；任一条失败就整批退化成「—」，不因为一次读不到就卡住弹窗。</p>
 */
const openLayerDialog = async () => {
  layerDialog.value = true
  try {
    const res = await Promise.all(TYPE_OPTIONS.map((t) =>
      getTerms({ type: t.value, page: 1, size: 1, scope: 'effective' })))
    TYPE_OPTIONS.forEach((t, i) => {
      effectiveCounts[t.value] = res[i].data?.total ?? 0
    })
  } catch {
    // 读不到就让各行显示「—」：上面那段归一说明不依赖条数，照常可看
    for (const k of Object.keys(effectiveCounts)) effectiveCounts[k] = null
  }
}

/** 从弹窗里跳去看某一类：切类型 + 关弹窗（该类型的查看层已在表里选好） */
const goType = (t) => {
  typeKey.value = t
  layerDialog.value = false
}

/** 从本地存储读取当前类型的词典（切类型 / 进页面时用），没有就空着 */
function restoreLocal() {
  const raw = localStorage.getItem(localKey.value)
  if (!raw) {
    localTerms.value = []
    return
  }
  try {
    const obj = JSON.parse(raw) || {}
    localTerms.value = Array.isArray(obj.terms) ? obj.terms : []
  } catch (e) {
    localTerms.value = []
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
/* ===== 等高链（照 Qc.vue .grid-2 写法）=====
   页头引导条/类型行/tab 头固定高度，剩下的垂直高度交给当前激活页签的卡片：
   dict-page → dict-tabs → el-tabs__content → 激活 tab-pane → PanelCard(纵列弹性)
   → panel-bd(纵列弹性) → .fill-table(flex:1) → el-table height="100%"。
   链上任一环高度不定，百分比就退化为 auto、表格缩回内容高、卡底又空一块。 */
.dict-page {
  height: calc(100vh - 186px);
  display: flex;
  flex-direction: column;
}
.dict-tabs {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.dict-tabs :deep(.el-tabs__content) {
  /* EP 默认是 overflow:hidden 的 block：子 tab-pane 按内容 1027 高时会被剪裁成看不见的下半截
     （裁内容，禁止）。改成 flex 列容器，让已有 flex:1;min-height:0 的激活 tab-pane 吃到确定高度 */
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}
/* 未激活页签被 v-show 置 display:none（覆盖此处），激活页签才有这套 flex 生效 */
.dict-tabs :deep(.el-tab-pane) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.q-card,
.arch-card {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.q-card :deep(.panel-bd),
.arch-card :deep(.panel-bd) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}
.fill-table {
  flex: 1 1 auto;
  min-height: 0;
  margin-top: var(--sp-3);
}
/* 词典范围弹窗：正文超高时弹窗内滚动（8 行通常不触发，留兜底），关闭叉号在右上角始终可见 */
.layer-dialog :deep(.el-dialog__body) {
  max-height: 72vh;
  overflow: auto;
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
.dl-up { color: var(--ochre-text); }
.dl-down { color: var(--danger); }
.dl-flat { color: var(--text-sub-strong); }
/* 列头 ⓘ：可聚焦，键盘用户也能读出说明 */
.hdr-info {
  color: var(--text-sub-strong);
  cursor: help;
  font-size: var(--fs-xs);
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
  color: var(--text-sub-strong);
  margin-top: var(--sp-1);
}
/* 详细格式收进折叠说明，避免一上来把数据结构摊给用户；
   位置在 import-actions 内，不再落在上传热区*/
.fmt-detail {
  margin-top: 10px;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
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
  color: var(--text-sub-strong);
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
  color: var(--text-sub-strong);
}
.rv-dirty {
  color: var(--ochre-text);
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

/* AI 补词建议：候选表 + 逐条确认。与批量导入页的建议面板同款（字段与配色一致），
   便于在「导入」与「手动录入」两个入口之间保持一致的操作预期。 */
.ai-suggest {
  margin-top: var(--sp-3);
  padding: var(--sp-3);
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--ochre-surface);
}
.as-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}
.as-title {
  font-size: var(--fs-base);
  color: var(--ink);
}
.as-reason {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.as-tag { margin-left: var(--sp-1); }
.as-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
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
  color: var(--text-sub-strong);
}
.dg-item b {
  color: var(--ink);
  margin-right: 2px;
}
/* 词典范围按钮：推到引导条行末，与引导项同一行；窄屏换行时也自成一个块 */
.dg-action {
  margin-left: auto;
  align-self: center;
}
/* ===== 词典范围：页面上只有一颗按钮（就在页头引导条那一行） ===== */
.dict-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}
.dh-label {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}

/* ===== 「归一用的是哪份词典」说明弹窗 ===== */
.ld-answer {
  font-size: var(--fs-base);
  line-height: 1.8;
  color: var(--text);
}
.ld-answer b { color: var(--ink); }
.ld-note {
  margin-top: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  background: var(--surface-sub);
  border-radius: 4px;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.ld-note b { color: var(--ink); }
/* 三档切换行：标签 + 切换按钮 + 当前层 tag 一行排开 */
.ld-cur { margin-left: var(--sp-1); }
.ld-table { margin-top: var(--sp-3); }
.ld-foot { margin-top: var(--sp-2); }

</style>