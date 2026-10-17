<template>
  <div>
    <!-- 从「术语词典」页签摘出的「我的词典」独立页：统一入口在词典页页头引导条行末
         （与「词典范围」并列），不在每个术语类型下各放一个入口。
         类型决定本地词典的 localStorage 键（8 类各存各的）；进入时类型跟随来源页
         （query.type），页内仍可自由改 —— 只影响默认值，不限制切换。 -->
    <div class="dict-head">
      <span class="dh-label">术语类型</span>
      <el-radio-group v-model="typeKey" size="small" aria-label="术语类型">
        <el-radio-button v-for="t in TYPE_OPTIONS" :key="t.value" :value="t.value">
          {{ t.label }}
        </el-radio-button>
      </el-radio-group>
      <el-button size="small" plain @click="$router.push('/dictionary')">返回术语词典</el-button>
    </div>

    <PanelCard title="个人词典（本地）">
      <div class="tip" style="margin-bottom: var(--sp-2)">
        这是你自己的词典副本：可手动增删，也可<b>批量导入文件</b>或从组内词典拉取。
        它只存在这台电脑。
        <template v-if="isOwner">
          你是本组组长，改完点「提交更新提案」，再到「提案审核」点「通过」即合并入小组基线。
        </template>
        <template v-else>
          改动要生效必须提交提案、由组长审核通过。
        </template>
      </div>
      <!-- 拉取只拉**组内词典**（= 提案基线，审核走整快照替换组织层）。
           系统默认词典不落地：它随系统库更新而变，查询时直接读后端就是最新的
           （DictionaryTermStoreImpl.read 按内容哈希查库，写路径主动失效），
           存一份到本地反而会过期，也会把基础层词条混进组织层快照。 -->
      <div class="rv-row">
        <el-button
          v-if="userStore.orgId"
          size="small"
          :loading="baselineLoading"
          @click="pullBaseline"
        >
          拉取组内词典
        </el-button>
        <el-button
          size="small"
          @click="$router.push({ path: '/dictionary/import', query: { type: typeKey } })"
        >
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
        :empty-text="localTerms.length ? '' : '先点「拉取组内词典」或「批量导入文件」把词条加到本地'">
        <el-table-column prop="standardTerm" label="标准词" min-width="160" />
        <el-table-column label="别名" min-width="200">
          <template #default="{ row }">{{ (row.aliases || []).join('、') }}</template>
        </el-table-column>
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
        <el-input v-model="newTerm" placeholder="标准词" style="width: 150px" size="small" />
        <el-input v-model="newAliases" placeholder="别名，多个用「、」分隔（可选）" style="width: 230px" size="small" />
        <el-button size="small" :disabled="!newTerm.trim()" @click="addLocalTerm">加入本地</el-button>
        <!-- 与批量导入页同一套 AI 补词建议：拿不准一个词该「新建标准词」还是
             「挂到某个已有标准词当别名」时，先让 AI 判一遍。采纳后只回填上面的输入框，
             不直接入库 —— 仍由人工核对无误再点「加入本地」。 -->
        <el-button
          size="small"
          type="primary"
          plain
          :loading="suggestLoading"
          :disabled="!addSuggestWords.length"
          @click="askAddSuggest"
        >AI 建议</el-button>
        <span class="tip">加入本地后同样需要提交提案才会进入小组基线</span>
      </div>

      <!-- AI 建议结果：只出候选、不落库。字段与批量导入页的建议面板一致
           （原文 / 建议动作 / 标准词 / 依据），采用后回填输入框，人工核对再入库。 -->
      <div v-if="suggestions.length" class="ai-suggest">
        <div class="as-head">
          <span class="as-title">AI 补词建议（{{ suggestions.length }} 条）</span>
          <span class="tip">{{ suggestNote }}</span>
        </div>
        <el-table :data="suggestions" border size="small" max-height="320">
          <el-table-column prop="original" label="原文" min-width="120" />
          <el-table-column label="建议动作" width="120">
            <template #default="{ row }">
              <el-select v-model="row.action" size="small">
                <el-option label="挂别名" value="alias" />
                <el-option label="新建标准词" value="new" />
                <el-option label="忽略" value="ignore" />
                <el-option label="待判断" value="unknown" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="标准词" min-width="150">
            <template #default="{ row }">
              <el-input
                v-model="row.standardTerm"
                size="small"
                :disabled="row.action === 'ignore' || row.action === 'unknown'"
                placeholder="填标准词"
              />
            </template>
          </el-table-column>
          <el-table-column label="依据" min-width="220">
            <template #default="{ row }">
              <span class="as-reason">{{ row.reason || '—' }}</span>
              <el-tag
                v-if="row.source === 'model'"
                size="small"
                type="warning"
                effect="plain"
                class="as-tag"
              >AI 生成</el-tag>
              <el-tag v-else-if="row.source === 'dict'" size="small" effect="plain" class="as-tag">
                词表命中
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div class="as-foot">
          <el-button size="small" type="primary" :disabled="!confirmable.length" @click="applyAddSuggest">
            采用结果（{{ confirmable.length }} 条）
          </el-button>
          <el-button size="small" @click="suggestions = []">关闭</el-button>
          <span class="tip">采用后会填回上方输入框，核对无误再点「加入本地」</span>
        </div>
      </div>
    </PanelCard>

    <!-- 合并确认弹窗：拉取 / 导入进本地之前，把「同名差异」与「归属冲突」摆出来让人拍板 -->
    <DictMergeDialog
      v-model:visible="mergeVisible"
      :result="mergeResult"
      :type-key="typeKey"
      @confirm="applyMerge"
    />
  </div>
