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
import { getTerms } from '@/api/dictionary'

const props = defineProps({
  modelValue: { type: String, default: '' },
  type: { type: String, required: true },
  placeholder: { type: String, default: '输入术语，支持别名匹配' }
})

defineEmits(['update:modelValue'])

const querySearch = async (keyword, cb) => {
  if (!keyword) {
    cb([])
    return
  }
  try {
    const res = await getTerms({ type: props.type, keyword })
    cb(res.data.terms || [])
  } catch {
    cb([])
  }
}
</script>
