<template>
  <div class="sd-card">
    <!-- 可追溯：这份结构化数据是依据哪一版术语词典产生的（抽取/归一落库时打点）。
         主行只给人能判断的信息（依据多少条词条 + 什么时候采集），
         哈希串是技术标识，放 tooltip 里；直接把 81 字符的
         "disease:c2dd2a1e;pattern:…;" 甩在页面上没有任何解读价值。 -->
    <div v-if="dictVersion || dictCapturedAt" class="sd-meta">
      依据词典<template v-if="dictTermCount"> <b>{{ dictTermCount }} 条词条</b></template>
      <span v-if="dictCapturedAt" class="sd-meta-t">· 采集于 {{ dictCapturedAt }}</span>
      <el-tooltip v-if="dictVersion" placement="top">
        <template #content>
          词典内容指纹：{{ dictVersion }}<br>
          同一份词典内容才会得到同一个指纹；词典改动后指纹随之变化，
          据此可判断某份结构化数据是由「哪一版词库」归一出来的。
        </template>
        <span class="sd-meta-fp">查看版本指纹</span>
      </el-tooltip>
    </div>

    <!-- 人工修改提示：这块数据被人工改过，清洗归一会跳过，
         且不应计入「模型抽取准确率」。所以这里明确告诉用户，别把它当模型输出看。 -->
    <div v-if="manuallyEdited" class="sd-manual">
      <strong>人工修改数据</strong>
      <span v-if="editedBy">由 {{ editedBy }}</span>
      <span v-if="editedAt">· {{ editedAt }}</span>
      <span class="sd-manual-t">该条结构化数据含人工修改，非模型原样抽取；清洗归一会跳过本条。</span>
    </div>

    <!-- 降级提示（25.7）：这份数据是「未开启 / 服务连不上 / 模型未加载」时抽的，
         结果少或为空并非原文没写。单条解析写回时带 unavailableReason；
         批量解析不写原因，只能退回 modelAvailable=false（旧数据同理）。 -->
    <div v-if="degradeText" class="sd-degrade">
      <strong>抽取不完整</strong>
      <span>{{ degradeText }}</span>
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
              <!-- 28.17：locatable 时整条实体可点，向上抛 locate，由页面在原文区定位/高亮 -->
              <span
                class="sd-item"
                :class="{ clickable: locatable }"
                @click="locatable && $emit('locate', locateText(sec, it))"
              >
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
    <EmptyState v-else :text="emptyTitle" :image-size="70">
      <div class="empty-hint">{{ emptyHint }}</div>
      <!-- 空结果的原因补白：历史数据没写降级原因时，用实时探测把「也可能是一次降级抽取」说出来 -->
      <div v-if="liveHintText" class="empty-hint sd-live">{{ liveHintText }}</div>
    </EmptyState>
  </div>
</template>

<script setup>
// 结构化数据卡片：把一条病历抽取出的 9 类要素（疾病 / 症状 / 证候 / 方剂 / 中药 / 舌象 / 脉象 / 病因 / 治法）
// 按「有无独立词典」分组渲染，实体上标注来源（规则 / 模型）与归一命中等级。
// 设计取舍：空态必须区分「压根没抽过」与「抽过但没识别出要素」——两者的下一步动作不同。
import { computed, watch } from 'vue'
import EmptyState from '@/components/EmptyState.vue'
import { useNlpStatus } from '@/composables/useNlpStatus'
import { ENTITY_SECTIONS, LEVEL_SHORT, LEVEL_FULL, LEVEL_UNMATCHED, entityName, pct } from '@/utils/structured'

const props = defineProps({
  data: { type: [String, Object], default: null },
  /**
   * 28.17：是否允许点击实体定位原文。默认 false —— 同一张卡片还被病历详情弹窗
   * 等处复用，那些场景没有可定位的原文区，开启只会给一个点了没反应的交互。
   */
  locatable: { type: Boolean, default: false }
})

// 28.17：点击实体时抛出的定位关键词。优先用 sourceText（归一前原文，才是原文里真实存在的串），
// 没有就退回标准词名 —— 未归一实体的 content 本身通常就是原文。
const emit = defineEmits(['locate'])
const locateText = (sec, it) => String(it.sourceText || entityName(sec, it) || '')

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

// 词典版本元信息（落库时打点，见 StructuredDataMeta）；旧数据没有则为空、不展示
const dictVersion = computed(() => parsed.value?._meta?.dictVersion || '')
// 归一实际覆盖的词条数：新数据才有；旧数据为空则模板退回显示指纹
const dictTermCount = computed(() => parsed.value?._meta?.dictTermCount || 0)
// 人工修改标记（后端在复核提交 / 手工改结构化数据时打；清洗归一这类自动流程不打）
const manuallyEdited = computed(() => parsed.value?._meta?.manuallyEdited === true)
const editedBy = computed(() => parsed.value?._meta?.editedBy || '')
const editedAt = computed(() => parsed.value?._meta?.editedAt || '')
// 词典采集时间（与 dictVersion 同一处 _meta 打点）；旧数据没有则空串、不展示
const dictCapturedAt = computed(() => parsed.value?._meta?.dictCapturedAt || '')

