<template>
  <!-- 词典管理页（管理员）：五个词典类型（疾病 / 证候 / 症状 / 中药 / 方剂）切换，
       下面依次是术语查询、术语库导入、PDF 转换预览、版本回滚 -->
  <div>
    <el-tabs v-model="activeTab" class="dict-tabs">
      <el-tab-pane label="疾病" name="disease" />
      <el-tab-pane label="证候" name="pattern" />
      <el-tab-pane label="症状" name="symptom" />
      <el-tab-pane label="中药" name="herb" />
      <el-tab-pane label="方剂" name="formula" />
    </el-tabs>

    <!-- 演示词典规模远小于真实词表（疾病仅 10 条、别名多为空），不说明会被当成系统缺陷 -->
    <div class="tip" style="margin: 0 0 8px">
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
          @keyup.enter="loadTerms"
        />
        <el-button type="primary" :loading="loadingTerms" @click="loadTerms">查 询</el-button>
        <span class="tip">共 {{ terms.length }} 条</span>
      </div>
      <!-- max-height 360：表头 32 + 10 行 × 32 + 余量，表格内部滚动，页面本身不出现滚动条 -->
      <el-table v-loading="loadingTerms" :data="terms" border stripe style="margin-top: 12px" max-height="360"
        :empty-text="termsFailed ? '加载失败，请点「查 询」重试' : '没有匹配的术语'">
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
          <el-empty
            :description="keyword ? `没有匹配「${keyword}」的术语` : '该词典暂无术语'"
            :image-size="80"
          />
        </template>
      </el-table>
    </PanelCard>

    <!-- 术语库导入：非 PDF 直接覆盖入库（导入前自动备份）；PDF 先转换出候选、确认后才写入 -->
    <PanelCard title="术语库导入">
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
          accept=".xlsx,.xls,.csv,.json,.pdf"
        >
          <div class="upload-tip">
            拖拽文件到此处，或 <em>点击选择</em>
            <div class="sub">支持 Excel(.xlsx/.xls) / CSV / JSON；PDF 可智能转换为术语</div>
          </div>
        </el-upload>
        <div class="import-actions">
          <!-- 按钮文案随文件类型切换：PDF 走「智能转换（预览）」，其余走「开始导入」 -->
          <el-button type="primary" :loading="importing || converting" :disabled="!importFile" @click="handleImport">
            {{ isPdfFile ? '智能转换（预览）' : '开始导入' }}
          </el-button>
          <div class="tip" style="margin-top: 8px">导入前会自动备份，可在下方「版本回滚」恢复。</div>

          <!-- 格式说明移出 el-upload 拖拽区（UX-78）：原先嵌在拖拽热区里，
               点 <summary> 会冒泡触发原生文件选择框 -->
          <details class="fmt-detail">
            <summary>查看格式说明</summary>
            <div class="fmt-body">
              · Excel / CSV：第 1 列「标准术语」、第 2 列「别名」（多个用 、或 ; 分隔），可选第 3 列「国标代码」<br />
              · JSON：条目数组，每项含「标准术语」「别名」，可选「来源」「国标代码」<br />
              · PDF：上传后先转换为候选术语，确认无误再入库
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

    <!-- PDF 智能转换预览：预览阶段不落库，确认后才写入。
         由弹窗改为同页展开（UX-66 修订）——弹窗内嵌宽表格必然出现滚动条，
         且用户看不到它属于「术语库导入」这一步的上下文。左＝候选，右＝失败明细与确认操作 -->
    <PanelCard
      v-if="convertVisible"
      ref="convertRef"
      title="PDF 转换预览"
      class="convert-panel"
    >
      <template #header>
        <span>PDF 转换预览（确认后才写入词典）</span>
        <el-button link class="hd-close" @click="convertVisible = false">关闭预览</el-button>
      </template>

      <div class="convert-body">
        <div class="convert-main">
          <div class="convert-hd">
            转换出候选 <b>{{ convert.candidates.length }}</b> 条，失败 <b>{{ convert.failed.length }}</b> 条；
            确认后写入【{{ typeLabel }}】词典。
          </div>
          <el-table :data="convert.candidates" border size="small" max-height="400" style="margin-top: 10px">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="standardTerm" label="标准术语" width="170" />
            <el-table-column label="别名" min-width="220">
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
            <el-table-column prop="source" label="来源" width="150" show-overflow-tooltip />
            <el-table-column prop="code" label="国标代码" width="110" />
            <template #empty>
              <el-empty description="没有可入库的候选" :image-size="70" />
            </template>
          </el-table>
        </div>

        <div class="convert-side">
          <div class="col-hd">失败明细（{{ convert.failed.length }} 条）</div>
          <div v-if="convert.failed.length" class="fail-list">
            <div v-for="(f, i) in convert.failed" :key="i" class="ded-item">
              <span>{{ f.reason }}{{ f.text ? '：' + f.text : '' }}</span>
            </div>
          </div>
          <div v-else class="tip">本次没有失败项。</div>

          <div class="convert-actions">
            <el-button :disabled="!convert.failed.length" @click="downloadFailed">下载失败明细</el-button>
            <el-button
              type="primary"
              :loading="importing"
              :disabled="!convert.candidates.length"
              @click="confirmConvert"
            >确认入库（{{ convert.candidates.length }} 条）</el-button>
          </div>
        </div>
      </div>
    </PanelCard>

    <!-- 版本回滚：每次导入前自动备份，选任一版本覆盖当前词典并立即生效 -->
    <PanelCard title="版本回滚">
      <div class="rollback-row">
        <span class="tip">回滚会用该版本覆盖当前词典，立即生效。</span>
        <el-button size="small" @click="loadBackups">刷新历史版本</el-button>
      </div>
      <el-table :data="backups" border style="margin-top: 12px" max-height="260"
        :empty-text="backupsFailed ? '历史版本加载失败，请点「刷新历史版本」重试'
          : '暂无历史版本。导入词典时会自动备份，导入一次即可在这里回滚'">
        <el-table-column prop="time" label="导入时间" min-width="180" />
        <el-table-column prop="count" label="词条数" width="110" />
        <el-table-column label="较当前" width="120">
          <template #default="{ row }">
            <span :class="deltaClass(row.delta)">{{ deltaText(row.delta) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              type="warning"
              size="small"
              plain
              @click="handleRollback(row)"
            >回滚到此版</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PanelCard>
  </div>
</template>

<script setup>
// 词典管理页：类型切换会同时刷新「术语查询」与「版本回滚」两块数据。
// 导入有两条路径 —— 非 PDF 直接覆盖入库；PDF 先转换预览，确认后再把候选转成 JSON 复用同一入库接口。
import { ref, reactive, computed, watch, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox, genFileId } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import { getTerms, importDict, convertDict, rollback, getBackups } from '@/api/dictionary'
import { saveBlob } from '@/utils/download'
import { confirmBox } from '@/utils/confirm'

// 词典类型 → 界面文案；键名与后端 type 参数一致（disease / pattern / symptom / herb / formula）
const TYPE_LABELS = { disease: '疾病', pattern: '证候', symptom: '症状', herb: '中药', formula: '方剂' }

// ===== 布局：与其它页一致，不做整页缩放（表格内部滚动）=====
const activeTab = ref('disease')
// 当前词典类型的中文名，用于面板标题、确认文案与导入提示
const typeLabel = computed(() => TYPE_LABELS[activeTab.value])

// 术语查询状态：keyword 为用户输入，terms 为查询结果
const keyword = ref('')
const terms = ref([])
const loadingTerms = ref(false)
/** 词条查询失败：与「确实没有匹配」区分开 */
const termsFailed = ref(false)

// 查询当前类型下的术语（关键字命中标准词或别名）
const loadTerms = async () => {
  // 1. 置加载态，并清掉上一次的失败标记
  loadingTerms.value = true
  termsFailed.value = false
  try {
    // 2. 按当前词典类型 + 关键字查询（标准词与别名都参与匹配）
    const res = await getTerms({ type: activeTab.value, keyword: keyword.value })
    // 3. 回填查询结果
    terms.value = res.data.terms || []
  } catch {
    // 原来只有 try/finally：接口挂了列表还停在上一次的结果，用户会把旧数据当最新
    // 失败置失败态并清空列表，避免旧结果被当成最新
    termsFailed.value = true
    terms.value = []
  } finally {
    loadingTerms.value = false
  }
}

// 切换词典类型：先清掉上一次的查询与导入状态，再拉新类型的数据
watch(activeTab, () => {
  keyword.value = ''
  // 切换词典类型时清空上一次的导入/转换结果与已选文件，
  // 否则会把「上一类词典的结果」误读成本次的结果（UX-23）
  importResult.value = null
  convert.candidates = []
  convert.failed = []
  convertVisible.value = false
  dictFileList.value = []
  importFile.value = null
  loadTerms()
  loadBackups()
})

// 导入相关状态：dictFileList 供 el-upload 回显，importFile 才是真正待提交的文件
const uploadRef = ref(null)
const dictFileList = ref([])
const importFile = ref(null)
const importing = ref(false)
const importResult = ref(null)

// 已选文件是否为 PDF：决定导入按钮走「智能转换（预览）」还是「开始导入」
const isPdfFile = computed(() => (importFile.value?.name || '').toLowerCase().endsWith('.pdf'))

const MAX_FILE_MB = 50
const ALLOWED_EXT = ['.xlsx', '.xls', '.csv', '.json', '.pdf']

/** 预校验扩展名与大小，不合格直接剔除并说明原因（UX-27） */
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

/** limit=1 时再次选择会走这里；主动替换旧文件，避免「换了文件却没反应」（UX-28） */
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

/** 统一入口：PDF 先走智能转换出预览，其余格式直接入库 */
const handleImport = async () => {
  // 1. 没有待提交文件就直接返回
  if (!importFile.value) return
  // 2. PDF 先走「智能转换」出候选预览，不在这里入库
  if (isPdfFile.value) {
    await handleConvert()
    return
  }
  // 3. 其余格式覆盖式入库，先二次确认（取消则中止）
  const ok = await ElMessageBox.confirm(
    `确定用「${importFile.value.name}」覆盖【${typeLabel.value}】词典吗？`,
    '术语库导入',
    { type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '取消' }
  ).catch(() => false)
  if (!ok) return
  // 4. 执行入库，成功后清空已选文件与上传列表
  await doImport(importFile.value)
  uploadRef.value?.clearFiles()
  importFile.value = null
}

// PDF 转换预览状态：candidates 为待入库候选，failed 为失败明细（可下载）
const convertVisible = ref(false)
const convertRef = ref(null)
const convert = reactive({ candidates: [], failed: [] })
const converting = ref(false)

/** 预览展开时滚到面板处，避免用户以为「点了没反应」 */
watch(convertVisible, (v) => {
  if (!v) return
  nextTick(() => {
    convertRef.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
})

/** PDF → LLM 转换 → 候选预览（此步不落库） */
const handleConvert = async () => {
  // 1. 置转换态：按钮 loading，避免重复提交
  converting.value = true
  try {
    // 2. 组装上传表单：文件 + 当前词典类型
    const fd = new FormData()
    fd.append('file', importFile.value)
    fd.append('type', activeTab.value)
    // 3. 调后端转换（此步只出候选，不落库）
    const res = await convertDict(fd)
    // 4. 回填候选与失败明细
    convert.candidates = res.data.candidates || []
    convert.failed = res.data.failed || []
    // 5. 一条候选都没有时提醒用户去看失败明细
    if (!convert.candidates.length) {
      ElMessage.warning('未转换出可入库的候选，请查看失败明细')
    }
    // 6. 展开预览面板，并清空已选文件（候选已进预览框）
    convertVisible.value = true
    // 转换成功、候选已进预览框，这时才清空已选文件（UX-28）
    uploadRef.value?.clearFiles()
    importFile.value = null
  } catch {
    // 拦截器已提示，含「转换未启用」的友好文案；失败时保留已选文件以便直接重试
  } finally {
    converting.value = false
  }
}

/** 预览确认 → 候选转成 JSON 文件，复用 JSON 直传入库路径 */
const confirmConvert = async () => {
  // 1. 候选映射成入库结构，剔掉预览用的多余字段
  const payload = convert.candidates.map((c) => ({
    standardTerm: c.standardTerm,
    aliases: c.aliases || [],
    source: c.source || '',
    code: c.code || null
  }))
  // 2. 包成 JSON File，复用「JSON 直传入库」这条路径
  const file = new File(
    [JSON.stringify(payload, null, 2)],
    `converted_${activeTab.value}.json`,
    { type: 'application/json' }
  )
  // 3. 执行入库；成功才收起预览面板
  const ok = await doImport(file)
  if (ok) convertVisible.value = false
}

// 真正入库：非 PDF 与「PDF 转换确认」两条路径共用（后者把候选转成 JSON File 再走这里）
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
    // 4. 提示成功并刷新术语列表
    ElMessage.success(`导入完成：成功 ${res.data.imported} / 共 ${res.data.total}`)
    loadTerms()
    // 5. 返回成功，供调用方决定是否收起预览
    return true
  } catch {
    // 拦截器已提示
    return false
  } finally {
    // 无论成败都复位导入态
    importing.value = false
  }
}

// 失败明细导出为制表符分隔的 txt；前置 BOM 以免 Excel 打开乱码
const downloadFailed = () => {
  const rows = convert.failed.map((f) => `${f.text || ''}\t${f.reason || ''}`).join('\n')
  saveBlob(
    new Blob(['\uFEFF原文\t原因\n' + rows], { type: 'text/plain;charset=utf-8' }),
    `convert_failed_${Date.now()}.txt`
  )
}

// 版本回滚数据
const backups = ref([])
/** 历史版本读取失败：与「确实没有备份」区分开 */
const backupsFailed = ref(false)

/** 较当前增减：正=备份比现在多，负=少，0=一致 */
const deltaText = (d) => {
  const n = Number(d)
  if (Number.isNaN(n)) return '—'
  if (n === 0) return '无变化'
  return n > 0 ? `多 ${n} 条` : `少 ${-n} 条`
}
// 「较当前」的配色类名：非数字或持平走中性，多 / 少分别走 ochre / danger
const deltaClass = (d) => {
  const n = Number(d)
  if (Number.isNaN(n) || n === 0) return 'dl-flat'
  return n > 0 ? 'dl-up' : 'dl-down'
}

// 读取历史版本列表（按当前词典类型）
const loadBackups = async () => {
  try {
    const res = await getBackups({ type: activeTab.value })
    backups.value = res.data.backups || []
    backupsFailed.value = false
  } catch {
    // 原来连 try 都没有；且失败后表头的「暂无历史版本」会让人以为真的没有备份
    backupsFailed.value = true
    backups.value = []
  }
}

// 回滚到指定版本：二次确认 → 覆盖当前词典 → 刷新术语与版本列表
const handleRollback = async (row) => {
  // 1. 二次确认：回滚会覆盖当前词典，取消即整体中止
  if (!(await confirmBox(
    `确定将「${TYPE_LABELS[activeTab.value]}」词典回滚到 ${row.time} 的版本吗？覆盖当前词典并立即生效。`,
    '版本回滚'))) {
    return
  }
  // 2. 调后端按备份文件覆盖当前词典
  const res = await rollback({ type: activeTab.value, backupFilename: row.filename })
  // 3. 提示结果，并刷新术语列表与历史版本
  ElMessage.success(res.msg || '回滚成功')
  loadTerms()
  loadBackups()
}

// 进页面拉取当前类型的术语与历史版本
onMounted(() => {
  loadTerms()
  loadBackups()
})
</script>

<style scoped>
/* 类型 tab：激活态与下划线改用主题墨色，替换 Element Plus 默认蓝 */
.dict-tabs {
  margin-bottom: 4px;
}
.dict-tabs :deep(.el-tabs__item.is-active) {
  color: var(--ink);
}
.dict-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--ink-mid);
}
/* 查询行 / 回滚行：单行水平排布 */
.search-row,
.rollback-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
/* 「较当前」的增减配色：多=ochre、少=danger、无变化=次级色 */
.dl-up { color: var(--ochre); }
.dl-down { color: var(--danger); }
.dl-flat { color: var(--text-sub); }
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
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
  margin-top: 4px;
}
/* 详细格式收进折叠说明，避免一上来把数据结构摊给用户（UX-58）；
   位置在 import-actions 内，不再落在上传热区（UX-78） */
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
  padding: 8px 10px;
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
  gap: 12px;
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
  padding: 6px 12px;
  margin-bottom: 6px;
  font-size: 12.5px;
}

