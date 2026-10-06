<template>
  <div class="dict-import">
    <!--
      批量导入词典。

      **两种去向，按身份给最小选择**：
        · 所有人 → 导入「本机个人词典」（localStorage，只存你这台浏览器，不碰小组基线）
        · 管理员 → 还可选择「直接生效」写进小组基线（特权通道，不走审核）
      成员若想把本地词表推广给小组：到「词典」页 →「我的词典」提交提案，组长审核后合并。
    -->
    <!-- 导入后的重跑引导（批次 21）：
         词表改了不等于归一结果改了 —— structured_data 是解析时写下的快照。
         不说清楚，用户会以为「导入没生效」，然后反复重传同一个文件。 -->
    <div v-if="rerunNeeded" class="rerun-hint">
      <div class="rh-title">词表已生效，但还需要重跑一次解析</div>
      <div class="rh-desc">
        术语归一的结果存在每条病历的结构化字段里，是<b>解析那一刻算好就固定下来的</b>。
        刚导入的新词条不会自动套到已有病历上 —— 必须重跑「结构化解析 + 质控」才会生效。
      </div>
      <div class="rh-ops">
        <el-button type="primary" size="small" :loading="rerunning" @click="rerunAll">
          立即重跑解析与质控
        </el-button>
        <router-link class="rh-link" to="/standardization-report">查看质量报告</router-link>
        <!-- 与「查看质量报告」并排：导入完这一页的任务就结束了，用户要么去看结果，
             要么回词典确认新词条 —— 两条出口都给，别让人靠侧边栏自己找路 -->
        <router-link class="rh-link" to="/dictionary">返回术语词典</router-link>
        <span class="rh-skip">稍后再说（可随时回来重跑）</span>
      </div>
    </div>

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
          <!-- 批次7：dry-run 预览。后端 /dictionary/parse 明确「只解析、不落库」，
               所以可以放心在点确认之前先把影响面摆出来（条数 + 前几条样例）。 -->
          <div style="margin-top: 6px">
            <span v-if="previewLoading" class="tip">正在解析文件（只解析，不会写入任何数据）…</span>
            <template v-else-if="preview && preview.count">
              <b>将写入 {{ preview.count }} 条术语</b>
              <span v-if="preview.sample.length" class="tip">示例：{{ preview.sample.join('、') }}</span>
            </template>
            <span v-else-if="importFile" class="tip">未取得预览（解析失败或格式不符），仍可继续，但请自行确认文件内容</span>
          </div>

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

          <!-- 导入完成后给出明确出口。
               原先这一页导入完就「断」在这里：没有任何按钮回到术语词典，
               用户只能自己去侧边栏找路（面包屑也不是链接）。 -->
          <div class="import-done-ops">
            <el-button type="primary" @click="goDictionary">返回术语词典</el-button>
            <span class="tip">在「我的词典」里可以核对刚导入的词条</span>
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
import { useRouter } from 'vue-router'
import { ElMessage, genFileId } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import { importDict, parseDictFile } from '@/api/dictionary'
import { submitNlpBatch } from '@/api/nlp'
import { recomputeQc } from '@/api/qc'
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

// 导入完成后回术语词典。用 router.push 而不是 <router-link>：
// 结果区里它要呈现为**主按钮**（这一步的出口），而 rh-link 那套链接样式是给提示条用的。
const router = useRouter()
const goDictionary = () => router.push('/dictionary')

const uploadRef = ref(null)
const dictFileList = ref([])
// 批次7：dry-run 预览结果 { count, sample } 与加载态
const preview = ref(null)
const previewLoading = ref(false)
const importFile = ref(null)
const submitting = ref(false)
const result = ref(null)
/** 词表体检结果（批次 21），来自 /parse 响应的 lint 段 */
const lint = ref(null)
/** 导入完成后置位：提示需要重跑解析，mode=direct 时才提示（本地词典不影响基线） */
const rerunHint = ref(null)
const rerunning = ref(false)
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

/**
 * 批次 7：dry-run 预览 —— 调「只解析、不落库」的解析接口（后端 javadoc 明确如此），
 * 算出条目数与前几条样例。覆盖型导入（组织 / 基础层）会把共享词典整体换掉，
 * 用户点确认前必须看到影响面，而不是只看到文件名。
 */
const loadPreview = async (file) => {
  previewLoading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    form.append('type', type.value)
    const res = await parseDictFile(form, type.value)
    // 兼容两种返回：直接是数组，或包在 terms 里
    const terms = Array.isArray(res.data) ? res.data : (res.data?.terms || [])
    preview.value = {
      count: terms.length,
      sample: terms.slice(0, 6).map((t) => t.standardTerm || t.term || '').filter(Boolean)
    }
  } catch {
    // 预览失败不阻断导入，但绝不假装「0 条」—— 置 null，界面按「未预览」呈现
    preview.value = null
  } finally {
    previewLoading.value = false
  }
}

