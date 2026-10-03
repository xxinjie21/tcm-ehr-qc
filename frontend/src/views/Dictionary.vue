<template>
  <!-- 词典管理页（管理员）：五个词典类型（疾病 / 证候 / 症状 / 中药 / 方剂）切换，
       下面依次是术语查询、术语库导入、版本回滚 -->
  <div>
    <el-tabs v-model="activeTab" class="dict-tabs">
      <el-tab-pane label="疾病" name="disease" />
      <el-tab-pane label="证候" name="pattern" />
      <el-tab-pane label="症状" name="symptom" />
      <el-tab-pane label="中药" name="herb" />
      <el-tab-pane label="方剂" name="formula" />
    </el-tabs>

    <!-- 演示词典规模远小于真实词表（疾病仅 10 条、别名多为空），不说明会被当成系统缺陷 -->
    <div class="tip" style="margin: 0 0 var(--sp-2)">
      当前为演示词典：规模与真实词表差距较大，未命中属正常现象；导入正式词典后可提升归一命中率。
    </div>

    <!-- 术语查询：按当前类型 + 关键字模糊匹配（标准词与别名都参与匹配） -->
    <PanelCard :title="`术语查询（${typeLabel}）`">
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
    <!-- 写入入口按 admin / owner / 授权成员 三档判定（与后端 import/rollback 的门禁一致） -->
    <PanelCard v-if="canWrite" title="术语库导入">
      <div class="import-row">
        <el-upload
          ref="uploadRef"
          v-model:file-list="dictFileList"
          drag
          :auto-upload="false"
          :limit="1"
          :on-change="onFileChange"
          :on-remove="onFileRemove"
          :on-exceed="onFileExceed"
          accept=".xlsx,.xls,.csv,.json"
        >
          <div class="upload-tip">
            拖拽文件到此处，或 <em>点击选择</em>
            <div class="sub">支持 Excel(.xlsx/.xls) / CSV / JSON</div>
          </div>
        </el-upload>
        <div class="import-actions">
          <el-button type="primary" :loading="importing" :disabled="!importFile" @click="handleImport">
            开始导入
          </el-button>
          <div class="tip" style="margin-top: var(--sp-2)">导入前会自动备份，可在下方「版本回滚」恢复。</div>

          <!-- 格式说明移出 el-upload 拖拽区：原先嵌在拖拽热区里，
               点 <summary> 会冒泡触发原生文件选择框 -->
          <details class="fmt-detail">
            <summary>查看格式说明</summary>
            <div class="fmt-body">
              · Excel / CSV：第 1 列「标准术语」、第 2 列「别名」（多个用 、或 ; 分隔），可选第 3 列「国标代码」<br />
              · JSON：条目数组，每项含「标准术语」「别名」，可选「来源」「国标代码」
            </div>
          </details>
        </div>
      </div>
      <!-- 导入结果：总行数 / 成功 / 失败三个数字，外加按行号列出的失败原因 -->
      <div v-if="importResult" class="import-result">
        <StatCard label="文件总行数" :value="importResult.total" />
        <StatCard label="成功导入" :value="importResult.imported" tone="green" />
        <StatCard label="失败" :value="importResult.failed" tone="red" />
        <div v-if="importResult.failures?.length" class="failures">
          <div class="ded-hd">失败明细：</div>
          <div v-for="f in importResult.failures" :key="f.row" class="ded-item">
            <span>第 {{ f.row }} 行：{{ f.reason }}</span>
          </div>
        </div>
      </div>
    </PanelCard>

    <!-- ============================================================ 批次17：提案 + 归档 -->
    <!-- 原「版本回滚」面板已移除：dictionary_backups 表废弃，回滚改为
         「基于归档版本生成提案 → 组长审核」，历史列表改为「归档版本」。 -->

    <!-- 个人词典：拉取小组基线存本地，可在本地编辑后提交提案 -->
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
    <PanelCard title="基线更新提案">
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
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDiff(row)">看差异</el-button>
            <el-button
              v-if="isOwner && row.status === 'pending'"
              link type="warning"
              size="small"
              @click="doAudit(row, true)"
            >通过</el-button>
            <el-button
              v-if="isOwner && row.status === 'pending'"
              link type="danger"
              size="small"
              @click="doAudit(row, false)"
            >拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PanelCard>

    <!-- 差异详情 -->
    <el-dialog v-model="diffVisible" title="提案差异" width="min(900px, 94vw)" top="6vh">
      <div v-if="diff" class="rv-diff">
        <div class="rv-diff-sec">
          <div class="rv-diff-hd add">新增 {{ diff.added?.length || 0 }} 条</div>
          <div v-for="(t, i) in diff.added" :key="'a' + i" class="rv-diff-row">
            {{ t.standardTerm }}
            <span class="rv-diff-al">别名：{{ (t.aliases || []).join('、') || '—' }}</span>
          </div>
          <el-empty v-if="!diff.added?.length" description="无新增" :image-size="48" />
        </div>
        <div class="rv-diff-sec">
          <div class="rv-diff-hd mod">修改 {{ diff.modified?.length || 0 }} 条</div>
          <div v-for="(t, i) in diff.modified" :key="'m' + i" class="rv-diff-row">
            {{ t.before?.standardTerm }} → <b>{{ t.standardTerm }}</b>
            <span class="rv-diff-al">
              别名：{{ (t.before?.aliases || []).join('、') || '—' }}
              → {{ (t.aliases || []).join('、') || '—' }}
            </span>
          </div>
          <el-empty v-if="!diff.modified?.length" description="无修改" :image-size="48" />
        </div>
        <div class="rv-diff-sec">
          <div class="rv-diff-hd del">删除 {{ diff.removed?.length || 0 }} 条</div>
          <div v-for="(t, i) in diff.removed" :key="'d' + i" class="rv-diff-row">
            {{ t }}
          </div>
          <el-empty v-if="!diff.removed?.length" description="无删除" :image-size="48" />
        </div>
        <el-alert v-if="diff && diff.noDiff" type="info" :closable="false" show-icon
          title="与当前基线完全一致" description="提案内容与小组基线相同，合并后不会产生实际变化。" />
      </div>
      <template #footer>
        <el-button @click="diffVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 归档版本 -->
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
  </div>
