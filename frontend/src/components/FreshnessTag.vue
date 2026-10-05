<script setup>
/**
 * 数据新鲜度徽标（P2-9）。
 *
 * 为什么要有它：展示「解析 / 质控派生数据」的页面原先各写各的，有的干脆不写 ——
 * 用户看不出这个数字是**什么时候算的**、有没有**过期**。统一成一个组件后，
 * 观感与语义一致，将来加页面也不必再各写一套。
 *
 * props:
 *  - time   数据时间（后端下发，如 generatedAt / createTime）
 *  - stale  是否已过期（true 时高亮并给出原因）
 *  - reason 过期原因（如「解析早于词表，需重跑」）
 */
defineProps({
  time: { type: String, default: '' },
  stale: { type: Boolean, default: false },
  reason: { type: String, default: '' }
})
</script>

<template>
  <span class="fresh-tag" :class="{ stale }">
    <span class="k">数据时间</span>
    <b>{{ time || '—' }}</b>
    <template v-if="stale">
      <span class="why">（{{ reason || '已过期，建议重跑' }}）</span>
    </template>
  </span>
</template>

<style scoped>
.fresh-tag {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--ink-mid);
}
.fresh-tag .k { color: var(--text-sub); }
.fresh-tag.stale {
  color: var(--ochre);
  background: var(--ochre-surface);
  border: 1px solid var(--ochre-light);
  border-radius: 4px;
  padding: 1px 6px;
}
.fresh-tag .why { color: var(--ochre); }
</style>
