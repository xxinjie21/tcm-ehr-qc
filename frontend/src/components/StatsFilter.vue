<template>
  <section class="filter">
    <span class="cap">统计范围</span>
    <!-- 视觉标签必须带 for 才能关联到控件：只写 <label> 文本的话，
         Chrome 的 Issues 面板会对下面每个控件报「No label associated with a form field」。
         单个控件用 id + for（成组控件不能用 for，见 Governance 的单选按钮组） -->
    <div>
      <label for="sf-department">开单科室</label>
      <el-select id="sf-department" v-model="model.department" placeholder="全部科室" clearable style="width: 150px">
        <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
      </el-select>
    </div>
    <div>
      <label for="sf-start">就诊起始</label>
      <el-date-picker id="sf-start" v-model="model.start" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
    </div>
    <div>
      <label for="sf-end">就诊截止</label>
      <el-date-picker id="sf-end" v-model="model.end" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
    </div>
    <el-button type="primary" size="small" @click="$emit('search')">查 询</el-button>
    <el-button size="small" @click="$emit('reset')">重 置</el-button>
  </section>
</template>

<script setup>
defineProps({
  model: { type: Object, required: true },
  // 默认空数组：原来的 ['内科','外科','儿科','针灸科'] 在演示数据里根本不存在，
    // 一旦调用方忘了传就会显示假选项（Dashboard 目前恒传真实值，所以从未暴露）
    departments: { type: Array, default: () => [] }
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
