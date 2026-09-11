<template>
  <div class="stat" :class="tone">
    <div class="num">{{ display }}<small v-if="suffix">{{ suffix }}</small></div>
    <div class="lbl">{{ label }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], required: true },
  tone: { type: String, default: '' },
  suffix: { type: String, default: '' }
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
}
.stat .num small {
  font-size: 13px;
  font-weight: normal;
  color: var(--text-sub);
}
.stat .lbl {
  font-size: 12.5px;
  color: var(--text-sub);
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
