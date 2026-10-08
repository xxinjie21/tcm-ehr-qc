<template>
<PanelCard title="基线更新提案">
  <!-- 主从布局：左提案列表 / 右详情。
       原先是「列表在上 + 点开弹窗看差异」，三栏差异（新增/修改/删除）+ 别名对照
       塞进 el-dialog 非常挤 —— 这正是「观感不好」的另一处。
       父项「术语词典」不做展开/收起，所以这里选中即加载，右侧常驻。 -->
  <div class="rv-master">
  <div class="rv-side">
  <div class="rv-row">
    <el-select v-model="proposalStatus" size="small" style="width: 120px"
      aria-label="提案状态" @change="loadProposals">
      <el-option label="待审核" value="pending" />
      <el-option label="已通过" value="approved" />
      <el-option label="已拒绝" value="rejected" />
    </el-select>
    <el-button size="small" @click="loadProposals">刷新</el-button>
  </div>
  <!-- 列表只列「谁 / 多少 / 什么状态」，术语类型由页头筛选器统一控制，
       审核意见与差异都在右侧详情里 —— 左栏重复一遍只会把列挤到横向滚动。 -->
  <el-table :data="proposals" border size="small" max-height="300" style="margin-top: var(--sp-2)"
    :empty-text="`当前类型暂无${statusLabel}提案`">
    <el-table-column label="提交人" min-width="96" show-overflow-tooltip>
      <template #default="{ row }">{{ row.submitUserId }}</template>
    </el-table-column>
    <el-table-column label="词条数" width="72" align="right" />
    <el-table-column prop="status" label="状态" width="84">
      <template #default="{ row }">
        <el-tag size="small" :type="statusTagType(row.status)" effect="plain">
          {{ statusText(row.status) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="64">
      <template #default="{ row }">
        <el-button link type="primary" size="small" @click="selectProposal(row)">查看</el-button>
      </template>
    </el-table-column>
  </el-table>
  <div v-if="isOwner" class="tip" style="margin-top: var(--sp-2)">
    你是本组组长：成员提交的改动会列在这里，由你点「通过」才合并入小组基线。
  </div>
  <div v-else class="tip" style="margin-top: var(--sp-2)">
    这里只列出你提交的提案；改动要生效需组长审核通过。
  </div>
  </div>

  <!-- 右侧：差异详情常驻（不再用弹窗 —— 三栏差异塞进 el-dialog 太挤） -->
  <div class="rv-detail">
    <EmptyState
      v-if="!currentProposal"
      text="从左侧选择一条提案查看差异"
      :image-size="70"
    />
    <template v-else>
      <div class="rv-detail-hd">
        <div class="rv-detail-meta">
          <b>{{ typeLabel(currentProposal.type) }}</b>
          <el-tag size="small" :type="statusTagType(currentProposal.status)" effect="plain">
            {{ statusText(currentProposal.status) }}
          </el-tag>
          <span class="tip">
            {{ currentProposal.termCount }} 条 · {{ fmtTime(currentProposal.createTime) }} 提交
          </span>
        </div>
        <div class="rv-detail-ops">
          <el-button v-if="editing" size="small" type="primary"
            :loading="savingTerms" @click="saveProposalTerms">保存提案</el-button>
          <el-button v-if="editing" size="small" @click="cancelEdit">取消</el-button>
          <el-button v-else-if="canEditTerms" size="small" @click="startEdit">编辑提案</el-button>
          <template v-if="isOwner && currentProposal.status === 'pending'">
            <el-button size="small" type="warning" @click="doAudit(currentProposal, true)">通过</el-button>
            <el-button size="small" type="danger" @click="doAudit(currentProposal, false)">拒绝</el-button>
          </template>
        </div>
      </div>

      <!-- 28.15：基础信息卡片 + 流转时间线。提案一旦离开「待审核」，光看差异区
           不知道「谁提的、谁审的、何时审的、为什么拒」，这四项此前无处可看 -->
      <el-descriptions
        v-if="!editing"
        class="rv-detail-info"
        :column="2"
        size="small"
        border
      >
        <el-descriptions-item label="词典类型">{{ typeLabel(currentProposal.type) }}</el-descriptions-item>
        <el-descriptions-item label="术语条数">{{ currentProposal.termCount }} 条</el-descriptions-item>
        <el-descriptions-item label="提交人">{{ currentProposal.submitUserId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ fmtTime(currentProposal.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="审核人">{{ currentProposal.auditUserId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="审核时间">
          {{ currentProposal.auditTime ? fmtTime(currentProposal.auditTime) : '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="审核意见" :span="2">
          {{ currentProposal.auditComment || (currentProposal.status === 'pending' ? '待审核' : '—') }}
        </el-descriptions-item>
      </el-descriptions>

      <el-timeline v-if="!editing" class="rv-detail-flow">
        <el-timeline-item
          :timestamp="fmtTime(currentProposal.createTime)"
          type="primary"
          placement="top"
        >
          提交提案（{{ currentProposal.submitUserId || '未知提交人' }}）
        </el-timeline-item>
        <el-timeline-item
          v-if="currentProposal.status !== 'pending'"
          :timestamp="fmtTime(currentProposal.auditTime)"
          :type="currentProposal.status === 'approved' ? 'success' : 'danger'"
          placement="top"
        >
          {{ currentProposal.status === 'approved' ? '审核通过' : '审核拒绝' }}（{{ currentProposal.auditUserId || '未知审核人' }}）
          <div v-if="currentProposal.auditComment" class="tip">{{ currentProposal.auditComment }}</div>
        </el-timeline-item>
      </el-timeline>

      <el-alert
        v-if="editing" type="warning" :closable="false" show-icon
        title="编辑中：改动仅作用于本次提案，不会改动小组基线"
        description="基线要等审核通过合并后才会变化；删掉某行 = 合并时从基线移除该术语。"
      />

      <!-- 只读差异（三色） -->
      <div v-if="!editing" class="rv-diff">
        <div class="rv-diff-sec">
          <div class="rv-diff-hd add">新增 {{ diff?.added?.length || 0 }} 条</div>
          <div v-for="(t, i) in diff?.added || []" :key="'a' + i" class="rv-diff-row">
            {{ t.standardTerm }}
            <span class="rv-diff-al">别名：{{ (t.aliases || []).join('、') || '—' }}</span>
          </div>
          <EmptyState v-if="!diff?.added?.length" text="无新增" :image-size="44" />
        </div>
        <div class="rv-diff-sec">
          <div class="rv-diff-hd mod">修改 {{ diff?.modified?.length || 0 }} 条</div>
          <div v-for="(t, i) in diff?.modified || []" :key="'m' + i" class="rv-diff-row">
            {{ t.before?.standardTerm }} → <b>{{ t.standardTerm }}</b>
            <span class="rv-diff-al">
              别名：{{ (t.before?.aliases || []).join('、') || '—' }}
              → {{ (t.aliases || []).join('、') || '—' }}
            </span>
          </div>
          <EmptyState v-if="!diff?.modified?.length" text="无修改" :image-size="44" />
        </div>
        <div class="rv-diff-sec">
          <div class="rv-diff-hd del">删除 {{ diff?.removed?.length || 0 }} 条</div>
          <div v-for="(t, i) in diff?.removed || []" :key="'d' + i" class="rv-diff-row">
            {{ t }}
          </div>
          <EmptyState v-if="!diff?.removed?.length" text="无删除" :image-size="44" />
        </div>
        <el-alert
          v-if="diff && diff.noDiff" type="info" :closable="false" show-icon
          title="与当前基线完全一致"
          description="提案内容与小组基线相同，合并后不会产生实际变化。"
        />
        <!-- 批次8：把「词典层差分」换算成「病历层影响」——用户真正关心的是这个 -->
        <el-alert
          v-if="diff && !diff.noDiff && mergeImpact.total > 0"
          type="success" :closable="false" show-icon
          title="合并后预计改善的归一结果"
          :description="`本次新增的术语中，有 ${mergeImpact.hit.length} 个此前是未归一词：`
            + mergeImpact.hit.slice(0, 3).map((h) => `「${h.term}」${h.count} 条`).join('、')
            + (mergeImpact.hit.length > 3 ? ' 等' : '')
            + `，合计 ${mergeImpact.total} 条病历的归一结果会变化（重跑解析后生效）。`"
        />
      </div>

      <!-- 可编辑态：整份提案的术语行（默认不显示，避免一屏铺满输入框） -->
      <div v-else class="rv-edit">
        <div class="rv-edit-hd">
          <span class="tip">
            共 {{ editTerms.length }} 条。改名 = 视为「删除旧词 + 新增新词」；删除某行 = 合并时从基线移除。
          </span>
          <el-input v-model="newEditTerm" size="small" placeholder="新增标准词" style="width: 150px" />
          <el-button size="small" :disabled="!newEditTerm.trim()" @click="addEditTerm">加入</el-button>
        </div>
        <el-table :data="editPaged" border size="small" max-height="360">
          <el-table-column label="标准词" min-width="160">
            <template #default="{ row }">
              <el-input v-model="row.standardTerm" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="别名（、分隔）" min-width="200">
            <template #default="{ row }">
              <el-input v-model="row.aliasText" size="small" placeholder="别名1、别名2" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="removeEditTerm($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="editTerms.length > EDIT_PAGE_SIZE" class="rv-pager">
          <el-button size="small" :disabled="editPage <= 1" @click="editPage--">上一页</el-button>
          <span class="tip">{{ editPage }} / {{ editPageCount }}</span>
          <el-button size="small" :disabled="editPage >= editPageCount" @click="editPage++">下一页</el-button>
        </div>
      </div>
    </template>
  </div>
  </div>
</PanelCard>
</template>

<script setup>
// 提案审核 Tab：左侧提案列表 / 右侧差异详情（含 28.15 基础信息卡 + 流转时间线，
// 以及可编辑态）。原本是 Dictionary.vue 页内的一块，抽出后父页只保留页头筛选与其它页签。
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PanelCard from '@/components/PanelCard.vue'
import { listProposals, proposalDiff, auditProposal, updateProposalTerms } from '@/api/dictionary'
import { getStandardizationReport } from '@/api/stats'
import { confirmBox } from '@/utils/confirm'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  /** 页头术语类型筛选器的值：提案列表按它取数 */
  typeKey: { type: String, default: '' },
  /** 是否本组组长：决定「通过/拒绝」按钮是否出现 */
  isOwner: { type: Boolean, default: false },
  /** 归档快照是否已满 5 份：通过前提示「会清理最早一份」 */
  archiveFull: { type: Boolean, default: false },
  /** 类型显示名函数（与父页 TYPE_LABELS 同源，避免两处维护） */
  typeLabel: { type: Function, default: (v) => v }
})
const emit = defineEmits(['audited'])

const userStore = useUserStore()

// ===== 批次8：合并影响面 =====
// 报告要的是「合并后会影响哪些病历的归一结果」。后端差分只到词典层（added/modified/removed），
// 没有病历级影响，因此这里用标准化报告里**批次2 外露的 unmatched.top**（词表缺口高频实体的
// 「原文 → 次数」）与本次新增术语求交：命中的就是「合并后会被归上」的那批未归一实体。
// 只加载一次并缓存 —— 报告接口较重，不该每点一条提案就跑一遍。
const unmatchedTop = ref({})
const unmatchedTopLoaded = ref(false)
const loadUnmatchedTopOnce = async () => {
  if (unmatchedTopLoaded.value) return
  unmatchedTopLoaded.value = true
  try {
    const res = await getStandardizationReport()
    unmatchedTop.value = res.data?.unmatched?.top || {}
  } catch {
    unmatchedTop.value = {} // 拿不到就不展示影响面，绝不编数字
  }
}
const mergeImpact = computed(() => {
  const top = unmatchedTop.value || {}
  const hit = []
  for (const t of diff.value?.added || []) {
    const n = top[t.standardTerm]
    if (n) hit.push({ term: t.standardTerm, count: n })
  }
  hit.sort((a, b) => b.count - a.count)
  return { hit, total: hit.reduce((a, c) => a + c.count, 0) }
})

// ---- 提案列表 ----
const proposals = ref([])
const proposalStatus = ref('pending')
const loadProposals = async () => {
  try {
    // type 跟随页头的术语类型筛选：基线与归档都按它取数，提案列表不跟随就会割裂
    const res = await listProposals({
      status: proposalStatus.value || undefined,
      type: props.typeKey || undefined
    })
    proposals.value = res.data || []
  } catch {
    proposals.value = []
  }
}

// ---- 差异 ----
const diff = ref(null)

// ---- 审核 ----
const doAudit = async (row, approve) => {
  let comment = ''
  if (!approve) {
    try {
      // 28.5：改用 ElMessageBox.prompt —— window.prompt 是原生弹窗，样式与全站脱节、在部分浏览器会被拦截
      const { value } = await ElMessageBox.prompt(
        '请填写拒绝理由（会一并记入提案，供提交人查看）',
        '驳回提案',
        {
          confirmButtonText: '驳回',
          cancelButtonText: '取消',
          inputType: 'textarea',
          inputValidator: (v) => (v && v.trim() ? true : '拒绝时必须填写理由')
        })
      comment = value
    } catch {
      return // 取消 / 关闭弹窗
    }
  } else {
    // 28.6：满 5 份时提前告知「通过后会清理最早快照」，别让用户事后才发现丢了旧版本
    const extra = props.archiveFull
      ? '注意：快照已达 5 份上限，通过后会清理最早一份快照（版本元信息保留）。'
      : ''
    if (!(await confirmBox('通过后将整份提案内容替换当前基线，并生成一份归档版本。' + extra + '确定？',
      '审核通过', { type: 'warning' }))) {
      return
    }
  }
  try {
    await auditProposal(row.id, { approve, comment })
    ElMessage.success(approve ? '已通过并合并入基线' : '已驳回')
    await loadProposals()
    // M7（审查报告）：审核动作完成后，右侧详情必须与列表同步 ——
    // 列表刷新了、详情还留在「待审核 + 三个按钮」的缓存视图，会让用户以为没生效，
    // 且按钮仍可点会对已审提案发第二次请求。
    const found = proposals.value.find((p) => p.id === row.id)
    if (found) {
      currentProposal.value = found
    } else if (currentProposal.value?.id === row.id) {
      // 该提案已不在当前筛选态（例如待审列表）→ 清空详情回空态，避免残留操作按钮
      currentProposal.value = null
    }
    emit('audited') // 父页据此刷新「归档版本」页签
  } catch {
    // 拦截器已提示
  }
}

// ---- 提案审核：主从布局（左侧列表 / 右侧详情）----
const currentProposal = ref(null)
const EDIT_PAGE_SIZE = 20
const editing = ref(false)
const savingTerms = ref(false)
const editTerms = ref([])
const editPage = ref(1)
const newEditTerm = ref('')

const editPageCount = computed(() =>
  Math.max(1, Math.ceil(editTerms.value.length / EDIT_PAGE_SIZE)))
const editPaged = computed(() => {
  const from = (editPage.value - 1) * EDIT_PAGE_SIZE
  return editTerms.value.slice(from, from + EDIT_PAGE_SIZE)
})
/** 只有「提交者本人 + 待审」能编辑 —— 后端也会再校验一次，这里只是不给按钮 */
const canEditTerms = computed(() =>
  !!currentProposal.value &&
  currentProposal.value.status === 'pending' &&
  currentProposal.value.submitUserId === userStore.username)

/** 选中一条提案并加载差异（右侧常驻，不再弹窗） */
const selectProposal = async (row) => {
  currentProposal.value = row
  editing.value = false
  editPage.value = 1
  try {
    const res = await proposalDiff(row.id)
    diff.value = res.data || null
    loadUnmatchedTopOnce() // 批次8：影响面数据（只加载一次）
  } catch {
    diff.value = null
  }
}

/**
 * 进入可编辑态。
 *
 * <p>⚠️ 这里有个必须讲清的限制：diff 只给「新增 / 修改」，**没有给未改动的原有词**；
 * 而后端 PUT 收的是「完整目标词典」。所以若只把 diff 里的词填进编辑器再保存，
 * 会把提案里其它词**全删掉** —— 一次误操作就清空整份提案。
 * 因此当 diff 为空（提案与基线一致）时直接拒绝进入编辑：没有可编辑内容，
 * 就不该给一个会清空数据的入口。</p>
 */
const startEdit = async () => {
  const row = currentProposal.value
  if (!row) return
  try {
    const res = await proposalDiff(row.id)
    const d = res.data || {}
    const rows = []
    for (const t of d.modified || []) {
      rows.push({ standardTerm: t.standardTerm, aliasText: (t.aliases || []).join('、') })
    }
    for (const t of d.added || []) {
      rows.push({ standardTerm: t.standardTerm, aliasText: (t.aliases || []).join('、') })
    }
    if (!rows.length) {
      ElMessage.info('本次提案与基线一致，没有可编辑的变更')
      return
    }
    editTerms.value = rows
    editPage.value = 1
    editing.value = true
  } catch {
    // 拦截器已提示
  }
}

const cancelEdit = () => {
  editing.value = false
  editTerms.value = []
  newEditTerm.value = ''
  editPage.value = 1
}

const addEditTerm = () => {
  const t = newEditTerm.value.trim()
  if (!t) return
  if (editTerms.value.some((x) => x.standardTerm === t)) {
    ElMessage.warning('提案里已有该标准词')
    return
  }
  editTerms.value.push({ standardTerm: t, aliasText: '' })
  newEditTerm.value = ''
}

const removeEditTerm = (i) => {
  editTerms.value.splice(i, 1)
}

/** 保存：把可编辑行还原成后端要的 {standardTerm, aliases} 结构 */
const saveProposalTerms = async () => {
  const row = currentProposal.value
  if (!row) return
  const terms = editTerms.value
    .filter((t) => t.standardTerm && t.standardTerm.trim())
    .map((t) => ({
      standardTerm: t.standardTerm.trim(),
      aliases: String(t.aliasText || '')
        .split(/[、,，;；|]/)
        .map((x) => x.trim())
        .filter(Boolean),
      source: ''
    }))
  savingTerms.value = true
  try {
    await updateProposalTerms(row.id, terms)
    ElMessage.success('提案内容已更新')
    editing.value = false
    editTerms.value = []
    selectProposal(row)   // 重新拉差异
    loadProposals()
  } catch {
    // 拦截器已提示
  } finally {
    savingTerms.value = false
  }
}

const statusText = (st) => ({ pending: '待审核', approved: '已通过', rejected: '已拒绝' }[st] || st)
const statusTagType = (st) => ({ pending: 'warning', approved: 'success', rejected: 'info' }[st] || 'info')
/** 当前筛选状态的文案，供空态拼句用 */
const statusLabel = computed(() => statusText(proposalStatus.value))

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

watch(() => props.typeKey, () => { loadProposals() })

onMounted(() => { loadProposals() })

defineExpose({
  refresh: loadProposals,
  /** 版本回滚后调用：切回「待审核」并刷新，让刚生成的回滚提案立刻可见 */
  focusPending: () => { proposalStatus.value = 'pending'; return loadProposals() }
})
</script>

<style scoped>
/* 本组件的样式：仅保留提案审核主从布局与三色差异所需 */
.rv-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.rv-pager {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}
/* 差异三栏：新增/修改/删除用色块区分，颜色与 EmptyState 的语义色一致 */
.rv-diff-sec {
  margin-bottom: var(--sp-4);
}
.rv-diff-hd {
  font-weight: 600;
  font-size: var(--fs-base);
  margin-bottom: var(--sp-2);
  padding-left: var(--sp-2);
  border-left: 3px solid var(--line);
}
.rv-diff-hd.add { border-left-color: var(--ink-mid); color: var(--ink-mid); }
.rv-diff-hd.mod { border-left-color: var(--ochre); color: var(--ochre-text); }
.rv-diff-hd.del { border-left-color: var(--danger); color: var(--danger); }
.rv-diff-row {
  padding: var(--sp-1) var(--sp-2);
  font-size: var(--fs-base);
  border-bottom: 1px solid var(--line);
}
.rv-diff-al {
  margin-left: var(--sp-2);
  color: var(--text-sub-strong);
  font-size: var(--fs-xs);
}

/* ===== 提案审核：主从布局 ===== */
/* 左侧固定 420px 列表、右侧自适应详情。
   宽度从 340px 提到 420px：左列表的列宽合计约 316px（提交人 96 + 词条数 72
   + 状态 84 + 操作 64），340px 时会被挤到横向滚动。 */
.rv-master {
  display: flex;
  gap: var(--sp-4);
  align-items: flex-start;
}
.rv-side {
  flex: 0 0 420px;
  min-width: 360px;
}
.rv-detail {
  flex: 1 1 auto;
  min-width: 0;   /* 关键：否则 flex 子项不收缩，长内容会把右栏撑破 */
  border-left: 1px solid var(--line);
  padding-left: var(--sp-4);
}
.rv-detail-hd {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: var(--sp-3);
  padding-bottom: var(--sp-2);
  border-bottom: 1px solid var(--line);
}
.rv-detail-meta {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.rv-detail-ops {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
/* 28.15：基础信息卡片与流转时间线 */
.rv-detail-info {
  margin-bottom: var(--sp-4);
}
.rv-detail-flow {
  margin-bottom: var(--sp-3);
  padding-left: 2px;
}
/* 编辑态的术语行 */
.rv-edit-hd {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}
/* 窄屏（<1200px）下主从退化为上下堆叠 */
@media (max-width: 1200px) {
  .rv-master {
    flex-direction: column;
  }
  .rv-side {
    flex: 1 1 auto;
    width: 100%;
  }
  .rv-detail {
    width: 100%;
    border-left: none;
    border-top: 1px solid var(--line);
    padding-left: 0;
    padding-top: var(--sp-3);
  }
}
</style>
