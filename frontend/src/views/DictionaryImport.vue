<template>
  <div class="dict-import">
    <!--
      批量导入词典。

      **两种去向，按身份给最小选择**：
        · 所有人 → 导入「本机个人词典」（localStorage，只存你这台浏览器，不碰小组基线）
        · 管理员 → 还可选择「直接生效」写进小组基线（特权通道，不走审核）
      成员若想把本地词表推广给小组：到「词典」页 →「我的词典」提交提案，组长审核后合并。
    -->
    <PanelCard title="批量导入词典">
      <!-- 第一步：选类型 -->
      <div class="step">
        <div class="step-no">1</div>
        <div class="step-body">
          <div class="step-t">选术语类型</div>
          <div class="step-d">要与「小组基线」里现有的类型一致，导入后归一才会按新词条命中。</div>
          <el-select v-model="type" style="width: 160px" size="small" aria-label="术语类型">
            <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </div>
      </div>

      <!-- 第二步：选文件 -->
      <div class="step">
        <div class="step-no">2</div>
        <div class="step-body">
          <div class="step-t">上传词典文件</div>
          <div class="step-d">
            支持 Excel(.xlsx/.xls)、CSV、JSON。首列必须是<b>标准术语</b>，第二列<b>别名</b>（多个用「、」分隔，可选）。
          </div>
          <el-upload
            ref="uploadRef"
            v-model:file-list="dictFileList"
            drag
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            :on-exceed="onFileExceed"
            accept=".xlsx,.xls,.csv,.json"
          >
            <div class="upload-tip">
              拖拽文件到此处，或<em>点击选择</em>
              <div class="sub">支持 Excel / CSV / JSON，单个文件不超过 50MB</div>
            </div>
          </el-upload>
        </div>
      </div>

      <!-- 第三步：确认 -->
      <div class="step">
        <div class="step-no">3</div>
        <div class="step-body">
          <div class="step-t">选择去向并确认</div>
          <div class="step-d">{{ modeTip }}</div>

          <!-- 管理员可选「直接生效」；其余身份只有本地一条路，不给选择避免困惑 -->
          <div v-if="isAdmin" class="target-row">
            <el-radio-group v-model="mode" size="small">
              <el-radio-button value="local">导入本机个人词典</el-radio-button>
              <el-radio-button value="direct">直接生效到小组基线</el-radio-button>
            </el-radio-group>
          </div>

          <div v-if="isAdmin && mode === 'direct'" class="target-row">
            <el-radio-group v-model="target" size="small">
              <el-radio-button value="org">当前组织</el-radio-button>
              <el-radio-button value="base">基础层（影响所有组织）</el-radio-button>
            </el-radio-group>
          </div>

          <el-button
            type="primary"
            class="do-btn"
            :loading="submitting"
            :disabled="!importFile"
            @click="handleSubmit"
          >{{ submitLabel }}</el-button>
          <span v-if="!importFile" class="tip">请先在上一步选择文件</span>
        </div>
      </div>

<div v-if="result" class="import-result">
          <StatCard label="文件解析" :value="result.parsed" />
          <StatCard v-if="result.failed > 0" label="解析失败" :value="result.failed" tone="red" />
          <StatCard v-if="result.added != null" label="本地新增" :value="result.added" />
          <div class="what-next">
            <b>接下来会怎样：</b>{{ nextStepText }}
          </div>

          <!-- 词表体检（批次 21）：这些问题导入时不会报错，
               但会让词条悄悄变少或归一失效，所以在这里指出来 -->
          <div v-if="lintIssues.length" class="lint">
            <div class="lint-hd">
              词表体检：{{ lintErrors.length }} 项需要修改，{{ lintWarnings.length }} 项建议确认
            </div>
            <div v-for="(it, i) in lintIssues" :key="i" class="lint-item" :class="it.level">
              <div class="lint-top">
                <el-tag size="small" :type="it.level === 'error' ? 'danger' : 'warning'" effect="plain">
                  {{ it.level === 'error' ? '需修改' : '建议确认' }}
                </el-tag>
                <span class="lint-msg">{{ it.message }}</span>
                <span v-if="it.count > 1" class="lint-count">（{{ it.count }} 条）</span>
              </div>
              <div v-if="it.terms" class="lint-terms">{{ it.terms }}</div>
              <div v-if="it.advice" class="lint-advice">{{ it.advice }}</div>
            </div>
          </div>

          <div v-if="result.failures?.length" class="failures">
            <div class="ded-hd">解析失败的行（这些不会被导入）</div>
            <div v-for="(f, i) in result.failures" :key="i" class="ded-item">
              第 {{ f.row }} 行：{{ f.reason }}
            </div>
          </div>
        </div>
    </PanelCard>

    <PanelCard title="格式示例">
      <!-- 直接给可照抄的样子，比抽象描述省事 -->
      <div class="sample">
        <div class="sample-t">Excel / CSV（三列：标准术语、别名、国标代码）</div>
        <table class="sample-tb">
          <thead><tr><th>标准术语</th><th>别名</th><th>国标代码</th></tr></thead>
          <tbody>
            <tr><td>肝郁气滞</td><td>肝气郁结、肝郁</td><td>ZYBNR0101</td></tr>
            <tr><td>柴胡</td><td>北柴胡、醋柴胡</td><td></td></tr>
          </tbody>
        </table>
        <div class="sample-t" style="margin-top: var(--sp-3)">JSON（等价写法）</div>
        <pre class="code">[
  { "standardTerm": "肝郁气滞", "aliases": ["肝气郁结", "肝郁"] },
  { "standardTerm": "柴胡", "aliases": ["北柴胡", "醋柴胡"] }
]</pre>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
// 批量导入词典。所有人可导入本机个人词典；管理员可直写基线。
import { ref, computed } from 'vue'
import { ElMessage, genFileId } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import { importDict, parseDictFile } from '@/api/dictionary'
import { confirmBox } from '@/utils/confirm'
import { useUserStore } from '@/stores/user'

