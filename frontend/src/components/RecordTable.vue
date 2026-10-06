<template>
  <!--
    病历列表（共享）。

    为什么抽出来：「病历数据」与「结构化解析」两页的病历列表列定义原本逐字相同
    （连注释都写着「列口径与病历数据一致」），各自维护一份 —— 将来加一列（如本次的
    评分）要改两处，漏一处就出现「同一字段两页显示不一致」。

    两页的差异全部走 props / slot，不在这里写死：
      · 选择列：Records 需要批量操作，NlpExtract 不需要
      · 操作列：各页按钮不同，用 action 插槽由页面自己填
  -->
  <el-table
    :ref="tableRef"
    v-loading="loading"
    :element-loading-text="loadingText"
    :data="rows"
    border
    size="small"
    style="margin-top: var(--sp-3)"
    :max-height="maxHeight"
    :row-class-name="rowClassName"
    :highlight-current-row="highlightCurrent"
    @selection-change="$emit('selection-change', $event)"
    @row-click="(row, col, e) => $emit('row-click', row, col, e)"
  >
    <el-table-column v-if="selectable" type="selection" width="46" />
    <!-- 病历 ID 是 36 字符的 UUID：常态下没人读它，却固定吃掉 320px，
         把「摘要」挤到只剩半行可见（结构化解析页实拍可见：ID 完整、摘要被截）。
         这里只做长度收敛 —— 常态显示前 8 位（支持场景足以口头报出），悬停给全文。
         数据侧另有更可读的 registrationNo / outpatientNo，但它们各有语义（挂号号 / 门诊号），
         不适合冒充「病历 ID」，故不在此处替换。 -->
    <el-table-column label="病历ID" width="120">
      <template #default="{ row }">
        <el-tooltip :content="row.id || '—'" placement="top">
          <span>{{ (row.id || '—').slice(0, 8) }}</span>
        </el-tooltip>
      </template>
    </el-table-column>
    <el-table-column prop="summary" label="摘要" min-width="240" show-overflow-tooltip />
    <!-- 评分与分级成对：分级是结论、评分是量值，只给分级看不出差多少分 -->
    <el-table-column label="评分" width="80" align="center">
      <template #default="{ row }">
        <span v-if="row.score != null" :class="scoreClass(row.score)">{{ row.score }}</span>
        <span v-else class="tip">—</span>
      </template>
    </el-table-column>
    <el-table-column prop="grade" label="分级" width="90" />
    <!-- 人工修改标记：该条结构化数据被人工改过（复核修正 / 手工改结构化数据），
         不是模型原样抽的。标出来是为了评估模型准确率时能排除它。 -->
    <el-table-column label="来源" width="86">
      <template #default="{ row }">
        <el-tooltip
          v-if="row.manuallyEdited"
          content="本条结构化数据含人工修改，清洗归一会跳过；统计模型准确率时请排除"
          placement="top"
        >
          <el-tag size="small" type="warning" effect="plain">人工修改</el-tag>
        </el-tooltip>
        <span v-else class="tip">模型</span>
      </template>
    </el-table-column>
    <!-- 接诊时间：常态只到日，悬停给秒级原值。
         只到日是有意的 —— 演示数据的时间分量是脱敏噪声（57% 落在非门诊时段，
         会出现凌晨 2 点接诊），常态展示等于把噪声摆在列表上 -->
    <el-table-column label="接诊时间" width="110">
      <template #default="{ row }">
        <VisitTimeCell :visit-time="row.visitTime" />
      </template>
    </el-table-column>
    <!-- 年龄/性别：单块自包含，两字段后端追加、向后兼容 -->
    <el-table-column label="年龄/性别" width="110">
      <template #default="{ row }">
        <AgeGenderCell :age="row.age" :gender="row.gender" />
      </template>
    </el-table-column>
    <!-- 操作列由页面自己填：Records 是编辑/删除，NlpExtract 是载入，Qc 是扣分明细 -->
    <el-table-column v-if="$slots.action" label="操作" :width="actionWidth" fixed="right">
      <template #default="scope">
        <slot name="action" v-bind="scope" />
      </template>
    </el-table-column>
    <!-- 空态由页面填（各页文案与失败态不同），没给就退回 Element 默认 -->
    <template v-if="$slots.empty" #empty>
      <slot name="empty" />
    </template>
  </el-table>
</template>

<script setup>
/**
 * 病历列表（共享）：病历ID / 摘要 / 评分 / 分级 / 接诊时间 / 年龄·性别 + 操作插槽。
 *
 * <p>不接管请求与分页状态 —— 那些各页差异大（范围不同、有的要批量选择）。
 * 本组件只负责「列口径」，让同一份病历在所有列表里长得一样。</p>
 */
import { ref } from 'vue'
import VisitTimeCell from '@/components/cells/VisitTimeCell.vue'
import AgeGenderCell from '@/components/cells/AgeGenderCell.vue'

defineProps({
  /** 病历行数组（SearchVO.Item[]） */
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  loadingText: { type: String, default: '正在读取病历…' },
  /** 是否显示多选列 */
  selectable: { type: Boolean, default: false },
  /** 点击行是否高亮（列表页通常要） */
  highlightCurrent: { type: Boolean, default: false },
  rowClassName: { type: [String, Function], default: '' },
  maxHeight: { type: [String, Number], default: 420 },
  /** 操作列宽度：按钮多的一页给大一些 */
  actionWidth: { type: [String, Number], default: 90 }
})

defineEmits(['selection-change', 'row-click'])

const tableRef = ref(null)

// 代理 el-table 的方法：页面拿到的 ref 是本组件，直接 tableRef.value.clearSelection()
// 会是 undefined（?. 让它静默空操作 → 批量删除后选中态不复位）。
// 在这里代理掉，页面代码就不用知道中间隔了一层组件。
const clearSelection = () => tableRef.value?.clearSelection()
const toggleRowSelection = (row, selected) => tableRef.value?.toggleRowSelection(row, selected)

defineExpose({ clearSelection, toggleRowSelection })

// 评分配色：与质控页的合格线口径一致（>=90 合格 / >=60 待复核 / 其余无效）
// 这里只做「一眼看出高低」，阈值以后端规则为准，前端不另立标准。
const scoreClass = (s) => (s >= 90 ? 'score-ok' : s >= 60 ? 'score-mid' : 'score-low')
</script>

<style scoped>
.score-ok { color: var(--ink-mid); font-weight: 600; }
.score-mid { color: var(--ochre); }
.score-low { color: var(--danger); }
</style>