<template>
  <!--
    数据区骨架占位（共享）。

    为什么有它：数据区此前的加载态是 `v-loading` 整块遮罩，遮罩会盖住**已经渲染出来的
    文字**（统计数字、表头、上一批结果）—— 那正是「加载指示与内容重合」这一条要消除的。
    骨架屏只在「还没有内容可看」时出现，把将来的形状先占出来；已经有内容时不动它，
    刷新用 .spin-inline 在角落转圈（内容始终可见，见 theme.css「共享骨架屏」段）。

    用法：只在**没有内容**的分支上挂它，不要包住已有内容：
      <LoadingSkeleton v-if="loading && !rows.length" variant="table" :rows="5" />
      <el-table v-else ... />
    颜色/动画/变体全部来自 theme.css 的 .load-skel，组件本身只管形状与行数。
  -->
  <div
    class="load-skel"
    :class="`load-skel--${variant}`"
    role="status"
    :aria-label="label || '加载中'"
  >
    <span v-if="label" class="load-skel__label">{{ label }}</span>
    <span
      v-for="n in rowCount"
      :key="n"
      class="load-skel__row"
      :style="{ width: widthOf(n) }"
      aria-hidden="true"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 骨架占位。
 *
 * <p>props 只留三个：行数、形状变体、可选的一行说明文字。宽度按行号轻微错落
 * （末行短一截），读起来像「内容正在一条条来」，而不是一条静止的色带。</p>
 */
const props = defineProps({
  /** 骨架条行数（1~20，超出按 20 收敛，避免误传把页面撑长） */
  rows: { type: Number, default: 4 },
  /** 形状变体：line 文本行 / table 表格行 / chart 图表块 / form 表单行 */
  variant: { type: String, default: 'line' },
  /** 可选说明文字（如「正在统计扣分分布…」）；不传则只给屏幕阅读器一个「加载中」 */
  label: { type: String, default: '' }
})

const rowCount = computed(() => Math.max(1, Math.min(20, props.rows)))

/** 行宽错落：末行 62%，每第 3 行 88%，其余 100% */
const widthOf = (n) => {
  if (n === rowCount.value) return '62%'
  return n % 3 === 0 ? '88%' : '100%'
}
</script>
