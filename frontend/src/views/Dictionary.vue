<template>
  <div>
    <el-tabs v-model="activeTab" class="dict-tabs">
      <el-tab-pane label="疾病" name="disease" />
      <el-tab-pane label="证候" name="pattern" />
      <el-tab-pane label="症状" name="symptom" />
      <el-tab-pane label="中药" name="herb" />
      <el-tab-pane label="方剂" name="formula" />
    </el-tabs>

    <PanelCard :title="`术语查询（${typeLabel}）`">
      <div class="search-row">
        <el-input
          v-model="keyword"
          placeholder="输入术语或别名关键字，模糊匹配"
          clearable
          style="width: 320px"
          @keyup.enter="loadTerms"
        />
        <el-button type="primary" :loading="loadingTerms" @click="loadTerms">查 询</el-button>
        <span class="tip">共 {{ terms.length }} 条（内存词典实时查询）</span>
      </div>
      <el-table v-loading="loadingTerms" :data="terms" border stripe style="margin-top: 12px" max-height="360">
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
            <div class="sub">
              支持 Excel(.xlsx/.xls) / CSV / JSON；PDF 可智能转换为术语
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
        </el-upload>
        <div class="import-actions">
          <el-button type="primary" :loading="importing || converting" :disabled="!importFile" @click="handleImport">
            {{ isPdfFile ? '智能转换（预览）' : '开始导入' }}
          </el-button>
          <div class="tip" style="margin-top: 8px">导入前会自动备份，可在下方「版本回滚」恢复。</div>
        </div>
      </div>
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

    <PanelCard title="版本回滚">
      <div class="rollback-row">
        <span class="tip">回滚会用该备份覆盖当前词典，立即生效。</span>
        <el-button size="small" @click="loadBackups">刷新备份列表</el-button>
      </div>
      <el-table :data="backups" border style="margin-top: 12px" max-height="280">
        <el-table-column prop="filename" label="备份文件" min-width="280" />
        <el-table-column prop="time" label="备份时间" width="180" />
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

    <!-- PDF 智能转换预览：预览阶段不落库，确认后才写入 -->
    <el-dialog v-model="convertVisible" title="PDF 转换预览" width="min(860px, 92vw)">
      <div class="convert-hd">
        转换出候选 <b>{{ convert.candidates.length }}</b> 条，失败 <b>{{ convert.failed.length }}</b> 条。
        确认后将写入【{{ typeLabel }}】词典（入库前自动备份，可在「版本回滚」恢复）。
      </div>

      <el-table :data="convert.candidates" border size="small" max-height="320" style="margin-top: 10px">
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
        <el-table-column prop="source" label="来源" width="170" show-overflow-tooltip />
        <el-table-column prop="code" label="国标代码" width="110" />
        <template #empty>
          <el-empty description="没有可入库的候选" :image-size="70" />
        </template>
      </el-table>

      <div v-if="convert.failed.length" class="failures" style="margin-top: 12px">
        <div class="ded-hd">失败明细（{{ convert.failed.length }} 条）：</div>
        <div v-for="(f, i) in convert.failed.slice(0, 8)" :key="i" class="ded-item">
          <span>{{ f.reason }}{{ f.text ? '：' + f.text : '' }}</span>
        </div>
        <div v-if="convert.failed.length > 8" class="tip">…另有 {{ convert.failed.length - 8 }} 条，请下载核对</div>
      </div>

      <template #footer>
        <el-button :disabled="!convert.failed.length" @click="downloadFailed">下载失败明细</el-button>
        <el-button @click="convertVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="importing"
          :disabled="!convert.candidates.length"
          @click="confirmConvert"
        >确认入库（{{ convert.candidates.length }} 条）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox, genFileId } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import { getTerms, importDict, convertDict, rollback, getBackups } from '@/api/dictionary'
import { saveBlob } from '@/utils/download'

const TYPE_LABELS = { disease: '疾病', pattern: '证候', symptom: '症状', herb: '中药', formula: '方剂' }

const activeTab = ref('symptom')
const typeLabel = computed(() => TYPE_LABELS[activeTab.value])

const keyword = ref('')
const terms = ref([])
const loadingTerms = ref(false)

const loadTerms = async () => {
  loadingTerms.value = true
  try {
    const res = await getTerms({ type: activeTab.value, keyword: keyword.value })
    terms.value = res.data.terms || []
  } finally {
    loadingTerms.value = false
  }
}

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

const uploadRef = ref(null)
const dictFileList = ref([])
const importFile = ref(null)
const importing = ref(false)
const importResult = ref(null)

const isPdfFile = computed(() => (importFile.value?.name || '').toLowerCase().endsWith('.pdf'))

const MAX_FILE_MB = 50
const ALLOWED_EXT = ['.xlsx', '.xls', '.csv', '.json', '.pdf']

/** 预校验扩展名与大小，不合格直接剔除并说明原因（UX-27） */
const rejectFile = (raw, reason) => {
  ElMessage.error(`「${raw.name}」${reason}`)
  dictFileList.value = []
  importFile.value = null
}

