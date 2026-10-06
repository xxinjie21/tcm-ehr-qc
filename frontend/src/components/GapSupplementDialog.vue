<template>
  <el-dialog
    :model-value="modelValue"
    title="补齐待补词（症状类）"
    width="560px"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <p class="gap-lead">
      这些词来自未归一的「词表缺口」，按出现次数取前 8 个。
      写入后需重跑结构化解析，才会在归一率上体现出来。
    </p>
    <el-checkbox-group v-model="gapWords" class="gap-words">
      <el-checkbox v-for="w in gapWordsAll" :key="w" :value="w">{{ w }}</el-checkbox>
    </el-checkbox-group>
    <div class="gap-extra">
      <el-input
        v-model="gapExtra"
        size="small"
        placeholder="补充词，多个用「、」或逗号分隔（可选）"
      />
    </div>
    <div v-if="userStore.isAdmin" class="gap-target">
      <span class="gap-target-label">写入层级</span>
      <el-radio-group v-model="gapTarget" size="small">
        <el-radio value="org" :disabled="!userStore.hasOrg">本组织词典</el-radio>
        <el-radio value="base">基础词典（所有组织共用）</el-radio>
      </el-radio-group>
    </div>
    <p class="gap-note">
      将写入 <b>{{ gapSelected.length }}</b> 个词：
      <span class="gap-preview">{{ gapSelected.join('、') || '（尚未选择）' }}</span>
    </p>
    <p v-if="userStore.isAdmin && gapTarget === 'base' && userStore.hasOrg" class="gap-note">
      基础层变更只能由管理员直接写入；提案只作用于本组织词典，故这里不提供「生成提案」。
    </p>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button
        v-if="canImportGap"
        type="primary"
        plain
        :loading="gapBusy"
        @click="doGapImport"
      >直接加入词典</el-button>
      <el-button
        v-if="canProposeGapNow"
        type="primary"
        :loading="gapBusy"
        @click="doGapProposal"
      >生成提案（需审核）</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 25.13 一键补词：把「标准化质量报告」里「先补这几个词」的建议变成一个能直接执行的动作。
// 两条出口对应两种权限：有写权限的直写（立即生效 + 归档版本），普通成员走提案（需审核）。
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { importDict, exportBaseline, submitProposal } from '@/api/dictionary'
import { confirmBox } from '@/utils/confirm'

