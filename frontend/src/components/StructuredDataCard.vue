<template>
  <div class="sd-card">
    <template v-if="hasAny">
      <div v-for="sec in sections" :key="sec.key" class="sd-section">
        <div v-if="list(sec.key).length" class="sd-sec">
          <div class="sd-sec-title">{{ sec.label }}</div>
          <div class="sd-items">
            <span v-for="(it, i) in list(sec.key)" :key="i" class="sd-item" :title="tip(it)">
              <template v-if="sec.key === 'herbs'">
                <b>{{ it.name }}</b><span v-if="it.dosage" class="dosage">{{ it.dosage }}</span>
              </template>
              <template v-else>
                <b>{{ it.content }}</b>
                <span v-if="it.sourceText && it.sourceText !== it.content" class="src">原文：{{ it.sourceText }}</span>
              </template>
              <em v-if="it.source" class="tag" :class="it.source">{{ it.source === 'rule' ? '规则' : '模型' }}</em>
              <span v-if="it.confidence != null" class="conf">{{ it.confidence }}</span>
            </span>
          </div>
        </div>
      </div>
    </template>
    <el-empty v-else description="无标准化数据" :image-size="70" />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  data: { type: [String, Object], default: null }
})

const SECTIONS = [
  { key: 'diseases', label: '疾病' },
  { key: 'symptoms', label: '症状' },
  { key: 'tongueList', label: '舌象' },
  { key: 'pulseList', label: '脉象' },
  { key: 'patternList', label: '证候' },
  { key: 'causeList', label: '病因' },
  { key: 'treatmentList', label: '治法' },
  { key: 'formulaList', label: '方剂' },
  { key: 'herbs', label: '中药' }
]
const sections = SECTIONS

const parsed = computed(() => {
  const d = props.data
  if (!d) return null
  if (typeof d === 'object') return d
  try {
    return JSON.parse(d)
  } catch {
    return null
  }
})

const list = (key) => {
  const d = parsed.value
  if (!d || !Array.isArray(d[key])) return []
  return d[key]
}

const hasAny = computed(() => SECTIONS.some((s) => list(s.key).length > 0))
const tip = (it) => (it.sourceText && it.sourceText !== it.content ? `原文：${it.sourceText}` : '')
</script>

<style scoped>
.sd-card { font-size: 13px; }
.sd-sec { margin-bottom: 12px; }
.sd-sec-title {
  font-size: 13px;
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: 8px;
  margin-bottom: 8px;
}
.sd-items { display: flex; flex-wrap: wrap; gap: 8px; }
.sd-item {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 4px 10px;
  color: var(--ink);
}
.sd-item .src { font-size: 11.5px; color: var(--text-sub); margin-left: 6px; }
.sd-item .dosage { color: var(--ochre); margin-left: 4px; }
.sd-item .tag {
  font-style: normal;
  font-size: 10.5px;
  margin-left: 6px;
  padding: 0 4px;
  border-radius: 3px;
  color: #fff;
  background: var(--ink-mid);
}
.sd-item .tag.rule { background: var(--ochre); }
.sd-item .conf { font-size: 10.5px; color: var(--text-sub); margin-left: 4px; }
</style>