const TYPES = [
  { value: 'disease', label: '疾病' },
  { value: 'pattern', label: '证候' },
  { value: 'symptom', label: '症状' },
  { value: 'herb', label: '中药' },
  { value: 'formula', label: '方剂' },
  { value: 'tongue', label: '舌象' },
  { value: 'pulse', label: '脉象' },
  { value: 'treatment', label: '治法' }
]
const typeLabel = (v) => (TYPES.find((t) => t.value === v) || {}).label || v

const userStore = useUserStore()
const isAdmin = computed(() => userStore.role === '管理员')

const uploadRef = ref(null)
const dictFileList = ref([])
const importFile = ref(null)
const submitting = ref(false)
const result = ref(null)
/** 词表体检结果（批次 21），来自 /parse 响应的 lint 段 */
const lint = ref(null)
const type = ref('herb')
// local = 并入本机个人词典（所有人）；direct = 直接写小组基线（仅管理员）
const mode = ref(isAdmin.value ? 'direct' : 'local')
const target = ref('org')

const modeTip = computed(() => {
  if (isAdmin.value && mode.value === 'direct') {
    return '文件直接覆盖写入小组基线并生成归档版本，不经过审核，立即对所有成员生效。仅在确信无误时使用。'
  }
  return '文件解析后并入你的本机个人词典（只存在你这台浏览器，不影响小组基线，也不影响其他成员）。'
})

const submitLabel = computed(() =>
  isAdmin.value && mode.value === 'direct' ? '直接导入' : '导入本地词典')

const nextStepText = computed(() => {
  if (isAdmin.value && mode.value === 'direct') {
    return '已写入小组基线，可在「词典」页的「归档版本」查看这次的快照。'
  }
  return '到「词典」页 →「我的词典」可查看这些词；如想推广给小组，在那里点「提交提案」，组长审核通过后才会进入小组基线。'
})

// 词表体检（批次 21）：优先取后端已排好序的 topIssues，没有就退回 errors+warnings。
// 用 topIssues 是因为它按「影响条数」降序 —— 界面上第一条永远是覆盖面最大、
// 改一处能消掉一大片的那条，而不是按检查顺序流水账。
const lintIssues = computed(() => {
  if (!lint.value) return []
  if (Array.isArray(lint.value.topIssues) && lint.value.topIssues.length) {
    return lint.value.topIssues
  }
  return [...(lint.value.errors || []), ...(lint.value.warnings || [])]
})
const lintErrors = computed(() => lint.value?.errors?.length || 0)
const lintWarnings = computed(() => lint.value?.warnings?.length || 0)

// 个人词典在 localStorage，键与词典页「我的词典」保持一致
const localKey = () => `dict.local.${userStore.orgId || 'base'}.${type.value}`

/** 把解析出的词条并入本机个人词典，返回新增条数；同名词以文件为准 */
function mergeIntoLocal(terms) {
  let obj = {}
  try {
    obj = JSON.parse(localStorage.getItem(localKey()) || '{}') || {}
  } catch (e) {
    obj = {}
  }
  const list = Array.isArray(obj.terms) ? obj.terms : []
  const byTerm = new Map(list.map((t) => [t.standardTerm, t]))
  let added = 0
  for (const t of terms) {
    if (!byTerm.has(t.standardTerm)) added++
    byTerm.set(t.standardTerm, {
      standardTerm: t.standardTerm,
      aliases: t.aliases || [],
      source: t.source || '批量导入'
    })
  }
  localStorage.setItem(localKey(), JSON.stringify({
    at: new Date().toLocaleString(),
    terms: [...byTerm.values()]
  }))
  return added
}

const onFileChange = (file) => {
    importFile.value = file
    result.value = null
    // 换文件就清掉上一份的体检结果，否则会误以为是新文件的问题
    lint.value = null
  }
  const onFileRemove = () => {
    importFile.value = null
    result.value = null
    lint.value = null
  }
