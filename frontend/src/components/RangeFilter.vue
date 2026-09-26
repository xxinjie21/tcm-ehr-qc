<template>
  <!-- 病历检索筛选条：科室 / 就诊时间 / 证候 / 分级。
       与 StatsFilter 的分工：本组件用 v-model 双向绑定（受控 + 自更新），
       供病历数据、复核等列表页复用；StatsFilter 只读展示 + 向上抛动作事件。 -->
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
           label 只能关联其中一个控件（取起始那个），结束那个因此没有标签，
           Chrome 的 Issues 面板会报「No label associated with a form field」。
           aria-label 是 Picker 的显式 prop（ariaLabel）：范围分支把它透传给
           PickerRangeTrigger，而后者用 useAttrs() 把同一份 attrs 合并进「两个」input
           —— 所以这一行同时给起止两个框补上无障碍名称，且不会污染外层 div。 -->
      <label for="rf-date-start">就诊时间</label>
      <el-date-picker
        :id="['rf-date-start', 'rf-date-end']"
        aria-label="就诊时间"
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
// 筛选条（受控组件）：自身不持有筛选状态，通过 v-model 与父组件同步；
// 科室候选来自后端 DISTINCT，证候输入走词典联想（TermInput）。
import { computed, onMounted, ref } from 'vue'
import TermInput from '@/components/TermInput.vue'
import { getDepartments } from '@/api/stats'

const props = defineProps({
  // 筛选值对象，字段：department / dateRange / pattern / grade。
  // 默认值给出完整空结构，父组件少传字段时子控件也不会取到 undefined。
  modelValue: {
    type: Object,
    default: () => ({ department: '', dateRange: null, pattern: '', grade: '' })
  }
})
const emit = defineEmits(['update:modelValue'])

// v-model 桥接：读直接透传 props，写立刻 emit 回父组件
// （不在本地复制一份状态，避免出现双份真相）
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
    // 候选拉取失败 → 退化为空列表，不阻断用户手输证候 / 选分级
    departments.value = []
  }
})
</script>

<style scoped>
/* 与 StatsFilter 同构的单行卡片布局；两者共用 styles/theme.css 的视觉令牌 */
.range-filter { display: flex; gap: 16px; align-items: flex-end; flex-wrap: wrap; }
/* 标签压在各自控件上方（与 StatsFilter 一致） */
.rf-item label { display: block; font-size: 12px; color: var(--text-sub); margin-bottom: 3px; }
</style>
