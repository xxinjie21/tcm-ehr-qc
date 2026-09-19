<template>
  <div class="range-filter">
    <div class="rf-item">
      <label>科室</label>
      <el-input v-model="inner.department" placeholder="如：中医内科" clearable style="width: 130px" />
    </div>
    <div class="rf-item">
      <label>就诊时间</label>
      <el-date-picker
        v-model="inner.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始"
        end-placeholder="截止"
        style="width: 240px"
      />
    </div>
    <div class="rf-item">
      <label>证候</label>
      <TermInput v-model="inner.pattern" type="pattern" placeholder="如：肝肾亏虚" style="width: 150px" />
    </div>
    <div class="rf-item">
      <label>分级</label>
      <el-select v-model="inner.grade" placeholder="全部" clearable style="width: 110px">
        <el-option label="合格" value="合格" />
        <el-option label="待复核" value="待复核" />
        <el-option label="无效" value="无效" />
      </el-select>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import TermInput from '@/components/TermInput.vue'

const props = defineProps({
  modelValue: {
    type: Object,
    default: () => ({ department: '', dateRange: null, pattern: '', grade: '' })
  }
})
const emit = defineEmits(['update:modelValue'])

const inner = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})
</script>

<style scoped>
.range-filter { display: flex; gap: 16px; align-items: flex-end; flex-wrap: wrap; }
.rf-item label { display: block; font-size: 12px; color: var(--text-sub); margin-bottom: 3px; }
</style>
