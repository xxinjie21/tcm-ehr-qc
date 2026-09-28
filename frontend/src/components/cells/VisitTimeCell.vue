<template>
  <!-- 接诊时间列：常态只到日，悬停给秒级原值。
       只到日是有意的 —— 演示数据的时间分量是脱敏噪声（57% 落在非门诊时段，
       会出现凌晨 2 点接诊），常态展示等于把噪声摆在列表上；hover 保留完整精度用于核对 -->
  <el-tooltip :content="full" placement="top">
    <span>{{ day }}</span>
  </el-tooltip>
</template>

<script setup>
import { computed } from 'vue'
import { fmtDateTime } from '@/utils/format'

const props = defineProps({
  visitTime: { type: [String, Object], default: null }
})

const day = computed(() => fmtDateTime(props.visitTime, 'date'))
const full = computed(() => fmtDateTime(props.visitTime, 'second'))
</script>