/**
 * 空态的两种含义（见模板注释）。
 *
 * <p>{@code parsed === null} 表示压根没有数据：{@code data} 为 null/空串，或 JSON 解析失败。
 * 反之为「有数据但 9 类都空」，即抽取执行过、只是没识别出要素。</p>
 */
const neverParsed = computed(() => parsed.value === null)
// 空态标题：按 neverParsed 区分「尚未抽取」与「已抽取但无要素」
const emptyTitle = computed(() => (neverParsed.value ? '尚未抽取标准化数据' : '已抽取，但没有识别出要素'))
// 空态副文案：给出与标题对应的下一步动作（去执行抽取 / 无需处理）
const emptyHint = computed(() => (neverParsed.value
  ? '这份病历还没有跑过结构化抽取。可在「结构化解析」页载入该病历后点「执行抽取」，结果会写入这里。'
  : '抽取已经执行过，只是这段原文里没有可归一的要素（疾病 / 症状 / 证候 / 方剂 / 中药等）。'))

/**
 * 抽取时刻的降级标记（25.7）。
 *
 * <p>单条解析写回结构化数据时会把 {@code unavailableReason} 一起存下来；批量解析不写原因，
 * 只能退回 {@code modelAvailable === false} 判断（旧数据、批量数据都走这条）。注意
 * {@code modelAvailable} 缺省（undefined）不代表降级 —— 手写或精简过的结构化数据没有这个字段，
 * 只有显式 {@code false} 才算那次抽取是降级产物。</p>
 */
const storedDegrade = computed(() => {
  const p = parsed.value
  if (!p) return ''
  if (p.unavailableReason) return p.unavailableReason
  return p.modelAvailable === false ? 'MODEL_MISSING' : ''
})

// 降级原因 →「为什么这份数据少 / 空」；以数据自带的原因（抽取时刻的真相）为准
const REASON_TEXT = {
  DISABLED: '保存这份数据时，自动抽取功能没有开启，结果里不会包含模型识别出的要素。',
  SERVICE_UNREACHABLE: '保存这份数据时，抽取服务连不上，结果里不会包含模型识别出的要素。',
  MODEL_MISSING: '保存这份数据时，抽取服务的模型没有加载成功，只有规则兜底（舌象 / 脉象 / 病因 / 治法）。'
}
const degradeText = computed(() => REASON_TEXT[storedDegrade.value] || '')

/**
 * 「已抽取、9 类全空、且数据里没写降级原因」这一含糊情形，才值得多问一句服务状态：
 * 空结果既可能是原文确实没写，也可能来自一次降级抽取。其余情形不问 ——
 * 压根没抽过（下一步是去抽取，与服务状态无关），或数据自带原因（以抽取时刻为准）。
 */
const ambiguousEmpty = computed(() => !neverParsed.value && !hasAny.value && !storedDegrade.value)

const { status: nlpStatus, probeNlp } = useNlpStatus()
watch(ambiguousEmpty, (v) => {
  if (v) probeNlp()
}, { immediate: true })

// 实时探测的补白：当前服务仍降级时，把「也可能是一次降级抽取」说出来；
// 探测不到（后端连不上）或服务正常时不加这句，避免对存量正常数据误报
const LIVE_TEXT = {
  DISABLED: '另外：抽取服务当前仍未启用，这份空结果也可能来自一次降级抽取，而不只是「原文没写要素」。',
  SERVICE_UNREACHABLE: '另外：抽取服务当前仍连不上，这份空结果也可能来自一次降级抽取，而不只是「原文没写要素」。',
  MODEL_MISSING: '另外：抽取服务的模型当前仍未加载成功，这份空结果也可能只是规则兜底的结果。'
}
const liveHintText = computed(() => (ambiguousEmpty.value
  ? LIVE_TEXT[nlpStatus.value?.unavailableReason] || ''
  : ''))

// 实体上的归一标签文案：命中方式；未命中说「未收录」
const lvText = (it) => (it.normLevel ? LEVEL_SHORT[it.normLevel] || it.normLevel : LEVEL_UNMATCHED)
/** 描边颜色：命中按精确度分三级，未收录走中性灰 —— 灰的是「没查到」，
    红黄是「查到了但可能不准」，两者不该同色 */
const lvClass = (it) => (it.normLevel ? `lv${it.normLevel}` : 'lv0')

// 悬停标题：「原文 → 标准词」，未命中/无词典时只说实体本身
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
 * <p>行数刻意压到 4 行以内（原先是 8 行：归一 / 怎么比的 / 走哪条路 / 词典来源 / 编码 /
 * 原文片段 / 来源 / 置信度）。合并方式：「走哪条路」并进「归一」一行；「来源 + 置信度」合成一行；
 * 「原文片段」本来就已经写在标题的「原文 → 标准词」里，
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
  } else {
    rows.push({ k: '归一', v: '未命中词典，按原文返回' })
  }

  // 3. 追加来源行并返回（保证任何实体至少「归一 + 来源」两行）
  rows.push({ k: '来源', v: sourceLine(it) })
  return rows
}