</template>

<script setup>
// 词典管理页：类型切换会同时刷新「术语查询」与「版本回滚」两块数据。
// 导入只有一条路径 —— Excel / CSV / JSON 覆盖入库（导入前自动备份）。
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, genFileId } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import {
  getTerms, importDict, exportBaseline, submitProposal, listProposals,
  proposalDiff, auditProposal, listArchives, rollbackArchive
} from '@/api/dictionary'
import { confirmBox } from '@/utils/confirm'
import { useUserStore } from '@/stores/user'
import { PAGE_SIZES_WIDE } from '@/utils/constants'

const userStore = useUserStore()
// 术语词典写入入口（导入/回滚）：管理员 / 所有者 / 被授权成员三档；
// 只读浏览对所有登录用户开放。后端按同一三档校验（批次 6 落地授权位），前端只负责不展示无效入口。
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
const TYPE_LABELS = { disease: '疾病', pattern: '证候', symptom: '症状', herb: '中药', formula: '方剂' }

// ===== 布局：与其它页一致，不做整页缩放（表格内部滚动）=====
const activeTab = ref('disease')
// 当前词典类型的中文名，用于面板标题、确认文案与导入提示
const typeLabel = computed(() => TYPE_LABELS[activeTab.value])

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
      type: activeTab.value,
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
watch(activeTab, () => {
  // 切术语类型：本地词典按类型分开存，切回来要恢复；提案/归档同理
  restoreLocal()
  localPage.value = 1
  loadProposals()
  loadArchives()
  keyword.value = ''
  // 切换词典类型时清空上一次的导入结果与已选文件，
  // 否则会把「上一类词典的结果」误读成本次的结果
  importResult.value = null
  dictFileList.value = []
  importFile.value = null
  loadTerms()
})

// 导入相关状态：dictFileList 供 el-upload 回显，importFile 才是真正待提交的文件
const uploadRef = ref(null)
const dictFileList = ref([])
const importFile = ref(null)
const importing = ref(false)
const importResult = ref(null)

const MAX_FILE_MB = 50
const ALLOWED_EXT = ['.xlsx', '.xls', '.csv', '.json']