const onFileExceed = (files) => {
  uploadRef.value?.clearFiles()
  const f = files[0]
  if (!f) return
  f.uid = genFileId()
  uploadRef.value?.handleStart(f)
  importFile.value = f
}

const handleSubmit = async () => {
  if (!importFile.value) return
  const direct = isAdmin.value && mode.value === 'direct'
  const where = direct
    ? (target.value === 'base' ? '基础层（影响所有组织）' : '当前组织')
    : '本机个人词典'
  const ok = await confirmBox(
    direct
      ? `将用「${importFile.value.name}」直接覆盖【${where}】的${typeLabel(type.value)}词典，立即生效。`
      : `将把「${importFile.value.name}」解析后并入${where}（${typeLabel(type.value)}），不影响小组基线。`,
    direct ? '确认直接导入' : '确认导入本地',
    { type: 'warning', confirmButtonText: direct ? '直接导入' : '导入本地', cancelButtonText: '取消' }
  )
  if (!ok) return

  submitting.value = true
  try {
    const form = new FormData()
    form.append('file', importFile.value)
    form.append('type', type.value)
    if (direct) {
      const res = await importDict(form, target.value)
      result.value = {
        parsed: res.data?.imported ?? 0,
        failed: res.data?.failed ?? 0,
        failures: res.data?.failures ?? []
      }
      ElMessage.success(res.msg || '已导入并生效')
      } else {
        const res = await parseDictFile(form, type.value)
        const terms = res.data?.terms ?? []
        // 先把体检结果挂上：即便后面因为「没解析出词条」提前返回，
        // 用户也能看到问题出在哪，而不是只得到一句「没有术语」
        lint.value = res.data?.lint ?? null
        if (!terms.length) {
          ElMessage.warning('文件里没有解析出任何术语，请检查首列「标准术语」是否为空')
          return
        }
        let added
        try {
          added = mergeIntoLocal(terms)
        } catch (e) {
          // localStorage 满 / 隐私模式：明确告知没存进去，不假装成功
          ElMessage.warning('本机存储不可用或已满，导入未能保存')
          return
        }
        result.value = {
        parsed: terms.length,
        added,
        failed: (res.data?.failures ?? []).length,
        failures: res.data?.failures ?? []
      }
      ElMessage.success(`已并入本机个人词典（新增 ${added} 条）`)
    }
    uploadRef.value?.clearFiles()
    importFile.value = null
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
/* 步骤条：序号圆点 + 标题 + 说明，降低「不知道下一步做什么」的成本 */
.step {
  display: flex;
  gap: var(--sp-3);
  padding-bottom: var(--sp-4);
  margin-bottom: var(--sp-4);
  border-bottom: 1px dashed var(--line);
}
.step:last-of-type {
  border-bottom: none;
  margin-bottom: 0;
}
.step-no {
  flex: 0 0 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--ink-mid);
  color: var(--surface);
  font-size: 12px;
  font-weight: 600;
}
.step-body {
  flex: 1 1 auto;
  min-width: 0;
}
.step-t {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.step-d {
  font-size: 12.5px;
  color: var(--text-sub);
  line-height: 1.7;
  margin-bottom: var(--sp-2);
}
.target-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}
.do-btn {
  margin-top: 2px;
}
.import-result {
  margin-top: var(--sp-4);
}
/* 词表体检（批次 21）：问题清单。错误与警告用左侧色条区分，不用整块红黄底 ——
   整块底色会让人以为「导入失败了」，其实多数条目仍会正常导入 */
.lint {
  margin-top: var(--sp-3);
}
.lint-hd {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: var(--sp-2);
}
.lint-item {
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: var(--sp-2);
  border-left: 3px solid var(--line);
  background: var(--surface-sub);
  border-radius: 0 4px 4px 0;
}
.lint-item.error {
  border-left-color: var(--danger);
}
.lint-item.warning {
  border-left-color: var(--ochre);
}
.lint-top {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.lint-msg {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.lint-count {
  font-size: 12px;
  color: var(--text-sub);
}
.lint-terms {
  margin-top: 4px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text);
  word-break: break-all;
}
.lint-advice {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-sub);
}
/* 「接下来会怎样」：把结果落到下一步动作上，而不是只报数字 */
.what-next {
  margin-top: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  background: var(--ink-light);
  border-radius: 4px;
  font-size: 13px;
}
/* 格式示例：给可照抄的表 */
.sample-t {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: var(--sp-2);
}
.sample-tb {
  border-collapse: collapse;
  font-size: 12.5px;
}
.sample-tb th,
.sample-tb td {
  border: 1px solid var(--line);
  padding: 5px 12px;
  text-align: left;
}
.sample-tb th {
  background: var(--surface-sub);
  font-weight: 600;
}
.code {
  margin: 0;
  padding: var(--sp-3);
  background: var(--surface-sub);
  border-radius: 4px;
  font-size: 12.5px;
  line-height: 1.7;
  overflow-x: auto;
}
</style>