// 来源行：模型抽取带置信度；规则兜底是确定性匹配，没有置信度可言
const sourceLine = (it) => {
  if (it.source === 'rule') return '规则兜底（确定性匹配，没有置信度）'
  const c = pct(it.confidence)
  return c ? `模型抽取 · 置信 ${c}` : '模型抽取'
}

// 把「精确/包含/模糊」翻成「跟谁比、怎么比上的」
const levelDesc = (level, raw, name) => {
  const src = raw || name
  if (level === 1) return `原文「${src}」与词典里的标准词或别名完全一致`
  if (level === 2) return `原文「${src}」与词典标准词「${name}」互相包含（一方含另一方）`
  if (level === 3) return `原文「${src}」与词典标准词「${name}」字面相似（字符重合度 ≥ 80%），属推测命中`
  return '—'
}
</script>

<style scoped>
.sd-card { font-size: var(--fs-base); }
.sd-meta {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-bottom: 10px;
  padding-bottom: 6px;
  border-bottom: 1px dashed var(--line);
}
.sd-meta b { color: var(--ink-mid); }
.sd-meta-t { margin-left: var(--sp-1); }
.sd-manual {
  margin-top: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--ochre);
  border-radius: 4px;
  background: var(--ochre-surface, var(--ochre-light));
  color: var(--text);
  font-size: var(--fs-xs);
}
.sd-manual strong {
  margin-right: var(--sp-2);
  color: var(--ochre-text);
}
.sd-manual-t {
  margin-left: var(--sp-2);
  color: var(--text-sub-strong);
}
/* 降级提示（25.7）：解释「为什么少 / 空」。与「人工修改」同款位置，
   用危险色系是因为它说明这份数据不完整，而不是一条中性元信息 */
.sd-degrade {
  margin-top: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid #e3c3bb;
  border-left: 3px solid var(--danger);
  border-radius: 4px;
  background: var(--danger-surface);
  color: #8a3d33;
  font-size: var(--fs-xs);
  line-height: 1.7;
}
.sd-degrade strong {
  margin-right: var(--sp-2);
  color: var(--danger);
}
.sd-meta-fp {
  margin-left: var(--sp-1);
  color: var(--text-sub-strong);
  cursor: help;
  border-bottom: 1px dotted var(--line);
}
.sd-sec { margin-bottom: var(--sp-3); }
.sd-sec-title {
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: var(--sp-2);
  margin-bottom: var(--sp-2);
}
.sd-sec-hint { font-size: var(--fs-xs); color: var(--text-sub-strong); margin-left: 6px; }
.sd-items { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.sd-item {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: var(--sp-1) var(--sp-3);
  color: var(--ink);
}
.sd-item .src { font-size: var(--fs-xs); color: var(--text-sub-strong); margin-left: 6px; }
.sd-item.clickable { cursor: pointer; transition: border-color var(--dur-fast) var(--ease-out), box-shadow var(--dur-fast) var(--ease-out); }
.sd-item.clickable:hover { border-color: var(--ink-mid); box-shadow: 0 1px 4px rgba(47, 70, 57, 0.12); }
.sd-item .dosage { color: var(--ochre-text); margin-left: var(--sp-1); }
.sd-item .tag {
  font-style: normal;
  font-size: var(--fs-xs);
  margin-left: 6px;
  padding: 0 var(--sp-1);
  border-radius: 2px;
  color: var(--surface);
  background: var(--ink-mid);
}
.sd-item .tag.rule { background: var(--ochre-deep); }
.sd-item .tag.lv {
  background: transparent;
  border: 1px solid currentColor;
  padding: 0 var(--sp-1);
}
.sd-item .tag.lv0 { color: var(--text-sub-strong); }
.sd-item .tag.lv1 { color: var(--ink-mid); }
.sd-item .tag.lv2 { color: var(--ochre-text); }
.sd-item .tag.lv3 { color: var(--danger); }
.sd-item .conf { font-size: var(--fs-xs); color: var(--text-sub-strong); margin-left: var(--sp-1); }
/* 空态副文案：标题只说「哪一种空」，下一步动作放这里 */
.empty-hint {
  max-width: 420px;
  margin: 2px auto 0;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
/* 原因补白：与空态正文分开一段，颜色偏警示 —— 它说的是「这份空不一定可信」 */
.sd-live {
  margin-top: var(--sp-2);
  color: #8a3d33;
}
</style>

<!-- 非 scoped：el-tooltip 的内容被 teleport 到 body，scoped 选择器命中不到，必须用全局块 -->
<style>
.el-popper .tp { max-width: 340px; font-size: var(--fs-xs); line-height: 1.7; }
.el-popper .tp-hd { font-weight: bold; margin-bottom: var(--sp-1); color: #2b2b2b; }
.el-popper .tp-row { display: flex; gap: var(--sp-2); }
.el-popper .tp-k { flex: 0 0 62px; color: #8a8578; }
.el-popper .tp-v { flex: 1 1 auto; color: #2b2b2b; }
</style>
