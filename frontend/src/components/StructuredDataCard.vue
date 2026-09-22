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
              <!-- 匹配度：原先只藏在 hover 的 title 里，用户不悬停就不知道这个词是精确命中
                   还是模糊猜的。这里直接标出来，用描边样式与「模型/规则」实心标签区分开：
                   实心标签说的是「这条怎么来的」，描边标签说的是「这个词准不准」。 -->
              <em v-if="it.normLevel" class="tag lv" :class="'lv' + it.normLevel">匹配 {{ LEVEL[it.normLevel] || it.normLevel }}</em>
              <!-- 置信度：加标签 + 统一成百分比两位。原先裸数字直出，同一屏会出现
                   0.772 / 0.9254 / 0.9996 三种不同位数，用户看不出这是什么数。 -->
              <span v-if="it.confidence != null" class="conf">置信 {{ pct(it.confidence) }}</span>
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
// 命中层级 → 中文。既用于要素上的「匹配 X」标签，也用于 hover 提示，
// 同一份文案两处引用，避免标签写「精确」而提示写「精确命中」这种不一致。
const LEVEL = { 1: '精确', 2: '包含', 3: '模糊' }
/**
 * 置信度显示：统一成百分比两位（0.9254 → 92.54%）。
 *
 * <p>后端 confidence 是 0~1 的原始小数且不保证位数（同一批数据里出现过 0.772 / 0.9254 / 0.9996），
 * 裸数字直出既没有标签说明含义、位数也不齐，看起来不像同一类值。这里统一成百分比两位。</p>
 *
 * <p>只有 {@code source=model}（模型抽取）的要素有这个值；{@code source=rule}（规则命中）
 * 的不下发 confidence，因此不显示 —— 属预期，不是缺数据。</p>
 */
const pct = (v) => {
  // 显式挡掉 null / undefined / 空串：Number(null) 和 Number('') 都是 0，
  // 不挡的话会渲染成「置信 0.00%」，比不显示更误导（模板虽有 v-if，但不该依赖调用方保证）
  if (v === null || v === undefined || v === '') return ''
  const n = Number(v)
  return Number.isFinite(n) ? `${(n * 100).toFixed(2)}%` : ''
}
const tip = (it) => {
  const parts = []
  if (it.sourceText && it.sourceText !== it.content) parts.push(`原文：${it.sourceText} → 标准词：${it.content}`)
  if (it.normLevel) parts.push(`命中层级：${LEVEL[it.normLevel] || it.normLevel}`)
  return parts.join('；')
}
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
/* 匹配度标签：描边不填充，和实心的「模型/规则」标签区分开；三级用深浅递进表达可靠度 */
.sd-item .tag.lv {
  background: transparent;
  border: 1px solid currentColor;
  padding: 0 3px;
}
.sd-item .tag.lv1 { color: var(--ink-mid); }
.sd-item .tag.lv2 { color: var(--ochre); }
.sd-item .tag.lv3 { color: var(--danger); }
.sd-item .conf { font-size: 10.5px; color: var(--text-sub); margin-left: 4px; }
</style>
