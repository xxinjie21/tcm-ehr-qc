<template>
  <!-- 年龄 / 性别合成一列；两者都空时给「—」 -->
  <span>{{ text }}</span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  age: { type: [String, Number], default: '' },
  gender: { type: String, default: '' }
})

const text = computed(() => {
  const parts = [props.age ? props.age + '岁' : '', props.gender].filter(Boolean)
  return parts.length ? parts.join(' / ') : '—'
})
</script>
