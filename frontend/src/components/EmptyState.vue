<template>
  <!-- 失败态：区分「加载失败」与「确实没有数据」，并给出重试与上报两条出口-->
  <el-empty v-if="failed" description="数据加载失败" :image-size="80">
    <el-button size="small" @click="$emit('retry')">重 试</el-button>
    <el-button size="small" plain @click="reportHint">联系管理员</el-button>
  </el-empty>
  <!-- 成功但为空 -->
  <el-empty v-else-if="!loading" :description="text" :image-size="80" />
</template>

<script setup>
import { ElMessage } from 'element-plus'

/**
 * 数据区空态：统一「加载中 / 成功 / 失败 / 成功但为空」四态里的后三态。
 * 加载中由外层容器的 v-loading 表达，故此处不渲染内容。
 */
defineProps({
  failed: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  text: { type: String, default: '暂无数据' }
})

defineEmits(['retry'])

// 5xx 的响应体里带后端生成的追踪码，提示用户复制它上报，便于按日志定位
const reportHint = () => {
  ElMessage.info('请把提示中的「追踪码」（或浏览器控制台里的错误信息）转给系统管理员')
}
</script>
