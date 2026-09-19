<template>
  <div>
    <PanelCard title="NLP 结构化解析">
      <div class="tip">
        从病历载入或直接粘贴原文 → 执行抽取，得到 9 类结构化实体（模型 NER + 规则兜底）。
        术语归一由「清洗与导出」按最新词典统一处理，本页只做抽取与人工修正。
      </div>

      <div class="load-row">
        <el-input v-model="regNo" placeholder="输入登记号载入病历原文" clearable style="width: 240px" @keyup.enter="loadRecord" />
        <el-button :loading="loadingRaw" @click="loadRecord">载入病历</el-button>
        <span v-if="recordId" class="tip">已载入病历：{{ recordId }}</span>
      </div>

      <div class="split">
        <div class="pane">
          <div class="pane-hd">原文</div>
          <el-input
            v-model="text"
            type="textarea"
            :rows="14"
            placeholder="病历原文（自由文本）"
          />
          <div class="actions">
            <el-button type="primary" :loading="extracting" :disabled="!text" @click="runExtract">执行抽取</el-button>
            <el-button :disabled="!recordId || !result" @click="save">保存到病历</el-button>
          </div>
        </div>

        <div class="pane">
          <div class="pane-hd">
            抽取结果
            <span v-if="result" class="src-note" :class="{ warn: !result.modelAvailable }">
              {{ result.modelAvailable ? '模型抽取 + 规则兜底' : 'NLP 服务未就绪（降级：可能仅规则兜底或为空）' }}
            </span>
          </div>
          <StructuredDataCard v-if="result" :data="result" />
          <el-empty v-else description="尚未抽取" :image-size="80" />
        </div>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StructuredDataCard from '@/components/StructuredDataCard.vue'
import { extractNlp } from '@/api/nlp'
import { searchRecords, getRawRecord, updateRecord } from '@/api/records'

const regNo = ref('')
const recordId = ref('')
const text = ref('')
const result = ref(null)
const loadingRaw = ref(false)
const extracting = ref(false)

const PARTS = ['chiefComplaint', 'selfReport', 'presentIllness', 'inspection', 'tongue', 'pulse',
  'physicalExam', 'tcmDiagnosis', 'pattern', 'prescription', 'followUp', 'treatmentEffect']

const composeText = (raw) => PARTS
  .map((k) => raw[k])
  .filter((s) => s && String(s).trim())
  .map((s) => String(s).trim())
  .join('。')

const loadRecord = async () => {
  if (!regNo.value) return
  loadingRaw.value = true
  try {
    const res = await searchRecords({ registrationNo: regNo.value, page: 1, pageSize: 1 })
    const first = res.data?.records?.[0]
    if (!first) {
      ElMessage.warning('未找到该登记号对应病历')
      return
    }
    const raw = await getRawRecord(first.id)
    recordId.value = first.id
    text.value = composeText(raw.data)
  } catch {
    // 拦截器已提示
  } finally {
    loadingRaw.value = false
  }
}

const runExtract = async () => {
  extracting.value = true
  try {
    const res = await extractNlp({ text: text.value })
    result.value = res.data
    if (!res.data.modelAvailable) ElMessage.warning('NLP 服务未就绪，结果为降级输出')
  } catch {
    // 拦截器已提示
  } finally {
    extracting.value = false
  }
}

const save = async () => {
  try {
    await updateRecord(recordId.value, { structuredData: result.value })
    ElMessage.success('已保存到病历')
  } catch {
    // 拦截器已提示
  }
}
</script>

<style scoped>
.tip { font-size: 12.5px; color: var(--text-sub); line-height: 1.7; }
.load-row { display: flex; gap: 12px; align-items: center; margin: 12px 0; }
.split { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.pane-hd { font-size: 13px; font-weight: bold; color: var(--ink); margin-bottom: 8px; }
.src-note { font-size: 11.5px; color: var(--ink-mid); font-weight: normal; margin-left: 8px; }
.src-note.warn { color: var(--danger); }
.actions { margin-top: 12px; display: flex; gap: 10px; }
@media (max-width: 1200px) { .split { grid-template-columns: 1fr; } }
</style>
