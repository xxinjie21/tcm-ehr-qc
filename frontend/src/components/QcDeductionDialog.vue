<template>
  <!-- 扣分明细弹窗：评分/分级 + 评分构成瀑布 + 扣分明细表。
       从 Qc.vue 抽出：纯展示，只依赖父页传入的 detail 快照与合格线。 -->
  <el-dialog
    :model-value="modelValue"
    title="规则预检单（扣分明细）"
    width="min(1080px, 94vw)"
    top="7vh"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <!-- 弹窗内容单栏：评分 / 分级 → 评分构成瀑布 → 扣分明细表 -->
    <div v-if="detail">
      <div class="ded-col">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="评分">{{ detail.score }}</el-descriptions-item>
          <el-descriptions-item label="分级">{{ detail.grade }}</el-descriptions-item>
        </el-descriptions>

        <!-- 评分构成瀑布：100 分起逐项扣到最终分，一眼看分扣在哪 -->
        <div class="sd-title">评分构成（100 分起，逐项扣）</div>
        <div class="wf">
          <div class="wf-item"><span class="wf-l">总分</span><b class="wf-num">100</b></div>
          <div v-for="(d, i) in detail.deductions" :key="i" class="wf-item">
            <span class="wf-l">{{ d.type }} · {{ d.item }}</span>
            <b class="wf-num neg">-{{ d.points }}</b>
          </div>
          <div class="wf-item end">
            <span class="wf-l">最终得分</span>
            <b class="wf-num">{{ detail.score }}</b>
            <span class="wf-grade" :class="gradeClass(detail.grade)">{{ detail.grade }}</span>
          </div>
        </div>
        <div class="wf-note">
          合格线 {{ qualifiedText }} 分
          <template v-if="distanceToQualified != null">，距合格线还差 {{ distanceToQualified }} 分</template>
        </div>

        <div class="sd-title">扣分明细（合计 -{{ detailTotal }} 分）</div>
        <el-table :data="detail.deductions" border size="small" max-height="340">
          <el-table-column prop="type" label="类型" width="110" />
          <el-table-column prop="item" label="项" width="90" />
          <el-table-column prop="points" label="扣分" width="70">
            <template #default="{ row }">-{{ row.points }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <template #empty><div class="ok">无扣分项</div></template>
        </el-table>
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 扣分明细弹窗：detail 由父页在打开前取好；合格线随规则变化传入，
// 阈值未就绪时统一显示「—」，不再退回 90（那个 90 是后端出厂默认值的拷贝）。
import { computed } from 'vue'
import { gradeClass as gradeClassOf, THRESHOLD_PLACEHOLDER } from '@/utils/grade'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  detail: { type: Object, default: null },
  qualified: { type: Number, default: null }
})
const emit = defineEmits(['update:modelValue'])

// 分级 → 样式类：读 utils/grade.js 的唯一副本
const gradeClass = gradeClassOf

const qualifiedText = computed(() =>
  props.qualified == null ? THRESHOLD_PLACEHOLDER : props.qualified
)
const distanceToQualified = computed(() =>
  props.qualified == null ? null : Math.max(0, props.qualified - (props.detail?.score ?? 0))
)
// 扣分合计：弹窗里直接给出，省得用户在长表里自己加
const detailTotal = computed(() =>
  (props.detail?.deductions || []).reduce((s, d) => s + (d.points || 0), 0)
)
</script>

<style scoped>
/* 弹窗内的小节标题 */
.sd-title {
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  margin: 14px 0 var(--sp-2);
}
/* 扣分明细弹窗的内容列 */
.ded-col {
  min-width: 0;
}
.ded-col .sd-title:first-child {
  margin-top: 0;
}
/* 「无扣分项」等正向文案 */
.ok {
  padding: var(--sp-2);
  color: var(--ink-mid);
  font-size: var(--fs-md);
}
/* ===== 评分构成瀑布 ===== */
.wf {
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 6px 10px;
  margin-bottom: var(--sp-2);
  background: var(--paper);
}
/* 瀑布单行：标签 + 数值（扣分用 danger） */
.wf-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 3px 0;
  font-size: var(--fs-md);
}
.wf-item .wf-l {
  flex: 1 1 auto;
  color: var(--ink);
}
.wf-item .wf-num {
  flex: 0 0 auto;
  color: var(--ink);
}
.wf-item .wf-num.neg {
  color: var(--danger);
}
.wf-item.end {
  border-top: 1px solid var(--line);
  margin-top: var(--sp-1);
  padding-top: 6px;
}
/* P4.11：最终得分要突出扫读，字号比扣分项（默认）放大一档加粗 */
.wf-item.end .wf-num {
  font-size: 17px;
  font-weight: 700;
}
/* 最终得分旁的分级胶囊 */
.wf-grade {
  font-size: var(--fs-sm);
  padding: 1px var(--sp-2);
  border-radius: 6px;
}
.wf-grade.is-ok {
  background: var(--ink-light);
  color: var(--ink);
}
.wf-grade.is-mid {
  background: var(--ochre-light);
  color: #8a6a44;
}
.wf-grade.is-bad {
  background: var(--danger-surface);
  color: #8a3d33;
}
/* 合格线说明 */
.wf-note {
  font-size: var(--fs-sm);
  color: var(--text-sub);
  margin-bottom: 10px;
}
</style>
