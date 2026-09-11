<template>
  <div class="bar-list">
    <div v-for="item in normalized" :key="item.name" class="bar-item">
      <span class="name" :title="item.name">{{ item.name }}</span>
      <div class="bar-track">
        <div class="bar-fill" :style="{ width: item.pct + '%', background: item.color }" />
      </div>
      <span class="val">{{ item.value }}</span>
    </div>
    <el-empty v-if="!items.length" description="暂无数据" :image-size="60" />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  items: { type: Array, default: () => [] },
  color: { type: String, default: '' }
})

const normalized = computed(() => {
  if (!props.items.length) return []
  const max = Math.max(...props.items.map((i) => i.value), 1)
  return props.items.map((i, idx) => ({
    ...i,
    pct: Math.round((i.value / max) * 1000) / 10,
    color: i.color || props.color || (idx % 2 === 0 ? 'var(--ink-mid)' : '#6b8a79')
  }))
})
</script>

<style scoped>
.bar-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.bar-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.bar-item .name {
  width: 64px;
  color: var(--text);
  text-align: right;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar-track {
  flex: 1;
  height: 13px;
  background: #f0ede4;
  border-radius: 2px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  background: var(--ink-mid);
}
.bar-item .val {
  width: 40px;
  text-align: right;
  color: var(--text-sub);
}
</style>
