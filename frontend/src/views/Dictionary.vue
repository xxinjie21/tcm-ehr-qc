<template>
  <!-- 词典管理页（管理员）：五个词典类型（疾病 / 证候 / 症状 / 中药 / 方剂）切换，
       下面依次是术语查询、术语库导入、版本回滚 -->
  <div>
    <!-- 页头：术语类型筛选器。
         类型是**全局过滤**（切类型后整个页签内的数据都跟着换），不是页面导航 ——
         所以用 radio-group 而不是页签：原先那 5 个自闭合的空壳 tab-pane 长得像页签、
         点的却是下面那一摞内容，观感上就是坏的。 -->
    <div class="dict-head">
      <el-radio-group v-model="typeKey" size="small" aria-label="术语类型">
        <el-radio-button v-for="t in TYPE_OPTIONS" :key="t.value" :value="t.value">
          {{ t.label }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <!-- 演示词典规模远小于真实词表（疾病仅 10 条、别名多为空），不说明会被当成系统缺陷 -->
    <div class="tip" style="margin: 0 0 var(--sp-2)">
      当前为演示词典：规模与真实词表差距较大，未命中属正常现象；导入正式词典后可提升归一命中率。
    </div>

    <!-- 术语查询：按当前类型 + 关键字模糊匹配（标准词与别名都参与匹配） -->
    <el-tabs v-model="tab" class="dict-tabs">

    <el-tab-pane label="小组基线" name="baseline">
    <PanelCard :title="`小组基线（${typeLabel}）`">
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
      <!-- 作用域提示：词典已按组织隔离（批次8b）。这一条不是装饰 —— 组织 A 导入的词
           只在 A 的归一里生效，管理员在此看到「基础层」时不能以为那就是全量生效词典 -->
      <div class="scope-hint">
        <el-tag size="small" :type="scopeTagType" effect="plain">{{ scopeLabel }}</el-tag>
        <span class="tip">{{ scopeTip }}</span>
      </div>
      <!-- max-height 360：表头 32 + 10 行 × 32 + 余量，表格内部滚动，页面本身不出现滚动条。
           每页条数可调（20~200），故按可视行数固定高度，超出的行在表格内部滚动 -->
<el-table v-loading="loadingTerms" element-loading-text="正在查询术语…" :data="terms" border stripe style="margin-top: var(--sp-3)" max-height="360">
          <!-- 空态解释「为什么空、怎么才有内容」：走下方 #empty 插槽；:empty-text 是死代码已删 -->
          <el-table-column prop="standardTerm" label="标准术语" width="220" />
        <el-table-column label="别名">
          <template #default="{ row }">
            <el-tag
              v-for="a in row.aliases"
              :key="a"
              size="small"
              effect="plain"
              style="margin-right: 6px"
            >{{ a }}</el-tag>
            <span v-if="!row.aliases?.length" class="tip">无</span>
          </template>
        </el-table-column>
        <template #empty>
          <!-- P5.8：空态必须解释「为什么空 / 怎么才有内容」；加载失败与真为空分开 -->
          <el-empty
            :description="termsFailed
              ? '术语加载失败，请点击「查 询」重试'
              : (keyword ? `没有匹配「${keyword}」的术语：换个更短的关键词，或确认该类型已导入过词条` : '该词典暂无术语：使用「术语库导入」上传词典后可在此检索')"
            :image-size="80"
          >
            <el-button v-if="termsFailed" size="small" @click="loadTerms(true)">重 试</el-button>
          </el-empty>
        </template>
      </el-table>
      <!-- 分页：词典已从演示的十几条涨到上千条（如疾病 1357、证候 2080），
           一次全量渲染会卡且无法定位，故按页浏览 -->
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="PAGE_SIZES_WIDE"
        :total="total"
        layout="total, sizes, prev, pager, next"
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
      <div class="rv-row">
        <el-button size="small" :loading="baselineLoading" @click="loadBaseline">
          拉取小组基线
        </el-button>
        <el-button
          size="small"
          type="primary"
          :loading="submittingProposal"
          :disabled="!localTerms.length"
          @click="doSubmitProposal"
        >提交更新提案</el-button>
        <span class="tip">
          本地词典只存在这台电脑上，<b>不会自动同步小组基线</b>；改动要生效必须走提案 → 组长审核。
        </span>
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
        <span class="tip">
          普通成员只能看到自己提交的提案；组织所有者可审核并合并。
        </span>
      </div>
      <el-table :data="proposals" border size="small" max-height="240" style="margin-top: var(--sp-3)"
        :empty-text="'暂无提案。在上方「个人词典」里改完后点「提交更新提案」'">
        <el-table-column prop="type" label="类型" width="90" />
        <el-table-column label="提交人" width="110">
          <template #default="{ row }">{{ row.submitUserId }}</template>
        </el-table-column>
        <el-table-column prop="termCount" label="词条数" width="90" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)" effect="plain">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="auditComment" label="审核意见" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="70">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="selectProposal(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>

      <!-- 右侧：差异详情常驻（不再用弹窗 —— 三栏差异塞进 el-dialog 太挤） -->
      <div class="rv-detail">
        <el-empty
          v-if="!currentProposal"
          description="从左侧选择一条提案查看差异"
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
              <el-empty v-if="!diff?.added?.length" description="无新增" :image-size="44" />
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
              <el-empty v-if="!diff?.modified?.length" description="无修改" :image-size="44" />
            </div>
            <div class="rv-diff-sec">
              <div class="rv-diff-hd del">删除 {{ diff?.removed?.length || 0 }} 条</div>
              <div v-for="(t, i) in diff?.removed || []" :key="'d' + i" class="rv-diff-row">
                {{ t }}
              </div>
              <el-empty v-if="!diff?.removed?.length" description="无删除" :image-size="44" />
            </div>
            <el-alert
              v-if="diff && diff.noDiff" type="info" :closable="false" show-icon
              title="与当前基线完全一致"
              description="提案内容与小组基线相同，合并后不会产生实际变化。"
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

    </el-tab-pane>

    <el-tab-pane label="归档版本" name="archives">
    <PanelCard title="归档版本">
      <div class="rv-row">
        <el-button size="small" :loading="archivesLoading" @click="loadArchives">刷新归档</el-button>
        <span class="tip">
          每次基线合并后自动留一份快照；每个类型最多保留最近 5 份快照，版本元信息永久保留。
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
            <el-tag v-if="row.snapshotPresent" size="small" type="success" effect="plain">可用</el-tag>
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
import PanelCard from '@/components/PanelCard.vue'
import {
  getTerms, exportBaseline, submitProposal, listProposals,
  proposalDiff, auditProposal, listArchives, rollbackArchive, updateProposalTerms
} from '@/api/dictionary'
import { confirmBox } from '@/utils/confirm'
import { useUserStore } from '@/stores/user'
import { PAGE_SIZES_WIDE } from '@/utils/constants'

const userStore = useUserStore()

/**
 * 术语类型的中文标签（与后端 TermTypes.ALL 同源）。
 *
 * <p><b>必须声明在 TYPE_OPTIONS 之前</b>：<code>const</code> 是块级作用域且有 TDZ，
 * 上层在初始化时读到下层的 const 会抛
 * {@code ReferenceError: Cannot access 'X' before initialization}，
 * 表现为整个组件 setup 失败、页面白屏 —— 而 {@code vite build} 不会报错。
 * 这就是批次 12 工作项 8「TDZ 调序」要防的那类问题。</p>
 */
const TYPE_LABELS = { disease: '疾病', pattern: '证候', symptom: '症状', herb: '中药', formula: '方剂' }

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
const total = ref(0)

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
  loadProposals()
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
    loadProposals()
  } catch {
    // 拦截器已提示（含「已有 5 条待审提案」这类业务提示）
  } finally {
    submittingProposal.value = false
  }
}

