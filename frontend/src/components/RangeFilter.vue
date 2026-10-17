<template>
  <!-- 病历检索筛选条：科室 / 就诊时间 / 导入时间 / 证候 / 分级。
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
           PickerRangeTrigger，而后者用 useAttrs 把同一份 attrs 合并进「两个」input
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
    <div v-if="showImport" class="rf-item">
      <!-- 导入时间：与「就诊时间」是两个独立维度（后者筛 visit_time，本项筛 create_time），
           可同时设置、取交集。典型用法是刚导入一批数据后，点「今天」只看这批新数据。
           id 同样必须是数组（范围选择器内部是两个 input），aria-label 由 Picker 透传到两个框。 -->
      <label for="rf-import-start">导入时间</label>
      <div class="rf-import">
        <el-date-picker
          :id="['rf-import-start', 'rf-import-end']"
          aria-label="导入时间"
          v-model="inner.importRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始"
          end-placeholder="截止"
          style="width: 240px"
        />
        <el-button size="small" @click="todayImport">今天</el-button>
      </div>
    </div>
    <div v-if="showPattern" class="rf-item">
      <label for="rf-pattern">证候</label>
      <TermInput id="rf-pattern" v-model="inner.pattern" type="pattern" placeholder="如：肝肾亏虚" style="width: 150px" />
    </div>
    <div v-if="showGrade" class="rf-item">
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
import { useDepartments } from '@/composables/useDepartments'
import { computed, onMounted, ref } from 'vue'
import TermInput from '@/components/TermInput.vue'

const props = defineProps({
  // 筛选值对象，字段：department / dateRange / pattern / grade / importRange。
  // 默认值给出完整空结构，父组件少传字段时子控件也不会取到 undefined。
  // importRange 筛的是「导入时间」（后端 records.create_time），dateRange 筛的是「就诊时间」。
  modelValue: {
    type: Object,
    default: () => ({ department: '', dateRange: null, pattern: '', grade: '', importRange: null })
  },
  // 是否显示「证候」输入。默认 true（病历数据 / 复核等列表页都用）。
  // 标准化质量报告页传 false —— 该页是组织级口径、无证候维度展示，
  // 且证候是 LIKE 子串模糊匹配，子集统计的参考性弱（2026-10-17 定案删除）。
  showPattern: {
    type: Boolean,
    default: true
  },
  // 是否显示「分级」选择器。清洗页传 false —— 清洗只作用于「质控合格」，
  // 分级对清洗没有意义，留着只会让用户以为能把整库都洗一遍。
  showGrade: {
    type: Boolean,
    default: true
  },
  // 是否显示「导入时间」。默认 true（病历数据 / Qc / 结构化解析 / 标准化质量报告等页都用）。
  // 标准化质量报告页同样传 true —— 它的默认口径就是「今天导入」：importRange 下推
  // records.create_time，与 start/end（接诊时间）是两个独立维度、可同时生效。
  showImport: {
    type: Boolean,
    default: true
  }
})
const emit = defineEmits(['update:modelValue'])

// v-model 桥接：读直接透传 props，写立刻 emit 回父组件
// （不在本地复制一份状态，避免出现双份真相）
const inner = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

/** 「今天」快捷：把导入时间设成今天一整天，便于只看刚导进来的那批病历 */
const todayImport = () => {
  const d = new Date()
  const s = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  // 就地改 props.modelValue（与模板里 inner.xxx = 的写法同一路径）：
  // 不能走 inner.value = {...} —— 父组件用 const filters = reactive({...}) 接 v-model，
  // 整体赋值会编译成对 const 的赋值而抛错。父对象是 reactive，新键同样是响应式的。
  inner.value.importRange = [s, s]
}

// U11：科室选项由后端动态获取（records.department DISTINCT），缓存收敛到 useDepartments
const { departments, reload } = useDepartments()
onMounted(reload)
</script>

<style scoped>
/* 与 StatsFilter 同构的单行卡片布局；两者共用 styles/theme.css 的视觉令牌 */
.range-filter { display: flex; gap: var(--sp-4); align-items: flex-end; flex-wrap: wrap; }
/* 标签压在各自控件上方（与 StatsFilter 一致） */
.rf-item label { display: block; font-size: var(--fs-xs); color: var(--text-sub-strong); margin-bottom: 3px; }
/* 导入时间：日期区间与「今天」按钮同一行，按钮与 32px 输入框底对齐 */
.rf-import { display: flex; align-items: center; gap: var(--sp-2); }
</style>
