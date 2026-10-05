<template>
  <el-card>
    <EmptyState :text="$route.meta.title + ' - 页面待开发'" />
  </el-card>
</template>

<script setup>
import EmptyState from '@/components/EmptyState.vue'
</script>
