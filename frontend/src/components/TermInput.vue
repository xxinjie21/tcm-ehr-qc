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

// 术语输入框：按类型（疾病 / 证候 / 中药等）从词典联想，选中后回填标准词
const props = defineProps({
  modelValue: { type: String, default: '' },
  type: { type: String, required: true },
  placeholder: { type: String, default: '输入术语，支持别名匹配' }
})

defineEmits(['update:modelValue'])

// 输入防抖间隔，避免逐字触发词典查询
const DEBOUNCE_MS = 200

let timer = null
// latest-wins：每次真正发起请求时取号，回来时号不是最新就丢弃，
// 避免慢的旧响应覆盖快的新结果
let seq = 0

// 联想查询：防抖后按类型拉词典候选，通过回调交给 el-autocomplete
const querySearch = (keyword, cb) => {
  // 1. 空关键字直接给空候选，不发请求
  if (keyword === null || keyword === undefined || keyword === '') {
    cb([])
    return
  }
  // 2. 防抖：连续输入只保留最后一次
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    timer = null
    const mine = ++seq
    try {
      // 3. 取候选；响应回来时若已不是最新一次输入则丢弃
      const res = await getTerms({ type: props.type, keyword })
      if (mine !== seq) return
      cb(res.data.terms || [])
    } catch {
      // 词典接口异常 → 降级为无候选，不打断用户继续输入
      if (mine !== seq) return
      cb([])
    }
  }, DEBOUNCE_MS)
}

// 组件卸载时清掉尚未触发的防抖定时器
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>