// ---- 提案列表 ----
const proposals = ref([])
const proposalStatus = ref('pending')
const loadProposals = async () => {
  try {
    const res = await listProposals({ status: proposalStatus.value || undefined })
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
    comment = window.prompt('请填写拒绝理由（会一并记入提案，供提交人查看）')
    if (comment === null) return
    if (!comment.trim()) {
      ElMessage.warning('拒绝时必须填写理由')
      return
    }
  } else if (!(await confirmBox('通过后将整份提案内容替换当前基线，并生成一份归档版本。确定？',
    '审核通过', { type: 'warning' }))) {
    return
  }
  try {
    await auditProposal(row.id, { approve, comment })
    ElMessage.success(approve ? '已通过并合并入基线' : '已驳回')
    loadProposals()
    loadArchives()
  } catch {
    // 拦截器已提示
  }
}

// ---- 归档版本 ----
const archives = ref([])
const archivesLoading = ref(false)
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
    proposalStatus.value = 'pending'
    loadProposals()
  } catch {
    // 拦截器已提示（含「快照已被清理，无法回滚」）
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

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

onMounted(() => {
  restoreLocal()
  loadProposals()
  loadArchives()
  loadTerms()
})
</script>

<style scoped>
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
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
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
  font-size: 13px;
  color: var(--text);
}
.upload-tip .sub {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: var(--sp-1);
}
/* 详细格式收进折叠说明，避免一上来把数据结构摊给用户；
   位置在 import-actions 内，不再落在上传热区*/
.fmt-detail {
  margin-top: 10px;
  font-size: 12px;
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
  padding: var(--sp-2) 10px;
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
  font-size: 13px;
  color: var(--text-sub);
  margin-bottom: 6px;
}
/* 单条失败项：浅 ochre 底条，与正文区分 */
.ded-item {
  background: var(--ochre-light);
  border-radius: 2px;
  padding: 6px var(--sp-3);
  margin-bottom: 6px;
  font-size: 12.5px;
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
  font-size: 12.5px;
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
/* 差异三栏：新增/修改/删除用色块区分，颜色与 EmptyState 的语义色一致 */
.rv-diff-sec {
  margin-bottom: var(--sp-4);
}
.rv-diff-hd {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: var(--sp-2);
  padding-left: var(--sp-2);
  border-left: 3px solid var(--line);
}
.rv-diff-hd.add { border-left-color: var(--ink-mid); color: var(--ink-mid); }
.rv-diff-hd.mod { border-left-color: var(--ochre); color: var(--ochre); }
.rv-diff-hd.del { border-left-color: var(--danger); color: var(--danger); }
.rv-diff-row {
  padding: 3px var(--sp-2);
  font-size: 13px;
  border-bottom: 1px solid var(--line);
}
.rv-diff-al {
  margin-left: var(--sp-2);
  color: var(--text-sub);
  font-size: 12px;
}

/* ===== 页头：术语类型筛选器 ===== */
.dict-head {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-bottom: var(--sp-3);
}

/* ===== 提案审核：主从布局 ===== */
/* 左侧固定 340px 列表、右侧自适应详情。
   原先是「列表在上 + 弹窗看差异」，三栏差异塞进 el-dialog 极挤。 */
.rv-master {
  display: flex;
  gap: var(--sp-4);
  align-items: flex-start;
}
.rv-side {
  flex: 0 0 340px;
  min-width: 300px;
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
/* 编辑态的术语行 */
.rv-edit-hd {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}
/* 窄屏（<1200px）下主从退化为上下堆叠：340px 固定 + 详情在 1366 视口里
   与侧栏(约220px)相加会挤掉内容，堆叠更稳。 */
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