const props = defineProps({
  /** 对话框显示状态（v-model） */
  modelValue: { type: Boolean, default: false },
  /** 候选待补词（来自报告的词表缺口 TOP-N） */
  words: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

// 未归一的「词表缺口 TOP-N」全部来自症状类：后端 StandardizationReportServiceImpl.unmatched()
// 只遍历 structured_data 的 symptoms 字段，所以补词的落点恒为 symptom，
// 不跟随「当前瓶颈」的类型走 —— 瓶颈可能是脉象、体征，那些词根本不在这份 TOP-N 里。
const GAP_DICT_TYPE = 'symptom'
const userStore = useUserStore()
const gapWordsAll = ref([])   // 对话框里可勾选的候选词
const gapWords = ref([])      // 已勾选
const gapExtra = ref('')      // 手输补充词
const gapTarget = ref('org')  // 写入层级：org / base
const gapBusy = ref(false)
// 直写词典要写权限（管理员 / 组织所有者 / 被授权成员）
const canImportGap = computed(() => userStore.canWriteDictionaryEntry)
// 提案只需「登录 + 属于一个组织」；基础层提案仅管理员可提交，故无组织又非管理员时不给入口
const canProposeGap = computed(() => userStore.isAdmin || userStore.hasOrg)
// 提案落点是「当前组织」；只有无组织的管理员才落基础层。
// 因而选了基础层、又有组织时，提案按钮必须收起（否则文案说基础层、实际提案进本组织）。
const canProposeGapNow = computed(
  () => canProposeGap.value && (gapTarget.value === 'org' || !userStore.hasOrg)
)

/** 每次打开：候选词与勾选都初始化为这批 TOP-N */
watch(() => props.modelValue, (v) => {
  if (v) initFromWords(props.words)
})

function initFromWords(words) {
  const list = [...(words || [])]
  gapWordsAll.value = list
  // 另存一份：勾选态由 checkbox-group 接管，不能与候选项共用同一个数组引用
  gapWords.value = [...list]
  gapExtra.value = ''
  // 无组织时只能写基础层；有组织默认写本组织（影响面最小）
  gapTarget.value = userStore.isAdmin && !userStore.hasOrg ? 'base' : 'org'
}

function close() {
  emit('update:modelValue', false)
}

/** 勾选 + 手输合并去重（手输支持顿号/逗号/空格/换行分隔） */
const gapSelected = computed(() => {
  const extra = gapExtra.value
    .split(/[、,，\s\n]+/)
    .map((w) => w.trim())
    .filter(Boolean)
  const seen = new Set()
  const out = []
  for (const w of [...gapWords.value, ...extra]) {
    const k = w.toLowerCase()
    if (seen.has(k)) continue
    seen.add(k)
    out.push(w)
  }
  return out
})

/** 组装导入/提案用的词条（JSON 走合并语义，只放新词，不会覆盖已有词条） */
function gapEntries(words, source) {
  return words.map((w) => ({ standardTerm: w, aliases: [], source, code: '' }))
}

/**
 * 直接加入词典（有写权限者）。
 *
 * 用 JSON 而不是 CSV：CSV 解析不跳表头，第一行会被当成一个词条。
 */
async function doGapImport() {
  const words = gapSelected.value
  if (!words.length) {
    ElMessage.warning('请至少选择一个待补词')
    return
  }
  const target = gapTarget.value
  const scope = target === 'base' ? '基础词典（所有组织共用）' : '本组织词典'
  const ok = await confirmBox(
    `将把 ${words.length} 个词写入${scope}，立即生效并生成一个可回滚的归档版本。`,
    '确认加入词典',
    { type: 'warning', confirmButtonText: '加入' }
  )
  if (!ok) return
  gapBusy.value = true
  try {
    const blob = new Blob(
      [JSON.stringify(gapEntries(words, '标准化质量报告-待补词'))],
      { type: 'application/json' }
    )
    const form = new FormData()
    form.append('file', blob, `gap-${GAP_DICT_TYPE}.json`)
    form.append('type', GAP_DICT_TYPE)
    const res = await importDict(form, target)
    const d = res.data || {}
    ElMessage.success(
      `已加入 ${d.imported || 0} 个词`
      + (d.failed ? `，${d.failed} 个失败` : '')
      + `，归档 v${d.archiveVersion ?? '—'}`
    )
    if (d.archiveWarning) ElMessage.warning(d.archiveWarning)
    close()
  } catch {
    // 拦截器已提示（无权限 / 类型非法 / 锁冲突 409 等），这里不叠加泛化文案
  } finally {
    gapBusy.value = false
  }
}

/**
 * 生成提案（普通成员出口）。
 *
 * 提案携带的是**完整目标词典**，所以先把本层基线拉下来再追加，
 * 否则一次提交就会把基线里其余词条删掉（后端有 80% 规模下限兜底）。
 */
async function doGapProposal() {
  const words = gapSelected.value
  if (!words.length) {
    ElMessage.warning('请至少选择一个待补词')
    return
  }
  gapBusy.value = true
  try {
    const base = (await exportBaseline({ type: GAP_DICT_TYPE })).data || []
    const existing = new Set(
      base.map((e) => String(e.standardTerm || '').trim().toLowerCase())
    )
    const added = words.filter((w) => !existing.has(w.trim().toLowerCase()))
    if (!added.length) {
      ElMessage.info('这些词已在本层级的症状词典里，无需再提交提案')
      return
    }
    const terms = [...base, ...gapEntries(added, '标准化质量报告-待补词')]
    await submitProposal({ type: GAP_DICT_TYPE, terms })
    ElMessage.success(`提案已提交（新增 ${added.length} 个词），审核通过后生效`)
    close()
  } catch {
    // 已提示（如待审提案已达 5 条上限、无权限）
  } finally {
    gapBusy.value = false
  }
}
</script>

<style scoped>
/* 25.13 一键补词 */
.gap-lead {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-md);
  line-height: 1.7;
  color: var(--text-sub);
}
.gap-words {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-1) var(--sp-3);
  max-height: 160px;
  overflow-y: auto;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--line);
  border-radius: 4px;
}
.gap-extra { margin-top: var(--sp-3); }
.gap-target {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
  margin-top: var(--sp-3);
}
.gap-target-label {
  flex: 0 0 auto;
  font-size: var(--fs-md);
  color: var(--text-sub);
}
.gap-note {
  margin: var(--sp-3) 0 0;
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--text-sub);
  word-break: break-all;
}
.gap-preview { color: var(--ink); }
</style>
