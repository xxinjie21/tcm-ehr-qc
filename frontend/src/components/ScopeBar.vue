<template>
  <!-- 页面级范围查询条（P0-1）：全站唯一的筛选条壳子。
       位置约定 —— 每页内容区最顶部、全宽、紧贴第一个面板之前；
       内容约定 —— 里面只放**筛选/范围**控件（RangeFilter 与同轴次级筛选），
       业务动作（重算 / 清洗 / 导出 / 删除）不放进来，另见各页的 .page-actions。
       视觉与间距来自全局基类 .scope-bar（见 styles/theme.css），页面不再各写一份。 -->
  <section class="scope-bar">
    <slot />
  </section>
</template>

<script setup>
// 纯布局壳：不持有任何状态，控件与按钮都由使用方通过默认插槽传入。
// 之所以抽成组件而不是各页写 <section class="scope-bar">：位置与外形是跨页约束，
// 组件化后「六页同一形态」只由这一个文件决定，改一处即全站生效。
</script>
