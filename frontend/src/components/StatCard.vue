<template>
  <!-- 对标 E3：指标即入口。clickable 时整卡可点（并支持键盘 Enter/Space），
       否则保持纯展示 —— 不是所有用到 StatCard 的地方都有下钻目标。 -->
  <div
    class="stat"
    :class="[tone, { clickable }]"
    :role="clickable ? 'button' : null"
    :tabindex="clickable ? 0 : null"
    @keydown.enter="clickable && $emit('click', $event)"
    @keydown.space.prevent="clickable && $emit('click', $event)"
  >
    <div class="num">{{ display }}<small v-if="suffix">{{ suffix }}</small></div>
    <div class="lbl">
      <span class="ico" v-html="iconSvg" />
      {{ label }}
      <!-- 可下钻时给一个极轻的提示：让人知道"这个数字能点" -->
      <span v-if="clickable" class="drill" aria-hidden="true">›</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 指标卡片：一个大数字 + 标签 + 细线图标；tone 决定数字配色（green / ochre / red）
const props = defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], required: true },
  tone: { type: String, default: '' },
  suffix: { type: String, default: '' },
  icon: { type: String, default: '' },
  /** 是否可点击下钻（对标 E3）。true 时整卡可点、可 Tab 聚焦、支持 Enter/Space */
  clickable: { type: Boolean, default: false }
})

// 细线单色图标（内联SVG，24 viewBox，stroke=currentColor）
const ICONS = {
  record: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 13h6"/><path d="M9 17h4"/>',
  rate: '<circle cx="12" cy="12" r="9"/><path d="m8.4 12.6 2.4 2.4 4.8-5.2"/>',
  pending: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5V12l3 2"/>',
  invalid: '<path d="M12 3.2 2.4 20.4h19.2z"/><path d="M12 9.5v4.2"/><path d="M12 16.8v.4"/>'
}

// 按 icon 名拼出完整 SVG；未登记的图标名返回空串，模板不渲染
const iconSvg = computed(() => {
  const path = ICONS[props.icon]
  if (!path) return ''
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`
})

// 数字加千分位便于阅读；字符串值（如已格式化过的比例）原样展示
const display = computed(() =>
  typeof props.value === 'number' ? props.value.toLocaleString() : props.value
)
</script>

<style scoped>
.stat {
  flex: 1;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px var(--sp-4);
}
.stat .num {
  font-size: 24px;
  font-weight: bold;
  color: var(--ink);
  line-height: 1.2;
}
.stat .num small {
  font-size: var(--fs-base);
  color: var(--text-sub);
}
.stat .lbl {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-md);
  color: var(--text-sub);
  margin-top: 2px;
}
.stat .ico {
  display: inline-flex;
  width: 14px;
  height: 14px;
  opacity: 0.75;
}
.stat .ico :deep(svg) {
  width: 14px;
  height: 14px;
}
/* tone 对应的数字配色：green=达标、ochre=待办、red=异常 */
.stat.green .num {
  color: var(--ink-mid);
}
.stat.ochre .num {
  color: var(--ochre);
}
.stat.red .num {
  color: var(--danger);
}
/* 对标 E3：可下钻的卡片给出可点的视觉与键盘焦点。
   hover 只加边框与轻微阴影，不做位移 —— 指标卡并排，位移会让整行抖一下。 */
.stat.clickable {
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.stat.clickable:hover {
  border-color: var(--line-soft);
  box-shadow: 0 2px 8px rgb(0 0 0 / 6%);
}
.stat.clickable:focus-visible {
  outline: 2px solid var(--ochre);
  outline-offset: 1px;
}
.stat .drill {
  margin-left: 2px;
  color: var(--text-sub);
  opacity: 0.7;
}
</style>
