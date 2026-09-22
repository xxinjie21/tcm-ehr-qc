<template>
  <div class="range-filter">
    <div class="rf-item">
      <label for="rf-department">科室</label>
      <el-select id="rf-department" v-model="inner.department" placeholder="全部科室" clearable style="width: 140px">
        <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
      </el-select>
    </div>
    <div class="rf-item">
      <!-- 范围选择器内部是「两个 input」，所以 id 必须是两个 id 的数组：
           传字符串会触发 element-plus 的 prop 类型告警（PickerRangeTrigger 的 id 只收 Array）。
           label 只能关联其中一个控件，取起始那个 -->
      <label for="rf-date-start">就诊时间</label>
      <el-date-picker
        :id="['rf-date-start', 'rf-date-end']"
        v-model="inner.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始"
        end-placeholder="截止"
        style="width: 240px"
      />
    </div>
    <div class="rf-item">
      <label for="rf-pattern">证候</label>
      <TermInput id="rf-pattern" v-model="inner.pattern" type="pattern" placeholder="如：肝肾亏虚" style="width: 150px" />
    </div>
    <div class="rf-item">
      <label for="rf-grade">分级</label>
      <el-select id="rf-grade" v-model="inner.grade" placeholder="全部" clearable style="width: 110px">
        <el-option label="合格" value="合格" />
        <el-option label="待复核" value="待复核" />
        <el-option label="无效" value="无效" />
      </el-select>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import TermInput from '@/components/TermInput.vue'
import { getDepartments } from '@/api/stats'

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

// U11：科室选项由后端动态获取（records.department DISTINCT）
const departments = ref([])
onMounted(async () => {
  try {
    const res = await getDepartments()
    departments.value = res.data || []
  } catch {
    departments.value = []
  }
})
</script>

<style scoped>
.range-filter { display: flex; gap: 16px; align-items: flex-end; flex-wrap: wrap; }
.rf-item label { display: block; font-size: 12px; color: var(--text-sub); margin-bottom: 3px; }
</style>