const onFileChange = (file) => {
  const raw = file.raw
  if (!raw) return
  const name = (raw.name || '').toLowerCase()
  if (!ALLOWED_EXT.some((ext) => name.endsWith(ext))) {
    rejectFile(raw, `格式不支持，仅支持 ${ALLOWED_EXT.join(' / ')}`)
    return
  }
  if (raw.size > MAX_FILE_MB * 1024 * 1024) {
    rejectFile(raw, `超过 ${MAX_FILE_MB}MB 上限`)
    return
  }
  importFile.value = raw
}

const onFileRemove = () => {
  importFile.value = null
}

/** limit=1 时再次选择会走这里；主动替换旧文件，避免「换了文件却没反应」（UX-28） */
const onFileExceed = (files) => {
  const file = files[0]
  uploadRef.value?.clearFiles()
  dictFileList.value = []
  importFile.value = null
  if (file) {
    file.uid = genFileId()
    uploadRef.value?.handleStart(file)
  }
}

/** 统一入口：PDF 先走智能转换出预览，其余格式直接入库 */
const handleImport = async () => {
  if (!importFile.value) return
  if (isPdfFile.value) {
    await handleConvert()
    return
  }
  const ok = await ElMessageBox.confirm(
    `确定用「${importFile.value.name}」覆盖【${typeLabel.value}】词典吗？将处理全部【${typeLabel.value}】词典，导入前会自动备份，可在下方「版本回滚」恢复。`,
    '术语库导入',
    { type: 'warning', confirmButtonText: '确认导入', cancelButtonText: '取消' }
  ).catch(() => false)
  if (!ok) return
  await doImport(importFile.value)
  uploadRef.value?.clearFiles()
  importFile.value = null
}

const convertVisible = ref(false)
const convert = reactive({ candidates: [], failed: [] })
const converting = ref(false)

/** PDF → LLM 转换 → 候选预览（此步不落库） */
const handleConvert = async () => {
  converting.value = true
  try {
    const fd = new FormData()
    fd.append('file', importFile.value)
    fd.append('type', activeTab.value)
    const res = await convertDict(fd)
    convert.candidates = res.data.candidates || []
    convert.failed = res.data.failed || []
    if (!convert.candidates.length) {
      ElMessage.warning('未转换出可入库的候选，请查看失败明细')
    }
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
  const payload = convert.candidates.map((c) => ({
    standardTerm: c.standardTerm,
    aliases: c.aliases || [],
    source: c.source || '',
    code: c.code || null
  }))
  const file = new File(
    [JSON.stringify(payload, null, 2)],
    `converted_${activeTab.value}.json`,
    { type: 'application/json' }
  )
  const ok = await doImport(file)
  if (ok) convertVisible.value = false
}

const doImport = async (file) => {
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('type', activeTab.value)
    const res = await importDict(fd)
    importResult.value = res.data
    ElMessage.success(`导入完成：成功 ${res.data.imported} / 共 ${res.data.total}`)
    loadTerms()
    return true
  } catch {
    // 拦截器已提示
    return false
  } finally {
    importing.value = false
  }
}

const downloadFailed = () => {
  const rows = convert.failed.map((f) => `${f.text || ''}\t${f.reason || ''}`).join('\n')
  saveBlob(
    new Blob(['\uFEFF原文\t原因\n' + rows], { type: 'text/plain;charset=utf-8' }),
    `convert_failed_${Date.now()}.txt`
  )
}

const backups = ref([])

const loadBackups = async () => {
  const res = await getBackups({ type: activeTab.value })
  backups.value = res.data.backups || []
}

const handleRollback = async (row) => {
  await ElMessageBox.confirm(
    `确定将「${TYPE_LABELS[activeTab.value]}」词典回滚到 ${row.filename} 吗？`,
    '版本回滚',
    { type: 'warning' }
  )
  const res = await rollback({ type: activeTab.value, backupFilename: row.filename })
  ElMessage.success(res.msg || '回滚成功')
  loadTerms()
  loadBackups()
}

onMounted(() => {
  loadTerms()
  loadBackups()
})
</script>

<style scoped>
.dict-tabs {
  margin-bottom: 4px;
}
.dict-tabs :deep(.el-tabs__item.is-active) {
  color: var(--ink);
}
.dict-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--ink-mid);
}
.search-row,
.rollback-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.tip {
  font-size: 12.5px;
  color: var(--text-sub);
}
.import-row {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}
.upload-tip {
  font-size: 13px;
  color: var(--text);
}
.upload-tip .sub {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 4px;
}
/* 详细格式收进折叠说明，避免一上来把数据结构摊给用户（UX-58） */
.fmt-detail {
  margin-top: 6px;
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
.import-actions {
  flex: 1;
}
.import-result {
  display: flex;
  gap: 12px;
  margin-top: 14px;
  align-items: flex-start;
}
.failures {
  flex: 1;
}
.ded-hd {
  font-size: 12.5px;
  color: var(--text-sub);
  margin-bottom: 6px;
}
.ded-item {
  background: var(--ochre-light);
  border-radius: 2px;
  padding: 6px 12px;
  margin-bottom: 6px;
  font-size: 12.5px;
}
.convert-hd {
  font-size: 13px;
  color: var(--text-sub);
  line-height: 1.7;
}
.convert-hd b {
  color: var(--ink);
}
</style>