</template>

<script setup>
// 「我的词典」独立页：本地 localStorage 副本的增删 / 拉取 / 批量导入 / 提交提案。
// 从「术语词典」页的 mine 页签整体迁出（统一入口：词典页页头引导条行末按键）。
// localStorage 键与迁出前一致（dict.local.{org}.{type}），数据不丢、与批量导入页互通。
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import DictMergeDialog from '@/components/DictMergeDialog.vue'
import { getTerms, exportBaseline, submitProposal } from '@/api/dictionary'
import { runTermSuggest } from '@/api/ai'
import { confirmBox } from '@/utils/confirm'
import { useUserStore } from '@/stores/user'
import { splitAliases } from '@/utils/terms'
import { mergeTermLists } from '@/utils/dictMerge'

const route = useRoute()
const userStore = useUserStore()

// 术语类型的中文标签（与后端 EntityTypes.dictKeys() 同源，键名须与后端 type 参数一致）
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
const TYPE_OPTIONS = Object.keys(TYPE_LABELS).map((v) => ({ value: v, label: TYPE_LABELS[v] }))

// 类型默认跟随来源：从「术语词典」页点「我的词典」进来时路由 query 带该类型，
// 这里按白名单校验后采用；缺失或非法（直接敲 URL）回落首项（disease）。
// 页内仍可自由改 —— 只是默认值跟着来源走。
const qType = String(route.query.type || '')
const typeKey = ref(TYPE_OPTIONS.some((t) => t.value === qType) ? qType : TYPE_OPTIONS[0].value)
const typeLabel = (v) => (TYPE_LABELS[v] || v)

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
const newAliases = ref('')
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

/** 从本地存储恢复当前类型的词典（切类型时换键重读），没有就空着 */
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

/**
 * 拉取组内词典到本地 —— **并入，不是覆盖**。
 *
 * <p>本地词典是一个持续累积的工作副本：拉取只往里加，不把本地已有内容整份换掉
 * （整份换掉会让「先导入再拉取」丢掉刚导入的词）。合并口径在
 * {@code utils/dictMerge.js}，与批量导入共用同一套。</p>
 *
 * <p>只拉组织层 —— 它才是提案基线（审核走整快照替换组织层）。系统默认词典不拉：
 * 它随系统库更新而变，查询时直接读后端就是最新的，存到本地反而会过期。</p>
 *
 * <p>有「同名差异」或「归属冲突」时**不替用户决定**，弹窗交人工拍板；
 * 纯新增这种没有歧义的情况直接落盘，不为了一次纯新增也拦一道弹窗。</p>
 */
const pullBaseline = async () => {
  baselineLoading.value = true
  try {
    const res = await exportBaseline({ type: typeKey.value, scope: 'org' })
    const incoming = (res.data || []).map((t) => ({
      standardTerm: t.standardTerm,
      aliases: t.aliases || []
    }))
    const r = mergeTermLists(localTerms.value, incoming)
    if (!r.sameTermDiff.length && !r.collisions.length) {
      commitLocal(r.merged, `已并入 ${r.added} 条（合计 ${r.total} 条）`)
      return
    }
    mergeResult.value = r
    mergeVisible.value = true
  } catch {
    // 拦截器已提示
  } finally {
    baselineLoading.value = false
  }
}

// ---- 合并结果落盘（弹窗确认后 / 无需确认时直接走）----
const mergeVisible = ref(false)
const mergeResult = ref(null)

/**
 * 把合并结果写进本地词典。
 *
 * <p>{@code baselineTouched} 只能近似：合并后本地是「组织层 ∪ 本地原有」，
 * 只有本地原本是空的才严格等于基线。所以判据取「合并前本地有没有内容」——
 * 比一律置 true/false 更贴近它要表达的「本地是否比基线多了东西」。</p>
 */
const commitLocal = (terms, msg) => {
  const hadLocal = localTerms.value.length > 0
  localTerms.value = terms
  localLoadedAt.value = new Date().toLocaleString()
  baselineTouched.value = hadLocal
  localPage.value = 1
  saveLocal()
  ElMessage.success(msg)
}

const applyMerge = (terms) => {
  const before = localTerms.value.length
  commitLocal(terms, `已并入本地词典：${before} → ${terms.length} 条`)
}

/**
 * 手动新增一条到本地词典。
 *
 * <p><b>两种重名都要拦</b>：①同标准词已存在（纯重复，直接拒绝）；
 * ②该词已是另一条的别名（归属冲突）—— 这一种直接放行会造出「一个词既是标准词、
 * 又是别人的别名」，归一里两边都要它，结果不确定。所以问一句，同意后才把
 * 它从原别名里摘出来、提为独立标准词（与弹窗里选「按新来」是同一套语义）。</p>
 */
