<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="min(1180px, 94vw)"
    top="6vh"
    class="record-detail-dialog"
  >
    <!-- 左右两栏（UX-65）：左＝原始 21 字段只读，右＝结构化数据 + AI 解读，同屏可比对；
         改为弹窗后（UX-69 / UX-77）不再占用页面纵向空间 -->
    <div v-if="record" class="detail-2col">
      <div class="detail-col">
        <div class="col-hd">原始字段</div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item v-for="f in FIELDS" :key="f.key" :label="f.label" :span="f.wide ? 2 : 1">
            {{ fieldOf(record, f.key) || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="评分">{{ record.score ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="分级">{{ record.grade || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <div class="detail-col">
        <div class="col-hd">结构化数据（术语已归一，sourceText 为原文溯源）</div>
        <StructuredDataCard :data="record.structuredData" />
        <AiInterpretCard :record-id="record.id" />
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import StructuredDataCard from './StructuredDataCard.vue'
import AiInterpretCard from './AiInterpretCard.vue'

defineProps({
  /** 弹窗标题：病历数据页用「病历详情（原始字段只读）」，治理页用「病历完整详情」 */
  title: { type: String, default: '病历详情（原始字段只读）' },
  /** 病历行对象，需含 21 个原始字段 + structuredData + score/grade */
  record: { type: Object, default: null }
})

const visible = defineModel({ type: Boolean, default: false })

const FIELDS = [
  { key: 'registrationNo', label: '登记号' },
  { key: 'outpatientNo', label: '门诊号' },
  { key: 'gender', label: '性别' },
  { key: 'age', label: '年龄' },
  { key: 'visitCount', label: '就诊次数' },
  { key: 'westernDiagnosis', label: '西医诊断', wide: true },
  { key: 'tcmDiagnosis', label: '中医诊断', wide: true },
  { key: 'chiefComplaint', label: '主诉', wide: true },
  { key: 'selfReport', label: '自诉', wide: true },
  { key: 'presentIllness', label: '现病史', wide: true },
  { key: 'inspection', label: '望诊', wide: true },
  { key: 'pulse', label: '脉诊' },
  { key: 'tongue', label: '舌诊', wide: true },
  { key: 'physicalExam', label: '查体', wide: true },
  { key: 'pattern', label: '辨证结论', wide: true },
  { key: 'prescription', label: '草药', wide: true },
  { key: 'followUp', label: '随访', wide: true },
  { key: 'treatmentEffect', label: '治疗效果' },
  { key: 'department', label: '开单科室' },
  { key: 'doctorId', label: '医生工号' },
  { key: 'visitTime', label: '接诊时间' }
]

const fieldOf = (row, key) => {
  if (!row) return ''
  if (key === 'visitTime') {
    return row.visitTime ? String(row.visitTime).replace('T', ' ').substring(0, 19) : ''
  }
  return row[key]
}
</script>

<style scoped>
.detail-2col {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}
.detail-col {
  min-width: 0;
}
.col-hd {
  font-size: 12.5px;
  color: var(--text-sub);
  margin-bottom: 8px;
}
@media (max-width: 900px) {
  .detail-2col {
    grid-template-columns: 1fr;
  }
}
</style>
