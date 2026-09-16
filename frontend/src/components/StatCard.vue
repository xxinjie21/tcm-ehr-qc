<template>
  <div class="stat" :class="tone">
    <div class="num">{{ display }}<small v-if="suffix">{{ suffix }}</small></div>
    <div class="lbl">
      <span class="ico" v-html="iconSvg" />
      {{ label }}
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], required: true },
  tone: { type: String, default: '' },
  suffix: { type: String, default: '' },
  icon: { type: String, default: '' }
})

// 细线单色图标（内联SVG，24 viewBox，stroke=currentColor）
const ICONS = {
  record: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 13h6"/><path d="M9 17h4"/>',
  rate: '<circle cx="12" cy="12" r="9"/><path d="m8.4 12.6 2.4 2.4 4.8-5.2"/>',
  pending: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5V12l3 2"/>',
  invalid: '<path d="M12 3.2 2.4 20.4h19.2z"/><path d="M12 9.5v4.2"/><path d="M12 16.8v.4"/>'
}

const iconSvg = computed(() => {
  const path = ICONS[props.icon]
  if (!path) return ''
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`
})

const display = computed(() =>
  typeof props.value === 'number' ? props.value.toLocaleString() : props.value
)
</script>

<style scoped>
.stat {
  flex: 1;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px 18px;
}
.stat .num {
  font-size: 24px;
  font-weight: bold;
  color: var(--ink);
  line-height: 1.2;
}
.stat .num small {
  font-size: 13px;
  font-weight: normal;
  color: var(--text-sub);
}
.stat .lbl {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
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
.stat.green .num {
  color: var(--ink-mid);
}
.stat.ochre .num {
  color: var(--ochre);
}
.stat.red .num {
  color: #a04335;
}
</style>