// 预校验扩展名与大小，不合格直接剔除并说明原因
const rejectFile = (raw, reason) => {
  ElMessage.error(`「${raw.name}」${reason}`)
  dictFileList.value = []
  importFile.value = null
}

// 选择文件：先做本地预校验（扩展名、大小），不合格直接剔除并说明原因
const onFileChange = (file) => {
  // 1. 取原始文件对象；拿不到就直接忽略
  const raw = file.raw
  if (!raw) return
  const name = (raw.name || '').toLowerCase()
  // 2. 扩展名不在白名单 → 剔除并说明原因
  if (!ALLOWED_EXT.some((ext) => name.endsWith(ext))) {
    rejectFile(raw, `格式不支持，仅支持 ${ALLOWED_EXT.join(' / ')}`)
    return
  }
  // 3. 超过大小上限 → 同样剔除
  if (raw.size > MAX_FILE_MB * 1024 * 1024) {
    rejectFile(raw, `超过 ${MAX_FILE_MB}MB 上限`)
    return
  }
  // 4. 预校验通过，记为待提交文件
  importFile.value = raw
}

// 移除已选文件：同步清掉待提交引用，避免提交到已删除的文件
const onFileRemove = () => {
  importFile.value = null
}

// limit=1 时再次选择会走这里；主动替换旧文件，避免「换了文件却没反应」
const onFileExceed = (files) => {
  // 1. 先清空旧文件：上传列表与待提交引用都要清，避免提交到上一个文件
  const file = files[0]
  uploadRef.value?.clearFiles()
  dictFileList.value = []
  importFile.value = null
  if (file) {
    // 2. 有新文件则重设 uid 后重新交给 upload 接管（limit=1 只能手动替换）
    file.uid = genFileId()
    uploadRef.value?.handleStart(file)
  }
}

// 统一入口：二次确认后覆盖式入库
const handleImport = async () => {
  // 1. 没有待提交文件就直接返回
  if (!importFile.value) return
  // 2. 覆盖式入库，先二次确认（取消则中止）
  if (!(await confirmBox(
    `确定用「${importFile.value.name}」覆盖【${typeLabel.value}】词典吗？`,
    '术语库导入',
    { type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '取消' }
  ))) return
  // 3. 执行入库，成功后清空已选文件与上传列表
  const ok = await doImport(importFile.value)
  if (ok) {
    uploadRef.value?.clearFiles()
    importFile.value = null
  }
}

// 真正入库：Excel / CSV / JSON 直传覆盖写入
const doImport = async (file) => {
  // 1. 置导入态：按钮 loading，避免重复提交
  importing.value = true
  try {
    // 2. 组装上传表单：文件 + 当前词典类型
    const fd = new FormData()
    fd.append('file', file)
    fd.append('type', activeTab.value)
    // 3. 提交入库并回填结果（总数 / 成功 / 失败明细）
    const res = await importDict(fd)
    importResult.value = res.data
    // 4. 提示成功并刷新术语列表与版本列表
    ElMessage.success(`导入完成：成功 ${res.data.imported} / 共 ${res.data.total}`)
    loadTerms()
    // 5. 返回成功，供调用方决定是否清空已选文件
    return true
  } catch {
    // 拦截器已提示
    return false
  } finally {
    // 无论成败都复位导入态
    importing.value = false
  }
}// 读取历史版本列表（按当前词典类型）// 回滚到指定版本：二次确认 → 覆盖当前词典 → 刷新术语与版本列表// 进页面拉取当前类型的术语与历史版本
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

const localKey = computed(() => LOCAL_KEY(userStore.orgId, activeTab.value))

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
    const res = await exportBaseline({ type: activeTab.value })
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
      type: activeTab.value,
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
const diffVisible = ref(false)
const diff = ref(null)
const openDiff = async (row) => {
  try {
    const res = await proposalDiff(row.id)
    diff.value = res.data || null
    diffVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

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
    const res = await listArchives({ type: activeTab.value })
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
    const res = await rollbackArchive(row.versionNo, activeTab.value)
    ElMessage.success(res.msg || '已生成回滚提案，请审核')
    proposalStatus.value = 'pending'
    loadProposals()
  } catch {
    // 拦截器已提示（含「快照已被清理，无法回滚」）
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
</style>
