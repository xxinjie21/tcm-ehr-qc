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
        <el-button type="primary" @click="loadTerms">查 询</el-button>
        <span class="tip">共 {{ terms.length }} 条（内存词典实时查询）</span>
      </div>
      <el-table :data="terms" border stripe style="margin-top: 12px" max-height="360">
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
      </el-table>
    </PanelCard>

    <PanelCard title="术语库导入">
      <div class="import-row">
        <el-upload
          ref="uploadRef"
          drag
          :auto-upload="false"
          :limit="1"
          :on-change="onFileChange"
          :on-remove="() => (importFile = null)"
          accept=".xlsx,.xls,.csv"
        >
          <div class="upload-tip">
            拖拽文件到此处，或 <em>点击选择</em>
            <div class="sub">仅支持 Excel(.xlsx/.xls) 或 CSV，需包含「标准术语」「别名」两列（别名可用 、或, 分隔多个）</div>
          </div>
        </el-upload>
        <div class="import-actions">
          <el-button type="primary" :loading="importing" :disabled="!importFile" @click="handleImport">开始导入</el-button>
          <div class="tip" style="margin-top: 8px">导入时自动备份当前词典到 backup/ 目录，可在下方回滚。</div>
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
        <span class="tip">回滚将使用备份文件覆盖当前词典，并重建内存缓存与 ES 索引。</span>
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
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import { getTerms, importDict, rollback, getBackups } from '@/api/dictionary'

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
  loadTerms()
})

const uploadRef = ref(null)
const importFile = ref(null)
const importing = ref(false)
const importResult = ref(null)

const onFileChange = (file) => {
  importFile.value = file.raw
}

const handleImport = async () => {
  if (!importFile.value) return
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', importFile.value)
    fd.append('type', activeTab.value)
    const res = await importDict(fd)
    importResult.value = res.data
    ElMessage.success(`导入完成：成功 ${res.data.imported} / 共 ${res.data.total}`)
    loadTerms()
  } finally {
    importing.value = false
    uploadRef.value?.clearFiles()
    importFile.value = null
  }
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
</style>
