<template>
  <!--
    术语输入框（人工复核用）：绑定的是**自由文本**，一个字段可填「肝郁、脾虚」，
    提交时由 buildCorrected 按分隔符拆开 —— 所以这里不能用 el-select，
    否则会退化成「一个字段只能选一个词」。

    候选取数见 composables/useTermOptions.js（与质控规则配置共用）。
    与原版的差别：展开/聚焦会**预载一批候选**，点开不再是空的了
    （原先 el-autocomplete 只有输入才出候选，用户不知道这里能搜国标术语）。
  -->
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
  />
</template>

<script setup>
import { useTermOptions } from '@/composables/useTermOptions'

const props = defineProps({
  modelValue: { type: String, default: '' },
  type: { type: String, required: true },
  placeholder: { type: String, default: '输入术语，支持别名匹配' }
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
