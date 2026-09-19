<template>
  <section class="panel">
    <!-- 面板标题用 h2，与页面级 h1 构成标题层级（UX-17） -->
    <h2 class="panel-hd">
      <slot name="header">{{ title }}</slot>
    </h2>
    <div class="panel-bd">
      <slot />
    </div>
  </section>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' }
})
</script>

<style scoped>
.panel {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  margin-bottom: 14px;
  transition: box-shadow 0.15s ease, transform 0.15s ease;
}
.panel:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(47, 70, 57, 0.08);
}
.panel-hd {
  margin: 0;
  padding: 10px 16px;
  border-bottom: 1px solid #eee9dd;
  font-size: 14px;
  font-weight: bold;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 8px;
}
.panel-hd::before {
  content: '';
  width: 3px;
  height: 14px;
  background: var(--ink-mid);
}
.panel-bd {
  padding: 16px;
}
</style>
