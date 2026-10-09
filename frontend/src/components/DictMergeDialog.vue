<template>
  <!-- 合并确认弹窗：拉取 / 批量导入进本地词典之前，把「需要人拍板」的部分摆出来。
       默认口径（同名词取并集、冲突按本地）已经算好了，所以这里的默认按钮是「确认合并」——
       不是为了挡人，而是因为下面这两类差异**会改变归一结果**，不能悄悄替用户决定。 -->
  <el-dialog
    :model-value="visible"
    class="merge-dialog"
    title="合并到本地词典"
    width="min(960px, 94vw)"
    top="6vh"
    :close-on-click-modal="false"
    @update:model-value="(v) => emit('update:visible', v)"
  >
    <div v-if="result" class="md-body">
      <!-- 概览：先说清这次合并到底干了什么，用户再决定要不要展开细节 -->
      <div class="md-summary">
        <span class="ms-item">新来 <b>{{ incomingCount }}</b> 条</span>
        <span class="ms-sep">·</span>
        <span class="ms-item">新增 <b>{{ result.added }}</b> 条</span>
        <span class="ms-sep">·</span>
        <span class="ms-item">同名合并 <b>{{ result.sameTermDiff.length }}</b> 条</span>
        <span class="ms-sep">·</span>
        <span class="ms-item" :class="{ 'ms-warn': result.collisions.length }">
          需人工确认 <b>{{ result.collisions.length }}</b> 条
        </span>
        <span class="ms-sep">·</span>
        <span class="ms-item">合并后合计 <b>{{ result.total }}</b> 条</span>
      </div>
      <div class="tip md-note">
        合并只增不删：本地已有的词条不会被整份换掉，要删词请到「我的词典」表里手动删。
        别名一律取并集去重，与后端合并口径一致。
      </div>

      <!-- 一、归属冲突：并集解决不了（两边都要这个词），必须选一边 -->
      <template v-if="result.collisions.length">
        <div class="md-head">
          <span class="md-title">需人工确认（{{ result.collisions.length }} 条）</span>
          <span class="tip">同一个词在两边归属不同，归一结果会因此不同，请逐条选一边</span>
          <el-button
            size="small"
            type="primary"
            plain
            :loading="aiLoading"
            @click="askAi"
          >AI 建议</el-button>
        </div>
        <el-table :data="result.collisions" border size="small" max-height="280">
          <el-table-column prop="word" label="字面" min-width="110" />
          <el-table-column prop="localView" label="本地认为" min-width="150" />
          <el-table-column prop="incomingView" label="新来认为" min-width="150" />
          <el-table-column label="处理" width="170">
            <template #default="{ row }">
              <el-radio-group v-model="collisionModes[row.word]" size="small">
                <el-radio-button value="local">按本地</el-radio-button>
                <el-radio-button value="incoming">按新来</el-radio-button>
              </el-radio-group>
            </template>
          </el-table-column>
          <el-table-column label="AI 建议" min-width="240">
            <template #default="{ row }">
              <template v-if="aiMap[row.word]">
                <span class="as-reason">{{ aiText(aiMap[row.word]) }}</span>
                <el-tag
                  v-if="aiMap[row.word].source === 'model'"
                  size="small"
                  type="warning"
                  effect="plain"
                  class="as-tag"
                >AI 生成</el-tag>
                <el-tag v-else size="small" effect="plain" class="as-tag">词表命中</el-tag>
                <el-button
                  v-if="aiPick(row) !== row.mode"
                  link
                  type="primary"
                  size="small"
                  @click="collisionModes[row.word] = aiPick(row)"
                >采纳</el-button>
              </template>
              <span v-else class="tip">—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 二、同名但别名不一致：默认并集，列出来让人过一眼 -->
      <template v-if="result.sameTermDiff.length">
        <div class="md-head">
          <span class="md-title">同名、别名不一致（{{ result.sameTermDiff.length }} 条）</span>
          <span class="tip">默认取并集，可逐条改成「用新的」或「保留本地」</span>
          <el-radio-group v-model="bulkMode" size="small" @change="applyBulk">
            <el-radio-button value="union">全部取并集</el-radio-button>
            <el-radio-button value="incoming">全部用新的</el-radio-button>
            <el-radio-button value="local">全部保留本地</el-radio-button>
          </el-radio-group>
        </div>
        <el-table :data="result.sameTermDiff" border size="small" max-height="280">
          <el-table-column prop="standardTerm" label="标准词" min-width="120" />
          <el-table-column label="仅本地有" min-width="160">
            <template #default="{ row }">
              <span v-if="!row.onlyLocal.length" class="tip">—</span>
              <el-tag v-for="a in row.onlyLocal" :key="a" size="small" effect="plain" class="alias-tag">{{ a }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="仅新来有" min-width="160">
            <template #default="{ row }">
              <span v-if="!row.onlyIncoming.length" class="tip">—</span>
              <el-tag v-for="a in row.onlyIncoming" :key="a" size="small" type="success" effect="plain" class="alias-tag">{{ a }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处理" width="200">
            <template #default="{ row }">
              <el-radio-group v-model="sameTermModes[row.standardTerm]" size="small">
                <el-radio-button value="union">并集</el-radio-button>
                <el-radio-button value="incoming">用新的</el-radio-button>
                <el-radio-button value="local">保留本地</el-radio-button>
              </el-radio-group>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <div v-if="!result.collisions.length && !result.sameTermDiff.length" class="md-clean">
        没有需要确认的差异：新来的词条要么是全新的，要么与本地完全一致。
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" @click="confirmMerge">
        确认合并（{{ previewCount }} 条）
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 合并确认弹窗。
 *
 * <p>只做「把差异摆给人看 + 收选择」，不自己算合并 —— 合并口径在
 * {@code utils/dictMerge.js}，拉取与批量导入两条入口共用同一套，避免两处口径漂移。</p>
 */
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { resolveMerged } from '@/utils/dictMerge'
import { runTermSuggest } from '@/api/ai'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** mergeTermLists 的返回值；null 表示还没算 */
  result: { type: Object, default: null },
  /** 术语类型 key，AI 建议要用 */
  typeKey: { type: String, default: '' }
})
const emit = defineEmits(['update:visible', 'confirm'])