/* ===== PDF 转换预览：同页展开，左右两栏（UX-66 修订） ===== */
.convert-panel {
  margin-top: 14px;
}
/* 面板头部右侧的「关闭预览」 */
.hd-close {
  font-size: 12.5px;
}
/* 转换预览：左候选表（flex:2）、右失败明细与操作（flex:1） */
.convert-body {
  display: flex;
  gap: 22px;
  align-items: flex-start;
}
.convert-main {
  flex: 2;
  min-width: 0;
}
/* 右栏最小宽度 250px，保证失败文案不被挤成竖排 */
.convert-side {
  flex: 1;
  min-width: 250px;
  border-left: 1px solid var(--line);
  padding-left: 22px;
}
/* 右栏小标题 */
.col-hd {
  font-size: 12.5px;
  font-weight: bold;
  color: var(--ink);
  padding-bottom: 6px;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--line);
}
/* 失败项可能上百条，给一个与候选表等高的滚动区；
   页面本身不再出现滚动条 */
.fail-list {
  max-height: 400px;
  overflow-y: auto;
  padding-right: 4px;
}
/* 右栏底部操作按钮 */
.convert-actions {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
/* 候选表上方的汇总说明 */
.convert-hd {
  font-size: 13px;
  color: var(--text-sub);
  line-height: 1.7;
}
.convert-hd b {
  color: var(--ink);
}
@media (max-width: 900px) {
  .convert-body {
    flex-direction: column;
  }
  .convert-side {
    border-left: 0;
    padding-left: 0;
    min-width: 0;
    width: 100%;
  }
}
</style>
