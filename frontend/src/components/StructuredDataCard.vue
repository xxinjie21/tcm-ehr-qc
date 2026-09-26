<template>
  <div class="sd-card">
    <!-- 可追溯：这份结构化数据是依据哪一版术语词典产生的（抽取/归一落库时打点） -->
    <div v-if="dictVersion" class="sd-meta">
      依据词典版本 <b>{{ dictVersion }}</b>
      <span v-if="dictCapturedAt" class="sd-meta-t">· 采集于 {{ dictCapturedAt }}</span>
    </div>
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
                <!-- 描边标签＝归一「准不准」。命中层级写出来，避免用户只看到「精确」而不知道比的是什么；
                     途径（ES / 内存）已不再区分：ES 是唯一权威，没有内存兜底 -->
                <em v-if="sec.dict" class="tag lv" :class="lvClass(it)">{{ lvText(it) }}</em>
              </span>
            </el-tooltip>
          </div>
        </div>
      </div>
    </template>
    <!-- 空态必须分清两件事（「把没做说成没问题」同类问题已修 3 处，这是第 4 处）：
         ① 压根没抽过 —— structuredData 为 null / 空串 / 解析不出来，库里就是没有；
         ② 抽过了、只是没识别出要素 —— 后端 extractAndStore 只要 NLP 返回非 null 就写库，
            **哪怕 9 类全空**，所以「有对象但 9 类都空」明确代表抽取跑过了。
         原先两种都写「无标准化数据」，用户会以为「抽取没问题、只是没东西」，
         而实际可能是一次都没抽过 —— 两者的下一步动作完全不同。 -->
    <el-empty v-else :description="emptyTitle" :image-size="70">
      <div class="empty-hint">{{ emptyHint }}</div>
    </el-empty>
  </div>
</template>

<script setup>
// 结构化数据卡片：把一条病历抽取出的 9 类要素（疾病 / 症状 / 证候 / 方剂 / 中药 / 舌象 / 脉象 / 病因 / 治法）
// 按「有无独立词典」分组渲染，实体上标注来源（规则 / 模型）与归一命中等级。
// 设计取舍：空态必须区分「压根没抽过」与「抽过但没识别出要素」——两者的下一步动作不同。
import { computed } from 'vue'
import { ENTITY_SECTIONS, LEVEL_SHORT, LEVEL_FULL, LEVEL_UNMATCHED, entityName, pct } from '@/utils/structured'

const props = defineProps({
  data: { type: [String, Object], default: null }
})

const sections = ENTITY_SECTIONS

// 把 data 归一成对象：入参可能是已解析对象、JSON 字符串，也可能为 null / 空串 / 坏 JSON；
// 后三者一律返回 null，由 neverParsed 判定为「未抽取」
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

// 取某类要素的数组；无数据、键不存在或值不是数组都返回空数组，调用方无需二次判空
const list = (key) => {
  const d = parsed.value
  if (!d || !Array.isArray(d[key])) return []
  return d[key]
}

// 9 类要素是否至少有一类非空 —— 决定渲染实体列表还是空态
const hasAny = computed(() => sections.some((s) => list(s.key).length > 0))

/** 词典版本元信息（落库时打点，见 StructuredDataMeta）；旧数据没有则为空、不展示 */
const dictVersion = computed(() => parsed.value?._meta?.dictVersion || '')
/** 词典采集时间（与 dictVersion 同一处 _meta 打点）；旧数据没有则空串、不展示 */
const dictCapturedAt = computed(() => parsed.value?._meta?.dictCapturedAt || '')

/**
 * 空态的两种含义（见模板注释）。
 *
 * <p>{@code parsed === null} 表示压根没有数据：{@code data} 为 null/空串，或 JSON 解析失败。
 * 反之为「有数据但 9 类都空」，即抽取执行过、只是没识别出要素。</p>
 */