const addLocalTerm = async () => {
  const t = newTerm.value.trim()
  if (!t) return
  if (localTerms.value.some((x) => x.standardTerm === t)) {
    ElMessage.warning('本地词典里已有该标准词')
    return
  }
  const owner = localTerms.value.find((x) => (x.aliases || []).includes(t))
  if (owner && !(await confirmBox(
    `「${t}」当前是「${owner.standardTerm}」的别名。提为独立标准词吗？`
    + '（会同时把它从原别名里移除，避免同一个词两边都要）',
    '归属冲突', { type: 'warning', confirmButtonText: '提为标准词', cancelButtonText: '取消' }
  ))) {
    return
  }
  if (owner) owner.aliases = owner.aliases.filter((a) => a !== t)
  localTerms.value.push({
    standardTerm: t,
    aliases: splitAliases(newAliases.value, t)
  })
  baselineTouched.value = true
  newTerm.value = ''
  newAliases.value = ''
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
        aliases: t.aliases || []
      }))
    })
    ElMessage.success(res.msg || '提案已提交，等待组长审核')
    baselineTouched.value = false
    // 提案列表在「术语词典」页的「提案审核」页签，该组件挂载即自取
    // （DictionaryProposalReview onMounted → loadProposals），回到那页自然是最新的
  } catch {
    // 拦截器已提示（含「已有 5 条待审提案」这类业务提示）
  } finally {
    submittingProposal.value = false
  }
}

// ---- AI 补词建议（与批量导入页同一套：只出候选，人工确认后才录入）----
// 复用后端 termsuggest：它按字面相似度从现有词表召回候选，再让 LLM 判断
// 「挂别名 / 新建标准词 / 忽略」。判定不进归一链路，只作人工录入前的参考。
const suggestions = ref([])
const suggestLoading = ref(false)
const suggestNote = ref('')

/** 送给 AI 的原文 = 标准词 + 别名，按输入顺序、去重去空 */
const addSuggestWords = computed(() => {
  const out = []
  const push = (s) => {
    const v = String(s || '').trim()
    if (v && !out.includes(v)) out.push(v)
  }
  push(newTerm.value)
  for (const a of splitAliases(newAliases.value, newTerm.value)) push(a)
  return out
})

/** 可采用的条目：动作是挂别名或新建，且标准词非空 */
const confirmable = computed(() =>
  suggestions.value.filter(
    (s) => (s.action === 'alias' || s.action === 'new') && String(s.standardTerm || '').trim()
  )
)

/** 向 AI 要建议；失败或不可用都不影响手动录入那条路 */
const askAddSuggest = async () => {
  const words = addSuggestWords.value
  if (!words.length) return
  suggestLoading.value = true
  suggestions.value = []
  try {
    const reply = await runTermSuggest({ terms: words, termType: typeKey.value })
    const list = reply?.termSuggestions || []
    if (!list.length) {
      ElMessage.warning('AI 没有返回建议，请稍后重试')
      return
    }
    suggestions.value = list.map((s) => ({ ...s, aliases: s.aliases || [] }))
    suggestNote.value = reply.llmAvailable
      ? 'AI 建议仅供参考，标「AI 生成」的条目未经权威词表校验。'
      : 'AI 不可用，以下是词表中字面相近的候选，需人工判断。'
  } catch (e) {
    ElMessage.warning(e?.message || 'AI 建议生成失败')
  } finally {
    suggestLoading.value = false
  }
}

/**
 * 采纳建议 → 回填「标准词 / 别名」输入框（**不直接入库**）。
 *
 * <p>主标准词优先取判为「新建」的那条；若全判成「挂别名」，则取第一条命中的标准词，
 * 它对应的原文本身就成了别名。其余各条的原文与其别名一并收作别名。
 * 只回填、不落库：这一行的语义是「人工录入」，AI 只是把要填的东西先摆好。</p>
 */
const applyAddSuggest = () => {
  const rows = confirmable.value
  if (!rows.length) return
  const main = rows.find((r) => r.action === 'new') || rows[0]
  const std = String(main.standardTerm || '').trim()
  if (!std) return
  const aliasSet = []
  for (const r of rows) {
    if (r === main) {
      if (r.action === 'alias') aliasSet.push(r.original)
    } else {
      aliasSet.push(r.original)
    }
    for (const a of r.aliases || []) aliasSet.push(a)
  }
  const aliases = [...new Set(aliasSet.map((s) => String(s).trim()).filter((s) => s && s !== std))]
  newTerm.value = std
  newAliases.value = aliases.join('、')
  suggestions.value = []
  ElMessage.success('已把建议填回输入框，核对无误后点「加入本地」')
}

// 切术语类型：本地词典按类型分开存，切回来要恢复
watch(typeKey, () => {
  restoreLocal()
  localPage.value = 1
})

onMounted(() => {
  restoreLocal()
})
</script>

<style scoped>
/* 术语类型行：与词典页页头同款（radio-group + 返回键） */
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
</style>
