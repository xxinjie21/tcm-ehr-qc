<template>
  <section class="filter">
    <span class="cap">统计范围</span>
    <div>
      <label>开单科室</label>
      <el-select v-model="model.department" placeholder="全部科室" clearable style="width: 150px">
        <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
      </el-select>
    </div>
    <div>
      <label>就诊起始</label>
      <el-date-picker v-model="model.start" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
    </div>
    <div>
      <label>就诊截止</label>
      <el-date-picker v-model="model.end" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
    </div>
    <el-button type="primary" size="small" @click="$emit('search')">查 询</el-button>
    <el-button size="small" @click="$emit('reset')">重置为全部</el-button>
  </section>
</template>

<script setup>
defineProps({
  model: { type: Object, required: true },
  departments: { type: Array, default: () => ['内科', '外科', '儿科', '针灸科'] }
})

defineEmits(['search', 'reset'])
</script>

<style scoped>
.filter {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 16px;
  margin-bottom: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  align-items: flex-end;
}
.filter .cap {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  margin-right: 6px;
  align-self: center;
}
.filter label {
  display: block;
  font-size: 12px;
  color: var(--text-sub);
  margin-bottom: 3px;
}
</style>