// 标准词 → 处理方式；冲突字面 → 按哪边。都是「用户改过的才记」，默认走 dictMerge 的默认口径
const sameTermModes = reactive({})
const collisionModes = reactive({})
const bulkMode = ref('union')

// AI 建议：字面 → { action, standardTerm, reason, source }
const aiMap = reactive({})
const aiLoading = ref(false)

const incomingCount = computed(() => props.result?.incomingCount ?? 0)

/** 按当前选择算一遍最终条数，给确认按钮显示 —— 用户改完能立刻看到总数变没变 */
const previewCount = computed(() => {
  if (!props.result) return 0
  return resolveMerged(props.result, { ...sameTermModes }, { ...collisionModes }).length
})

/** 每次打开都重置选择与 AI 结果：上一次的残留会让人以为已经确认过 */
watch(
  () => props.visible,
  (v) => {
    if (!v) return
    for (const k of Object.keys(sameTermModes)) delete sameTermModes[k]
    for (const k of Object.keys(collisionModes)) delete collisionModes[k]
    for (const k of Object.keys(aiMap)) delete aiMap[k]
    bulkMode.value = 'union'
    for (const d of props.result?.sameTermDiff || []) sameTermModes[d.standardTerm] = 'union'
    for (const c of props.result?.collisions || []) collisionModes[c.word] = 'local'
  }
)

const applyBulk = (mode) => {
  for (const d of props.result?.sameTermDiff || []) sameTermModes[d.standardTerm] = mode
}

/** AI 建议文案：把「挂别名 / 新建标准词 / 忽略」翻译成用户能据以选择的说法 */
const aiText = (s) => {
  if (s.action === 'alias' && s.standardTerm) return `建议挂到「${s.standardTerm}」下`
  if (s.action === 'new') return '建议作为独立标准词'
  if (s.action === 'ignore') return 'AI 未表态'
  return 'AI 未表态'
}

/**
 * AI 建议 → 该选「按本地」还是「按新来」。
 *
 * <p>不能凭空翻译：AI 说的是「这个词该归谁」，而「按本地 / 按新来」是两边的归属。
 * 所以拿 AI 给的标准词去两边找 —— 哪边的归属与 AI 一致就选哪边；两边都不一致就不改。</p>
 */
const aiPick = (row) => {
  const s = aiMap[row.word]
  if (!s) return row.mode
  const wantOwner = s.action === 'alias' && s.standardTerm ? String(s.standardTerm).trim() : row.word
  if (row.localOwner === wantOwner) return 'local'
  if (row.incomingOwner === wantOwner) return 'incoming'
  return row.mode
}

/**
 * 一次把**全部**冲突字面交给 termsuggest（它本身就是批量接口）。
 * 逐条调用会在有几十条冲突时打几十次 LLM，既慢又容易触发异步任务排队。
 */
const askAi = async () => {
  const words = (props.result?.collisions || []).map((c) => c.word)
  if (!words.length) return
  aiLoading.value = true
  try {
    const reply = await runTermSuggest({ terms: words, termType: props.typeKey })
    const list = reply?.termSuggestions || []
    if (!list.length) {
      ElMessage.warning('AI 没有返回建议，请自行判断或稍后重试')
      return
    }
    for (const s of list) {
      if (s?.original) aiMap[s.original] = s
    }
    if (!reply.llmAvailable) {
      ElMessage.warning('AI 不可用，以下只是词表中字面相近的候选，仅供参考')
    }
  } catch (e) {
    ElMessage.warning(e?.message || 'AI 建议生成失败')
  } finally {
    aiLoading.value = false
  }
}

const confirmMerge = () => {
  const terms = resolveMerged(props.result, { ...sameTermModes }, { ...collisionModes })
  emit('confirm', terms)
  emit('update:visible', false)
}
</script>

<style scoped>
.md-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--sp-1);
  font-size: var(--fs-base);
  color: var(--text);
}
.ms-item b { color: var(--ink); }
.ms-warn b { color: var(--ochre-text); }
.ms-sep { color: var(--text-sub-strong); }
.md-note { margin-top: var(--sp-2); }
.md-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  margin: var(--sp-3) 0 var(--sp-2);
}
.md-title {
  font-size: var(--fs-base);
  color: var(--ink);
}
.alias-tag { margin-right: 6px; }
.as-reason {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.as-tag { margin-left: var(--sp-1); }
.md-clean {
  margin-top: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  background: var(--surface-sub);
  border-radius: 4px;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
</style>
