<template>
  <div>
    <PanelCard title="NLP 结构化解析">
      <div class="tip">
        从病历载入或直接粘贴原文 → 执行抽取，得到 9 类结构化实体（模型 NER + 规则兜底）。
        术语归一由「清洗与导出」按最新词典统一处理，本页只做抽取与人工修正。
      </div>

      <div class="load-row">
        <el-input
          v-model="regNo"
          placeholder="输入登记号载入病历原文"
          clearable
          style="width: 240px"
          @keyup.enter="loadRecord"
        />
        <el-button :loading="loadingRaw" @click="loadRecord">载入病历</el-button>
        <!-- 批量入口（UX-52）：原实现只能一条条手动载入+抽取，量一大就不可行 -->
        <el-button @click="batchVisible = true">批量解析</el-button>
        <span v-if="recordId" class="tip">
          已载入病历：<b>{{ loadedLabel }}</b>
        </span>
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
            <!-- 禁用时说明原因，而不是让用户猜（UX-01） -->
            <el-button :disabled="!canSave" @click="save">保存到病历</el-button>
            <span v-if="!recordId" class="tip">先载入一份病历才能保存</span>
            <span v-else-if="!result" class="tip">先执行抽取才能保存</span>
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

    <!-- 批量解析（UX-52）：按范围取前 N 条逐条抽取并保存，带进度与取消 -->
    <el-dialog v-model="batchVisible" title="批量结构化解析" width="min(560px, 92vw)">
      <div class="tip">
        对病历列表逐条执行抽取并保存到病历。条数较多时耗时较长，<b>请勿关闭页面</b>；
        需要中断可点「取消」，已处理的不回滚。
      </div>

      <div class="batch-row">
        <span>处理条数</span>
        <el-input-number v-model="batchLimit" :min="1" :max="500" size="small" :disabled="batchRunning" />
        <span class="tip">（取病历列表前 N 条）</span>
      </div>

      <div v-if="batchRunning || batchProgress.done" class="batch-progress">
        <div class="bp-hd">
          正在处理第 {{ Math.min(batchProgress.done + 1, batchProgress.total) }}/{{ batchProgress.total }} 条：
          <b>{{ batchProgress.current || '准备中…' }}</b>
        </div>
        <el-progress
          :percentage="batchProgress.total ? Math.round((batchProgress.done / batchProgress.total) * 100) : 0"
          :stroke-width="10"
        />
        <div class="bp-sub">成功 {{ batchProgress.success }} 条，失败 {{ batchProgress.failed }} 条</div>
      </div>

      <template #footer>
        <el-button v-if="batchRunning" @click="batchCancelled = true">取消</el-button>
        <el-button v-else @click="batchVisible = false">关闭</el-button>
        <el-button v-if="!batchRunning" type="primary" @click="runBatch">开始批量解析</el-button>
      </template>
    </el-dialog>

    <!-- 同登记号命中多条时由用户选择，不默认取第一条（UX-39） -->
    <el-dialog v-model="pickVisible" title="登记号命中多份病历" width="min(620px, 92vw)">
      <div class="tip">该登记号匹配到 {{ matches.length }} 份病历，请选择要载入的一份：</div>
      <el-table :data="matches" border size="small" max-height="320" style="margin-top: 10px">
        <el-table-column prop="id" label="病历ID" width="300" show-overflow-tooltip />
        <el-table-column prop="summary" label="摘要" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="pickRecord(row.id)">载入</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StructuredDataCard from '@/components/StructuredDataCard.vue'
import { extractNlp } from '@/api/nlp'
import { searchRecords, getRawRecord, updateRecord } from '@/api/records'

const regNo = ref('')
const recordId = ref('')
/** 已载入病历的展示标识（优先登记号），保存确认与成功提示都要回显它（UX-01） */
const loadedLabel = ref('')
const text = ref('')
const result = ref(null)
const loadingRaw = ref(false)
const extracting = ref(false)

const matches = ref([])
const pickVisible = ref(false)

const canSave = computed(() => !!recordId.value && !!result.value)

const PARTS = ['chiefComplaint', 'selfReport', 'presentIllness', 'inspection', 'tongue', 'pulse',
  'physicalExam', 'tcmDiagnosis', 'pattern', 'prescription', 'followUp', 'treatmentEffect']

