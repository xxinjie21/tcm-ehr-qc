<template>
  <!-- 宽度 min(1180px, 94vw) 保证窄屏不横向溢出；top 6vh 让弹窗偏上，
       避免长内容把底部页脚顶出视口 -->
  <el-dialog
    v-model="visible"
    :title="title"
    width="min(1180px, 94vw)"
    top="6vh"
    class="record-detail-dialog"
  >
    <!-- 左右两栏：左＝原始 21 字段只读，右＝结构化数据 + AI 解读，同屏可比对；
         改为弹窗后不再占用页面纵向空间 -->
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
        <div class="col-hd">结构化数据（术语已归一，灰色小字为归一前原文）</div>
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
// 病历详情弹窗（共用）：病历数据页与清洗页都用它，差别只在标题。
// 左栏为原始 21 字段只读，右栏为结构化数据 + AI 解读，左右同屏可比对。
import StructuredDataCard from './StructuredDataCard.vue'
import AiInterpretCard from './AiInterpretCard.vue'
import { fieldOf } from '@/utils/format'
import { fieldsWithWide } from '@/utils/recordFields'

defineProps({
  // 弹窗标题：病历数据页用「病历详情（原始字段只读）」，清洗页用「病历完整详情」
  title: { type: String, default: '病历详情（原始字段只读）' },
  // 病历行对象，需含 21 个原始字段 + structuredData + score/grade
  record: { type: Object, default: null }
})

// 显隐由父组件 v-model 控制（defineModel），本组件不持有开关状态
const visible = defineModel({ type: Boolean, default: false })

// 左栏「原始字段」的展示清单，与 Excel 原始列一一对应（共 21 项）。
// wide = 该字段内容较长，在描述列表里占满两列（span 2）。
// 字段定义收敛到 @/utils/recordFields（P3.5）；详情弹窗的整行集合
const FIELDS = fieldsWithWide([
  'westernDiagnosis', 'tcmDiagnosis', 'chiefComplaint', 'selfReport', 'presentIllness',
  'inspection', 'tongue', 'physicalExam', 'pattern', 'prescription', 'followUp'
])

// 取字段值：visitTime 后端下发的是 ISO 串（含 T），这里换成「日期 时间」可读形式；</script>

<style scoped>
/* 左右两栏：左＝原始 21 字段只读，右＝结构化数据 + AI 解读，同屏可比对；
   改为弹窗后不再占用页面纵向空间。
   内容超长时只让两栏内部滚动：页脚「关闭」始终留在视口内，
   不会出现「要看关闭按钮还得先滚到最底」 */
.detail-2col {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: start;
  max-height: calc(86vh - 140px);
  overflow: auto;
}
/* min-width:0 让 grid 子项可收缩：grid 子项默认 min-width:auto，
   长文本（如现病史）会撑破两栏宽度 */
.detail-col {
  min-width: 0;
}
/* 栏标题：小字次级色，只作分区提示 */
.col-hd {
  font-size: var(--fs-md);
  color: var(--text-sub);
  margin-bottom: var(--sp-2);
}
/* 窄屏（<900px）两栏塌成单列，避免每栏过窄导致长文本逐字换行 */
@media (max-width: 900px) {
  .detail-2col {
    grid-template-columns: 1fr;
  }
}
</style>
