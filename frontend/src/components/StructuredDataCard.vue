<template>
  <div class="sd-card">
    <template v-if="hasAny">
      <div v-for="sec in sections" :key="sec.key" class="sd-section">
        <div v-if="list(sec.key).length" class="sd-sec">
          <!-- 分区标题带上「这个字段有没有词典」：舌象/脉象/病因/治法四类本来就没有独立词典，
               与其在每条实体上重复标「无词典」，不如在这里说一次，实体上只留真正有信息量的标签 -->
          <div class="sd-sec-title">
            {{ sec.label }}
            <span class="sd-sec-hint">{{ sec.dict ? '走词典归一' : '无独立词典，保留原文' }}</span>
          </div>
          <div class="sd-items">
            <el-tooltip
              v-for="(it, i) in list(sec.key)"
              :key="i"
              placement="top"
              effect="light"
              :show-after="100"
              :hide-after="0"
            >
              <template #content>
                <div class="tp">
                  <div class="tp-hd">{{ tpTitle(sec, it) }}</div>
                  <div v-for="r in tpRows(sec, it)" :key="r.k" class="tp-row">
                    <span class="tp-k">{{ r.k }}</span>
                    <span class="tp-v">{{ r.v }}</span>
                  </div>
                </div>
              </template>
              <span class="sd-item">
                <template v-if="sec.key === 'herbs'">
                  <b>{{ it.name }}</b><span v-if="it.dosage" class="dosage">{{ it.dosage }}</span>
                </template>
                <template v-else>
                  <b>{{ it.content }}</b>
                  <span v-if="it.sourceText && it.sourceText !== it.content" class="src">原文：{{ it.sourceText }}</span>
                </template>
                <!-- 实心标签＝这条实体「怎么来的」 -->
                <em v-if="it.source" class="tag" :class="it.source">{{ it.source === 'rule' ? '规则' : '模型' }}</em>
                <!-- 置信度只对模型实体有意义：规则兜底是确定性匹配，没有概率可言 -->
                <span v-if="it.confidence != null" class="conf">置信 {{ pct(it.confidence) }}</span>
                <!-- 描边标签＝归一「准不准、走哪条路」。命中方式与途径都写出来，
                     避免用户只看到「精确」而不知道是跟谁比、也不知道这次是 ES 索引给的还是内存兜的 -->
                <em v-if="sec.dict" class="tag lv" :class="lvClass(it)">{{ lvText(it) }}</em>
              </span>
            </el-tooltip>
          </div>
        </div>
      </div>
    </template>
    <el-empty v-else description="无标准化数据" :image-size="70" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ENTITY_SECTIONS, LEVEL_SHORT, LEVEL_FULL, VIA_TEXT, entityName, pct } from '@/utils/structured'

const props = defineProps({
  data: { type: [String, Object], default: null }
})

const sections = ENTITY_SECTIONS

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

const hasAny = computed(() => sections.some((s) => list(s.key).length > 0))

/** 实体上的归一标签文案：命中方式 + 走哪条路；未命中说「未收录」 */
const lvText = (it) => {
  if (!it.normLevel) return '未收录'
  const via = it.normVia ? `·${VIA_TEXT[it.normVia] || it.normVia}` : ''
  return `${LEVEL_SHORT[it.normLevel] || it.normLevel}${via}`
}
/** 描边颜色：命中按精确度分三级，未收录走中性灰 —— 灰的是「没查到」，
    红黄是「查到了但可能不准」，两者不该同色 */
const lvClass = (it) => (it.normLevel ? `lv${it.normLevel}` : 'lv0')

/** 悬停标题：「原文 → 标准词」，未命中/无词典时只说实体本身 */
const tpTitle = (sec, it) => {
  const name = entityName(sec, it)
  const raw = it.sourceText
  if (sec.dict && it.normLevel && raw && raw !== name) return `「${raw}」 → 「${name}」`
  return `「${name}」`
}

/**
 * 悬停明细。**保证任何实体都至少有两行**——之前这里在「原文与标准词相同且未命中」时
 * 直接返回空串，导致舌象 / 脉象 / 病因 / 治法 四类实体完全没有悬停信息。
 */
const tpRows = (sec, it) => {
  const rows = []
  const raw = it.sourceText
  const name = entityName(sec, it)

  if (!sec.dict) {
    rows.push({ k: '归一', v: '该字段没有独立词典，不做归一，保留原文' })
  } else if (it.normLevel) {
    rows.push({ k: '归一', v: `已命中词典，${LEVEL_FULL[it.normLevel] || it.normLevel}` })
    rows.push({ k: '怎么比的', v: levelDesc(it.normLevel, raw, name) })
    rows.push({ k: '走哪条路', v: it.normVia ? VIA_TEXT[it.normVia] || it.normVia : '—' })
    if (it.normSource) rows.push({ k: '词典来源', v: it.normSource })
    if (it.normCode) rows.push({ k: '国标代码', v: it.normCode })
  } else {
    rows.push({ k: '归一', v: '未命中词典，按原文返回' })
  }

  if (raw && raw !== name) rows.push({ k: '原文片段', v: raw })

  rows.push({
    k: '来源',
    v: it.source === 'rule' ? '规则兜底（确定性匹配，无置信度）' : '模型抽取'
  })
  if (it.confidence != null) {
    rows.push({ k: '置信度', v: `${pct(it.confidence)}（模型对这个片段的识别把握，与归一无关）` })
  }
  return rows
}

/** 把「精确/包含/模糊」翻成「跟谁比、怎么比上的」 */
const levelDesc = (level, raw, name) => {
  const src = raw || name
  if (level === 1) return `原文「${src}」与词典里的标准词或别名完全一致`
  if (level === 2) return `原文「${src}」与词典标准词「${name}」互相包含（一方含另一方）`
  if (level === 3) return `原文「${src}」与词典标准词「${name}」字面相似（字符重合度 ≥ 80%），属推测命中`
  return '—'
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
.sd-sec-hint { font-size: 11px; font-weight: normal; color: var(--text-sub); margin-left: 6px; }
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
.sd-item .tag.lv {
  background: transparent;
  border: 1px solid currentColor;
  padding: 0 3px;
}
.sd-item .tag.lv0 { color: var(--text-sub); }
.sd-item .tag.lv1 { color: var(--ink-mid); }
.sd-item .tag.lv2 { color: var(--ochre); }
.sd-item .tag.lv3 { color: var(--danger); }
.sd-item .conf { font-size: 10.5px; color: var(--text-sub); margin-left: 4px; }
</style>

<!-- 非 scoped：el-tooltip 的内容被 teleport 到 body，scoped 选择器命中不到，必须用全局块 -->
<style>
.el-popper .tp { max-width: 340px; font-size: 12px; line-height: 1.7; }
.el-popper .tp-hd { font-weight: bold; margin-bottom: 4px; color: #2b2b2b; }
.el-popper .tp-row { display: flex; gap: 8px; }
.el-popper .tp-k { flex: 0 0 62px; color: #8a8578; }
.el-popper .tp-v { flex: 1 1 auto; color: #2b2b2b; }
</style>