const onFileChange = (file) => {
    importFile.value = file
    result.value = null
    // 换文件就清掉上一份的体检结果，否则会误以为是新文件的问题
    lint.value = null
    // 批次7：换文件即重算预览，避免「看着 A 的预览导入了 B」
    preview.value = null
    loadPreview(file)
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
      // P1-6：导入成功但「归档版本生成失败」时，后端会回 archiveWarning（含真实原因）——
      // 此前**界面从不显示它**（全仓搜不到该字段），用户以为一切正常：后端已如实报出，价值却没到达用户。
      // 用警告消息（固定位置、可关闭、停留久一点）确保可见，而不是塞进下方可能不在视野内的结果区。
      if (res.data?.archiveWarning) {
        ElMessage.warning({ message: res.data.archiveWarning, duration: 8000, showClose: true })
      }
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
      // 导入完成了，但要提醒「词表变了不等于归一结果变了」——
      // structured_data 是抽取时写下的快照，不重跑解析，新词条不会生效。
      rerunHint.value = { mode: direct ? 'direct' : 'local', at: new Date().toLocaleString() }
      uploadRef.value?.clearFiles()
      importFile.value = null
    } catch {
      // 拦截器已提示
    } finally {
      submitting.value = false
    }
  }

  // ---- 导入后的重跑引导 ----
  // 「本机个人词典」只影响这台浏览器上的个人用词，不影响小组基线，
  // 因此不需要（也不应该）在这里提示重跑；只有落到小组基线的那条路径才需要。
  const rerunNeeded = computed(() => rerunHint.value?.mode === 'direct')

  const rerunAll = async () => {
    if (!(await confirmBox(
      '将对本组织全部病历重跑「结构化解析 + 质控」。新词表要生效必须重跑：'
      + '解析结果存在病历的结构化字段里，不重跑就还是旧的。',
      '确认重跑解析与质控',
      { type: 'warning', confirmButtonText: '开始重跑', cancelButtonText: '取消' }
    ))) return
    rerunning.value = true
    try {
      await submitNlpBatch()
      await recomputeQc()
      ElMessage.success('已提交重跑任务，完成后到「标准化质量报告」查看新结果')
      rerunHint.value = null
    } catch {
      // 拦截器已提示
    } finally {
      rerunning.value = false
    }
  }
  </script>

<style scoped>
/* 导入后的重跑引导：说清「为什么要重跑」，否则用户会以为导入没生效而反复重传 */
.rerun-hint {
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: var(--sp-3);
  border-left: 3px solid var(--ochre);
  background: var(--ochre-surface);
  border-radius: 4px;
}
.rh-title {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.rh-desc {
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub);
}
.rh-ops {
  display: flex;
  gap: var(--sp-3);
  align-items: center;
  flex-wrap: wrap;
  margin-top: var(--sp-2);
}
.rh-link {
  font-size: var(--fs-xs);
  color: var(--link, #2b6cb0);
}
.rh-skip {
  font-size: var(--fs-xs);
  color: var(--text-sub);
}
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
  font-size: var(--fs-xs);
  font-weight: 600;
}
.step-body {
  flex: 1 1 auto;
  min-width: 0;
}
.step-t {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.step-d {
  font-size: var(--fs-xs);
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
  font-size: var(--fs-base);
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
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}
.lint-count {
  font-size: var(--fs-xs);
  color: var(--text-sub);
}
.lint-terms {
  margin-top: 4px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text);
  word-break: break-all;
}
.lint-advice {
  margin-top: 4px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub);
}
/* 「接下来会怎样」：把结果落到下一步动作上，而不是只报数字 */
.what-next {
  margin-top: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  background: var(--ink-light);
  border-radius: 4px;
  font-size: var(--fs-base);
}
/* 格式示例：给可照抄的表 */
.sample-t {
  font-size: var(--fs-base);
  font-weight: 600;
  margin-bottom: var(--sp-2);
}
.sample-tb {
  border-collapse: collapse;
  font-size: var(--fs-xs);
}
.sample-tb th,
.sample-tb td {
  border: 1px solid var(--line);
  padding: var(--sp-1) var(--sp-3);
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
  font-size: var(--fs-xs);
  line-height: 1.7;
  overflow-x: auto;
}
/* 导入完成后的出口行：与上方统计卡留出间距，按钮与说明同一行对齐 */
.import-done-ops {
  margin-top: var(--sp-4);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
</style>
