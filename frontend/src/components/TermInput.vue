<template>
  <!--
    术语输入框（人工复核用）：绑定的是**自由文本**，一个字段可填「肝郁、脾虚」，
    提交时由 buildCorrected 按分隔符拆开 —— 所以这里不能用 el-select，
    否则会退化成「一个字段只能选一个词」。

    候选取数见 composables/useTermOptions.js（与质控规则配置共用）。
    与原版的差别：展开/聚焦会**预载一批候选**，点开不再是空的了
    （原先 el-autocomplete 只有输入才出候选，用户不知道这里能搜标准术语）。

    性能审查 P1-3#3：候选一律标注「词典」，并给输入框挂一条说明 —— 证候筛选在 SQL 侧是
    `pattern LIKE '%词%'`（前导通配，B+Tree 索引无效）。从下拉里**选**词典标准术语就落到
    等值匹配，比手输半个词更快也更准，从源头减少模糊查询。
  -->
  <el-tooltip
    placement="top"
    content="从下拉里选词典标准术语：检索走等值匹配（更快、更准）；手输关键词会按模糊匹配处理"
  >
    <el-autocomplete
      :model-value="modelValue"
      :fetch-suggestions="fetchSuggestions"
      :placeholder="placeholder"
      :trigger-on-focus="false"
      value-key="label"
      clearable
      style="width: 100%"
      @focus="preload"
      @update:model-value="$emit('update:modelValue', $event)"
    >
      <!-- 每条候选都来自词典（getTerms → standardTerm），显式标出来，
           让「选」比「手输」更显然 —— 手输半个词会退化成前导通配的模糊查询 -->
      <template #default="{ item }">
        <span class="term-option">
          <span class="term-option__text">{{ item.label }}</span>
          <el-tag size="small" type="info" effect="plain">词典</el-tag>
        </span>
      </template>
    </el-autocomplete>
  </el-tooltip>
</template>

<script setup>
import { useTermOptions } from '@/composables/useTermOptions'

const props = defineProps({
  modelValue: { type: String, default: '' },
  type: { type: String, required: true },
  placeholder: { type: String, default: '输入关键词，从词典选标准术语' }
})

defineEmits(['update:modelValue'])

const { options, searchWith, preload } = useTermOptions(props.type)

// el-autocomplete 的取候选回调（同步契约）：
// 空输入 → 直接给预载的那批（点开就有内容）；有输入 → 防抖取回后再 cb，
// 因为它拿到什么就渲染什么、不会等我们异步刷新。
const fetchSuggestions = (keyword, cb) => {
  const kw = String(keyword || '').trim()
  if (!kw) {
    cb(options.value)
    return
  }
  searchWith(kw, cb)
}

</script>

<style scoped>
/* 候选行：词名占满剩余宽度（过长省略），右侧固定放「词典」标记 */
.term-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.term-option__text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
