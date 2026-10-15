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

// age 有两种形态：原始病历里可能已经是「45岁2月」这种**带单位**的原值，也可能是「45」这种纯数字。
// 无条件拼「岁」会把前者显示成「45岁2月岁」—— 既是一处错误的展示，也把单元格文本撑长到
// 110px 的列宽放不下：内容换行后整行从 37px 涨到 55px，每页 10 行就装不进 max-height=420，
// 表格因此冒出内部滚动条（病历数据页同因）。只在缺「岁」时补。
const text = computed(() => {
  const raw = props.age === null || props.age === undefined ? '' : String(props.age).trim()
  const ageText = raw && !raw.includes('岁') ? raw + '岁' : raw
  const parts = [ageText, props.gender].filter(Boolean)
  return parts.length ? parts.join(' / ') : '—'
})
</script>
