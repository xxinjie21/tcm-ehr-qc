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
    <!-- V1：内部改横向布局 —— 数字贴左、标签贴右（flex justify-between + align-end）。
         宽屏栅格会把卡片拉到 500px+，原竖排时标签右侧 ~80% 全空；
         两端锚定后空白落在数字与标签之间的「呼吸位」，右缘由标签收口。 -->
    <div class="row">
      <div class="num">{{ display }}<small v-if="suffix">{{ suffix }}</small></div>
      <div class="lbl">
        <!-- 未传 icon 时不渲染空占位（DictionaryImport 等调用方不传 icon，避免多出 14px 空隙） -->
        <span v-if="iconSvg" class="ico" v-html="iconSvg" />
        {{ label }}
        <!-- 可下钻时给一个极轻的提示：让人知道"这个数字能点" -->
        <span v-if="clickable" class="drill" aria-hidden="true">›</span>
      </div>
    </div>
    <!-- 可选口径说明：有值才渲染；默认空串不占位，未传 note 的调用方观感不变 -->
    <p v-if="note" class="note">{{ note }}</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 指标卡片：大数字贴左 + 「图标+标签」贴右 + 可选口径说明；tone 决定数字配色（green / ochre / red）
const props = defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], required: true },
  tone: { type: String, default: '' },
  suffix: { type: String, default: '' },
  icon: { type: String, default: '' },
  /** 是否可点击下钻（对标 E3）。true 时整卡可点、可 Tab 聚焦、支持 Enter/Space */
  clickable: { type: Boolean, default: false },
  /** V1：口径说明（--fs-xs 一行）。只允许传页面已有数据/文案，不许编造业务数字 */
  note: { type: String, default: '' }
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
  padding: var(--sp-4) var(--sp-4);
  /* V1：纵向排「数字行 + 口径说明行」；数字行内部再横向两端对齐 */
  display: flex;
  flex-direction: column;
  justify-content: center;
}
/* V1：数字贴左、标签贴右。align-items:flex-end 让 13px 标签与 30px 数字的底缘对齐，
   视觉上是「数字托着标签」，而不是两行互不相干的文本。 */
.stat .row {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: var(--sp-2);
}
.stat .num {
  font-size: var(--fs-xl); /* F5：大号指标走字号令牌（--fs-xl=30），不写字面量 */
  font-weight: bold;
  color: var(--ink);
  line-height: 1.2;
  white-space: nowrap; /* 数字与后缀不换行 */
}
.stat .num small {
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
  margin-left: 1px;
}
.stat .lbl {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  text-align: right;
  /* 常规宽度下完整显示并贴右缘；极窄卡片（auto-fit 收到 200px 档）时
     允许收缩 + 省略号，而不是换行或溢出边框 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
/* V1：口径说明 —— --fs-xs 一行，贴在数字行下方；有 note 才渲染，空串不占位 */
.stat .note {
  margin: 6px 0 0;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  line-height: 1.4;
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
  color: var(--ochre-text);
}
.stat.red .num {
  color: var(--danger);
}
/* 对标 E3：可下钻的卡片给出可点的视觉与键盘焦点。
   hover 只加边框与轻微阴影，不做位移 —— 指标卡并排，位移会让整行抖一下。 */
.stat.clickable {
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease-out),
    box-shadow var(--dur-fast) var(--ease-out);
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
  color: var(--text-sub-strong);
  opacity: 0.7;
}
</style>