const neverParsed = computed(() => parsed.value === null)
/** 空态标题：按 neverParsed 区分「尚未抽取」与「已抽取但无要素」 */
const emptyTitle = computed(() => (neverParsed.value ? '尚未抽取标准化数据' : '已抽取，但没有识别出要素'))
/** 空态副文案：给出与标题对应的下一步动作（去执行抽取 / 无需处理） */
const emptyHint = computed(() => (neverParsed.value
  ? '这份病历还没有跑过结构化抽取。可在「结构化解析」页载入该病历后点「执行抽取」，结果会写入这里。'
  : '抽取已经执行过，只是这段原文里没有可归一的要素（疾病 / 症状 / 证候 / 方剂 / 中药等）。'))

/** 实体上的归一标签文案：命中方式；未命中说「未收录」 */
const lvText = (it) => (it.normLevel ? LEVEL_SHORT[it.normLevel] || it.normLevel : LEVEL_UNMATCHED)
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
 *
 * <p>行数刻意压到 4 行以内（原先是 8 行：归一 / 怎么比的 / 走哪条路 / 词典来源 / 国标代码 /
 * 原文片段 / 来源 / 置信度）。合并方式：「走哪条路」并进「归一」一行；「词典来源 + 国标代码」
 * 合成「依据」；「来源 + 置信度」合成一行；「原文片段」本来就已经写在标题的「原文 → 标准词」里，
 * 不再重复。三档分级与置信度的含义移到结果区图例统一说明，这里不逐条复述。</p>
 */
const tpRows = (sec, it) => {
  // 1. 取出原文与标准词，并初始化结果行
  const rows = []
  const raw = it.sourceText
  const name = entityName(sec, it)

  // 2. 按「无词典 / 命中 / 未命中」三档生成归一相关行
  if (!sec.dict) {
    rows.push({ k: '归一', v: '该字段没有独立词典，不做归一，保留原文' })
  } else if (it.normLevel) {
    rows.push({ k: '归一', v: LEVEL_FULL[it.normLevel] || it.normLevel })
    rows.push({ k: '怎么比的', v: levelDesc(it.normLevel, raw, name) })
    if (it.normSource) {
      rows.push({ k: '依据', v: it.normCode ? `${it.normSource}（${it.normCode}）` : it.normSource })
    }
  } else {
    rows.push({ k: '归一', v: '未命中词典，按原文返回' })
  }

  // 3. 追加来源行并返回（保证任何实体至少「归一 + 来源」两行）
  rows.push({ k: '来源', v: sourceLine(it) })
  return rows
}

/** 来源行：模型抽取带置信度；规则兜底是确定性匹配，没有置信度可言 */
const sourceLine = (it) => {
  if (it.source === 'rule') return '规则兜底（确定性匹配，没有置信度）'
  const c = pct(it.confidence)
  return c ? `模型抽取 · 置信 ${c}` : '模型抽取'
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
.sd-meta {
  font-size: 11.5px;
  color: var(--text-sub);
  margin-bottom: 10px;
  padding-bottom: 6px;
  border-bottom: 1px dashed var(--line);
}
.sd-meta b { color: var(--ink-mid); font-weight: normal; }
.sd-meta-t { margin-left: 4px; }
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
/* 空态副文案：标题只说「哪一种空」，下一步动作放这里 */
.empty-hint {
  max-width: 420px;
  margin: 2px auto 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-sub);
}
</style>

<!-- 非 scoped：el-tooltip 的内容被 teleport 到 body，scoped 选择器命中不到，必须用全局块 -->
<style>
.el-popper .tp { max-width: 340px; font-size: 12px; line-height: 1.7; }
.el-popper .tp-hd { font-weight: bold; margin-bottom: 4px; color: #2b2b2b; }
.el-popper .tp-row { display: flex; gap: 8px; }
.el-popper .tp-k { flex: 0 0 62px; color: #8a8578; }
.el-popper .tp-v { flex: 1 1 auto; color: #2b2b2b; }
</style>
