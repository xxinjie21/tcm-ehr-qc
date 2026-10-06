<template>
  <section class="panel">
    <!-- 面板标题用 h2，与页面级 h1 构成标题层级-->
    <h2 class="panel-hd">
      <slot name="header">{{ title }}</slot>
    </h2>
    <div class="panel-bd">
      <slot />
    </div>
  </section>
</template>

<script setup>
// 通用面板容器：统一标题样式与内边距；标题可用 header 插槽覆盖（如放操作按钮）
defineProps({
  title: { type: String, default: '' }
})
</script>

<style scoped>
.panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  margin-bottom: 14px;
  transition: box-shadow 0.15s ease;
}
/* hover 只给阴影，**不要** translateY：
 * transform 会让 .panel 成为 position:fixed 后代的包含块 —— 面板内的
 * el-dialog / el-tooltip / el-select 下拉（遮罩与弹层）会被限制在这个面板的
 * 方框内（表现为「被方框截断」），且鼠标在面板内移动时 hover 反复求值，
 * 包含块随之变化 → 弹层跳位 → 看起来就是闪屏。
 * box-shadow 不参与布局，也不会创建包含块，纯绘制，安全。 */
.panel:hover {
  box-shadow: 0 2px 8px rgba(47, 70, 57, 0.08);
}
.panel-hd {
  margin: 0;
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
/* 标题左侧的竖条装饰 */
.panel-hd::before {
  content: '';
  width: 3px;
  height: 14px;
  background: var(--ink-mid);
}
.panel-bd {
  padding: var(--sp-4);
}
</style>