const composeText = (raw) => PARTS
  .map((k) => raw[k])
  .filter((s) => s && String(s).trim())
  .map((s) => String(s).trim())
  .join('。')

/** 载入一份病历：换病历时必须清空上一次抽取结果，否则会把 A 的结果存进 B（UX-01） */
const applyRecord = async (id) => {
  const raw = await getRawRecord(id)
  recordId.value = id
  loadedLabel.value = raw.data?.registrationNo || id
  text.value = composeText(raw.data)
  result.value = null
}

const loadRecord = async () => {
  if (!regNo.value) return
  loadingRaw.value = true
  try {
    const res = await searchRecords({ registrationNo: regNo.value, page: 1, pageSize: 20 })
    const list = res.data?.records || []
    if (!list.length) {
      ElMessage.warning('未找到该登记号对应病历')
      return
    }
    if (list.length > 1) {
      // 后端按登记号模糊匹配，可能同时命中「A01」与「A010」；交给用户选（UX-39）
      matches.value = list
      pickVisible.value = true
      return
    }
    await applyRecord(list[0].id)
  } catch {
    // 拦截器已提示
  } finally {
    loadingRaw.value = false
  }
}

const pickRecord = async (id) => {
  pickVisible.value = false
  loadingRaw.value = true
  try {
    await applyRecord(id)
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
  const label = loadedLabel.value || recordId.value
  try {
    await ElMessageBox.confirm(
      `将本次抽取结果写入病历「${label}」的结构化数据，覆盖原有内容。确认？`,
      '保存到病历',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await updateRecord(recordId.value, { structuredData: result.value })
    ElMessage.success(`已保存到病历「${label}」`)
  } catch {
    // 拦截器已提示
  }
}

// ===== 批量解析（UX-52）=====
const batchVisible = ref(false)
const batchLimit = ref(50)
const batchRunning = ref(false)
const batchCancelled = ref(false)
const batchProgress = reactive({ done: 0, total: 0, current: '', success: 0, failed: 0 })

/**
 * 按「病历列表前 N 条」逐条抽取并保存。
 *
 * <p>后端暂无批量接口，这里在前端串行推进 —— 相比人工逐条点开仍是质变，
 * 且能给出真实进度与取消入口。若后续补批量任务接口，替换此循环即可。</p>
 */
const runBatch = async () => {
  batchRunning.value = true
  batchCancelled.value = false
  Object.assign(batchProgress, { done: 0, total: 0, current: '', success: 0, failed: 0 })
  try {
    const res = await searchRecords({ page: 1, pageSize: batchLimit.value })
    const list = res.data?.records || []
    if (!list.length) {
      ElMessage.warning('没有可解析的病历')
      return
    }
    batchProgress.total = list.length
    for (let i = 0; i < list.length; i++) {
      if (batchCancelled.value) break
      const item = list[i]
      batchProgress.current = item.registrationNo || item.id
      try {
        const raw = await getRawRecord(item.id)
        const text = composeText(raw.data || {})
        if (!text) {
          batchProgress.failed += 1
        } else {
          const ex = await extractNlp({ text })
          await updateRecord(item.id, { structuredData: ex.data })
          batchProgress.success += 1
        }
      } catch {
        // 单条失败不影响后续
        batchProgress.failed += 1
      }
      batchProgress.done = i + 1
    }
    const tail = batchCancelled.value ? '（已取消，未处理剩余病历）' : ''
    ElMessage.success(`批量解析完成：成功 ${batchProgress.success} 条，失败 ${batchProgress.failed} 条${tail}`)
  } catch {
    // 拦截器已提示
  } finally {
    batchRunning.value = false
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
.actions { margin-top: 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
/* 批量解析（UX-52） */
.batch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 14px 0 4px;
}
.batch-progress {
  margin-top: 14px;
  padding: 10px 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
}
.bp-hd {
  font-size: 12.5px;
  color: var(--text);
  margin-bottom: 8px;
}
.bp-hd b {
  color: var(--ink);
}
.bp-sub {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-sub);
}
@media (max-width: 1200px) { .split { grid-template-columns: 1fr; } }
</style>
