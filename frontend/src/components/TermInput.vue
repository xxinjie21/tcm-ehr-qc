<template>
  <el-autocomplete
    :model-value="modelValue"
    :fetch-suggestions="querySearch"
    :placeholder="placeholder"
    value-key="standardTerm"
    clearable
    style="width: 100%"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>

<script setup>
import { onBeforeUnmount } from 'vue'
import { getTerms } from '@/api/dictionary'

const props = defineProps({
  modelValue: { type: String, default: '' },
  type: { type: String, required: true },
  placeholder: { type: String, default: '输入术语，支持别名匹配' }
})

defineEmits(['update:modelValue'])

const DEBOUNCE_MS = 200

let timer = null
// latest-wins：每次真正发起请求时取号，回来时号不是最新就丢弃，
// 避免慢的旧响应覆盖快的新结果
let seq = 0

const querySearch = (keyword, cb) => {
  if (keyword === null || keyword === undefined || keyword === '') {
    cb([])
    return
  }
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    timer = null
    const mine = ++seq
    try {
      const res = await getTerms({ type: props.type, keyword })
      if (mine !== seq) return
      cb(res.data.terms || [])
    } catch {
      if (mine !== seq) return
      cb([])
    }
  }, DEBOUNCE_MS)
}

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>
