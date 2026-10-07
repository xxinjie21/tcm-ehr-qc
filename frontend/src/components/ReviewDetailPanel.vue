<template>
    <div v-loading="detailLoading" element-loading-text="正在读取复核详情…">
      <!-- ② 当前任务卡：详情区头部，右上角固定「关闭详情」出口。
           只把关闭入口放进底部吸底条，用户实测仍反馈「只有保存修改 / 复核通过」——
           进入复核后视线落在头部，出口必须在这里就出现，位置与弹窗右上角关闭同侧 -->
      <section v-if="current" class="task-card">
        <span class="task-id">{{ current.recordId }}</span>
        <span class="tag tag-score">当前评分：{{ current.score ?? '—' }} 分</span>
        <span v-for="t in issueTags" :key="t" class="tag tag-issue">{{ t }}</span>
        <!-- 截止时间与「还剩几天」只对**待复核任务**成立；从「全部病历」进入时没有任务，
             这两个字段是空的，显示「—」只会让人误以为任务已过期 -->
        <span v-if="current.taskId" class="deadline">
          复核截止：<b>{{ fmt(current.deadlineTime) }}</b>（{{ remainText }}）
        </span>
        <span v-else class="no-task-tip">
          该病历没有待复核任务 —— 可查看与修正，提交裁决需从「待复核任务」进入
        </span>
        <el-button class="close-top" :disabled="submitting" @click="closeReview">关闭详情</el-button>
      </section>

      <!-- ③ 病历原文对照（可折叠） -->
      <details v-if="record" class="raw-panel">
        <summary>
          病历原文对照 · {{ record.registrationNo || record.id }}（{{ patientSummary }}）
          <span class="raw-hint">默认收起 —— 展开后原文会把下方「人工修正」推到屏外</span>
        </summary>
        <div class="raw-bd raw-grid">
          <div
            v-for="f in FIELDS"
            :key="f.key"
            class="raw-item"
            :class="{ full: f.wide }"
          >
            <span class="k">{{ f.label }}</span>
            <span class="v">{{ fieldOf(record, f.key) || '—' }}</span>
          </div>
        </div>
      </details>

      <!-- ④ 左右对比：左原始只读 / 右人工修正 -->
      <div v-if="record" class="compare">
        <section class="panel">
          <h2 class="panel-hd hd-left">
            原始结构化数据（NLP 抽取）<span class="mini-tag">只读锁定</span>
          </h2>
          <div class="panel-bd">
            <div v-for="f in visibleCompareFields" :key="f.key" class="field-row">
              <div class="flabel">{{ f.label }}</div>
              <div class="fvalue">
                <span v-if="originalText(f.key)">{{ originalText(f.key) }}</span>
                <span v-else class="miss">缺失（抽取为空）</span>
              </div>
            </div>

            <p class="ded-hd">质控扣分明细（合计 -{{ totalDeduct }} 分）</p>
            <div v-if="precheck && precheck.structuredMissing" class="ded-item structured-miss">
              <span>尚未结构化解析（或解析失败），本次按"原始列有记录"从轻计分；建议先执行结构化解析</span>
            </div>
            <div v-for="(d, i) in deductions" :key="i" class="ded-item">
              <span>{{ d.type }}：{{ d.reason }}</span>
              <span class="pts">-{{ d.points }}</span>
            </div>
            <div v-if="!deductions.length && precheck" class="ok">无扣分项</div>

            <template v-if="aiLines.length">
              <p class="ded-hd">AI 预检建议</p>
              <div class="ai-box">
                <p v-for="(l, i) in aiLines" :key="i">{{ l }}</p>
                <span class="ai-src">{{ aiSource === 'rule' ? '规则预检（LLM 未启用）' : 'AI 建议' }}</span>
              </div>
            </template>
          </div>
        </section>

        <section class="panel">
          <h2 class="panel-hd hd-right">人工修正</h2>
          <div class="panel-bd">
            <div
              v-for="f in visibleCompareFields"
              :key="f.key"
              class="field-row"
              :class="{ fixed: isFixed(f.key) }"
            >
              <div class="flabel">{{ f.label }}</div>
              <div class="fvalue term-wrap">
                <TermInput
                  v-if="f.termType"
                  v-model="editValues[f.key]"
                  :type="f.termType"
                  :placeholder="originalText(f.key) || '原值为空，请输入或选择'"
                />
                <el-input
                  v-else
                  v-model="editValues[f.key]"
                  size="small"
                  :placeholder="originalText(f.key) || '原值为空，请输入'"
                  clearable
                />
              </div>
            </div>
            <div class="term-note">↑ 带下拉的字段可直接搜索国标术语；多个词用「、」分隔</div>

            <div class="field-row">
              <div class="flabel">复核备注</div>
              <div class="fvalue">
                <el-input v-model="remark" type="textarea" :rows="3" placeholder="留痕用，例如：已对照原文补充脉象" />
              </div>
            </div>

            <div class="preview">
              复核后预估评分：<b>{{ estimate.score }}</b> 分　预计分级：<span v-if="estimate.grade" :class="['tag', gradeClass(estimate.grade)]">{{ estimate.grade }}</span><span v-else class="tag is-mid">{{ THRESHOLD_PLACEHOLDER }}</span><span v-if="estimate.hint" class="tip">（{{ estimate.hint }}）</span>
              <span class="est-note">（按已补齐的核心字段扣分回算，最终以服务端重算为准）</span>
            </div>
          </div>
        </section>
      </div>

      <!-- ⑤ 提交反馈 -->
      <section v-if="result" class="result-bar">
        <span class="rk">提交反馈：</span>
        <span class="rv">
          状态：<b>{{ result.status }}</b>　·　重新评分：<b>{{ result.score }} 分</b>　·　
          {{ result.errors && result.errors.length ? '剩余问题：' : '剩余问题：无' }}
          <template v-if="result.errors && result.errors.length">
            <span v-for="(e, i) in result.errors" :key="i">{{ i ? '；' : '' }}{{ e.type }}：{{ e.msg }}</span>
          </template>
        </span>
        <span class="deadline">{{ submittedAt }}</span>
      </section>

      <!-- ⑥ 底部操作 -->
      <section v-if="record" class="footer-bar">
        <span class="tip">
          点击「复核通过」后系统会自动重新执行质控评分与诊疗逻辑校验；不填修正内容表示仅裁定不修改数据。
        </span>
        <div class="btns">
          <!-- 补关闭入口：此前只有保存 / 通过两个出口，想只读退出无处可点。
               在任务卡右上角再加一处，底部这处保留 —— 读到最底也有出口 -->
          <el-button :disabled="submitting" @click="closeReview">关闭详情</el-button>
          <el-tooltip
            :disabled="!!current?.taskId"
            content="该病历没有待复核任务；后端要求任务存在才能提交裁决，请从「待复核任务」页签进入"
            placement="top"
          >
            <span>
              <el-button :loading="submitting" :disabled="!current?.taskId" @click="submit(false)">保存修改</el-button>
              <el-button type="primary" :loading="submitting" :disabled="!current?.taskId" @click="submit(true)">
                复核通过
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </section>
    </div>
</template>

<script setup>
// 复核详情面板（②~⑥）：当前任务卡 / 病历原文对照 / 左右对比 / 提交反馈 / 底部操作。
// 由 Review.vue 页内抽出；「进入复核」所需的数据加载、修正表单与提交裁决逻辑都在此。
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import TermInput from '@/components/TermInput.vue'
import { getRawRecord, submitReview } from '@/api/records'
import { runAiAsync } from '@/api/ai'
import { qcScore, getQcRules } from '@/api/qc'
import { fmtDateTime, fieldOf } from '@/utils/format'
import { fieldsWithWide } from '@/utils/recordFields'
import { gradeOf, gradeHint, gradeClass, THRESHOLD_PLACEHOLDER } from '@/utils/grade'

const emit = defineEmits(['submitted', 'opened', 'closed'])

// 字段定义收敛到 @/utils/recordFields（P3.5）；复核列表的整行集合
const FIELDS = fieldsWithWide([
  'westernDiagnosis', 'tcmDiagnosis', 'chiefComplaint', 'selfReport', 'presentIllness',
  'inspection', 'pulse', 'tongue', 'physicalExam', 'pattern', 'prescription', 'followUp'
])

// 扣分明细里「核心字段缺失」的 item 名 → structuredData 键，用于预估评分回算（核心 6 要素，取自规则集目录）
const FIELD_BY_ITEM = {
  疾病: 'diseases',
  症状: 'symptoms',
  证候: 'patternList',
  舌象: 'tongueList',
  脉象: 'pulseList',
  中药: 'herbs'
}

/**
 * 核心要素的 structuredData 键集合。
 *
 * <p>对照区据此区分两类字段：<b>核心要素</b>（参与「核心字段缺失」扣分）无论空不空都要显示，
 * 因为「缺失（抽取为空）」正是提示复核员去补；<b>非核心</b>（治法 / 方剂 / 病因）只做展示，
 * 空的时候渲染出来只会让人以为病历缺项，而那是补不了的假象 —— 例如本数据集里
 * {@code prescription} 列是纯中药清单、没有方剂名，方剂永远抽不出来。</p>
 */
const CORE_KEYS = new Set(Object.values(FIELD_BY_ITEM))

/**
 * 对照区的字段（功能设计附录A 的 9 类实体）。
 * termType 指向词典类型；治法/病因在现有词典里没有对应类别，故用普通输入框。
 */
const COMPARE_FIELDS = [
  { key: 'patternList', label: '证候', termType: 'pattern' },
  { key: 'treatmentList', label: '治法', termType: '' },
  { key: 'formulaList', label: '方剂', termType: 'formula' },
  { key: 'tongueList', label: '舌象', termType: 'symptom' },
  { key: 'pulseList', label: '脉象', termType: 'symptom' },
  { key: 'herbs', label: '中药', termType: 'herb' },
  { key: 'diseases', label: '疾病', termType: 'disease' },
  { key: 'symptoms', label: '症状', termType: 'symptom' },
  { key: 'causeList', label: '病因', termType: '' }
]

// 时间格式化：去掉 T、截到分钟；空值返回「—」，避免列表里出现 Invalid Date
const fmt = (t) => (t ? fmtDateTime(t,'minute') : '—')

// ===== ②~⑥ 同页复核 =====
const detailLoading = ref(false)
const current = ref(null)
const record = ref(null)
const precheck = ref(null)
const aiAnswer = ref('')
const aiSource = ref('')
const remark = ref('')
const submitting = ref(false)
const result = ref(null)
const submittedAt = ref('')

const editValues = reactive({})
const originalMap = ref({})

// AI 预检建议按行拆开渲染；空内容时得到空数组，模板据此整块隐藏
const aiLines = computed(() => (aiAnswer.value || '').split('\n').filter((l) => l.trim() !== ''))
// 质控扣分明细：precheck 未返回时兜底为空数组，模板用长度判断「无扣分项」
const deductions = computed(() => precheck.value?.deductions || [])
// 扣分合计（显示为负数），用于明细标题；明细缺失时累加为 0
const totalDeduct = computed(() => deductions.value.reduce((s, d) => s + (d.points || 0), 0))

// 把任务的问题类型字符串按中英文分号 / 逗号拆成标签数组，顺带去掉空白项
const issueTags = computed(() =>
  String(current.value?.issueType || '')
    .split(/[；;，,]/)
    .map((s) => s.trim())
    .filter(Boolean)
)

// 任务卡与原文区的一句话患者摘要（性别 · 年龄 · 科室 · 就诊日）；无病历时返回空串
const patientSummary = computed(() => {
  const r = record.value
  if (!r) return ''
  return [
    r.gender,
    r.age ? `${r.age} 岁` : '',
    r.department,
    r.visitTime ? `${fmtDateTime(r.visitTime,'date','')} 就诊` : ''
  ]
    .filter(Boolean)
    .join('　')
})

// 距截止时间的自然语言剩余量：按天向上取整，已过期显示超时天数，无截止时间返回空串
const remainText = computed(() => {
  const t = current.value?.deadlineTime
  if (!t) return ''
  const days = Math.ceil((new Date(t).getTime() - Date.now()) / 86400000)
  if (days < 0) return `已超时 ${-days} 天`
  if (days === 0) return '今天截止'
  return `剩余 ${days} 天`
})

// 归一实体的展示词：优先 content（多数实体），草药等只有 name，两者都没有则返回空串
const textOf = (entry) => entry?.content || entry?.name || ''
// 把某字段的原始实体列表拼成顿号分隔的只读文本，空项过滤掉
// 原文按字段预先拼好：originalText 在模板里每行调 2 次，
// 原来每次都重新 map/filter/join 出一串，逐行重复做同一件事。
const originalTextMap = computed(() => {
  const out = new Map()
  for (const [key, values] of Object.entries(originalMap.value || {})) {
    out.set(key, (values || []).map(textOf).filter(Boolean).join('、'))
  }
  return out
})
const originalText = (key) => originalTextMap.value.get(key) || ''

/**
 * 对照区实际渲染的字段。
 *
 * <p>核心要素一律显示（空就是「缺失（抽取为空）」，供复核员去补）；
 * 非核心要素只在<b>原文有值或复核员改过</b>时显示 —— 否则会为每条病历都渲染一行
 * 「缺失（抽取为空）」，而那是补不了的假象（见 {@link CORE_KEYS} 的说明）。</p>
 */
const visibleCompareFields = computed(() =>
  COMPARE_FIELDS.filter(
    (f) => CORE_KEYS.has(f.key) || !!originalText(f.key) || !!editValues[f.key]
  )
)
// 判断人工修正框是否与原值不同，用于整行高亮「已改动」
const isFixed = (key) => String(editValues[key] || '') !== originalText(key)

// structuredData 可能是对象也可能是一段 JSON 字符串，统一解析成对象；空值或解析失败都退回空对象
const safeParse = (sd) => {
  // 1. 空值直接退回空对象
  if (!sd) return {}
  // 2. 已是对象则原样返回
  if (typeof sd === 'object') return sd
  try {
    // 3. 字符串按 JSON 解析
    return JSON.parse(sd)
  } catch {
    // 4. 解析失败也退回空对象，不让调用方拿到异常
    return {}
  }
}

// 用病历的结构化数据回填修正框与原值快照：两者必须同时刷新，否则「是否已改动」的判断会错位
const fillEditors = (sd) => {
  // 1. 先把 structuredData 统一解析成对象
  const data = safeParse(sd)
  const map = {}
  // 2. 逐字段回填修正框，并同步记下原值快照（两者必须一起刷新）
  COMPARE_FIELDS.forEach((f) => {
    const list = Array.isArray(data[f.key]) ? data[f.key] : []
    map[f.key] = list
    editValues[f.key] = list.map(textOf).filter(Boolean).join('、')
  })
  // 3. 快照整体落库，供「是否已改动」比对
  originalMap.value = map
}

// 字段级表单 → structuredData；原存在的术语沿用原文溯源 sourceText
const buildCorrected = () => {
  // 1. 结果对象从空开始，逐字段组装
  const out = {}
  // 2. 逐个对照字段：先拆词，再还原成实体数组
  COMPARE_FIELDS.forEach((f) => {
    // 3. 输入框文本按顿号 / 逗号等拆成词，去掉空项
    const words = String(editValues[f.key] || '')
      .split(/[、,，;；|]/)
      .map((s) => s.trim())
      .filter(Boolean)
    // 4. 命中原文的术语沿用其溯源信息，新词补一份最小结构
    out[f.key] = words.map((w) => {
      const hit = (originalMap.value[f.key] || []).find((e) => textOf(e) === w)
      if (hit) return hit
      return f.key === 'herbs'
        ? { name: w, sourceText: '', standardTerm: '' }
        : { content: w, sourceText: '', standardTerm: '' }
    })
  })
  // 5. 返回组装好的 structuredData
  return out
}

/**
 * 分级阈值：来自后端规则（管理员可在「规则配置」里改），不在前端写死。
 *
 * 初值只是「请求还没回来 / 失败」时的兜底，取值与后端 QcRuleSet 的出厂默认一致 ——
 * 原来的写法是把 90 / 60 直接写在判级那行，而注释还写着「不复制规则表」：
 * 管理员把合格线改成 85 后，这一栏的预估分级就与服务端重算结果对不上（审查报告 G8）。
 */
// 初值为 null：请求没回来 / 失败时<b>不给分级结论</b>，界面显示「—」。
// 原来兜底成 90 / 60（后端出厂默认值的拷贝），于是规则还没到位的那几秒里
// 界面就按 90 判级 —— 管理员把合格线改成 85 后，这段窗口里的结论是错的。
const thresholds = ref(null)

/**
 * 复核后预估评分（原型「复核后预估评分」区）。
 * 只做「已补齐的核心字段把对应扣分加回」这一条，且明确标注以服务端重算为准 ——
 * 前端不复制规则表，避免与服务端判定口径漂移。
 */
const estimate = computed(() => {
  const base = precheck.value?.score ?? current.value?.score ?? 0
  let gain = 0
  deductions.value.forEach((d) => {
    if (d.type !== '核心字段缺失') return
    const key = FIELD_BY_ITEM[d.item]
    if (key && String(editValues[key] || '').trim()) gain += d.points || 0
  })
  const score = Math.max(0, Math.min(100, base + gain))
  // 判定与名称都取 utils/grade.js 的唯一副本：原来这里写死
  // '合格 → 进入数据清洗'，把处置动作混进分级名，于是同一条病历在质控页显示
  // 「合格」、在复核页显示「合格 → 进入数据清洗」，两页结论字面不一致。
  const grade = gradeOf(score, thresholds.value)
  return { score, grade, hint: gradeHint(grade) }
})

// ---- 进入复核 / 退出 / 提交 ----
const openReview = async (row) => {
  // 1. 先切到该任务并进入加载态
  current.value = row
  emit('opened', row)
  detailLoading.value = true
  // 2. 清空上一次的详情与表单，避免残留上一条任务的数据
  record.value = null
  precheck.value = null
  aiAnswer.value = ''
  result.value = null
  remark.value = ''
  Object.keys(editValues).forEach((k) => delete editValues[k])
  originalMap.value = {}
  try {
    // 3. 并行拉病历原文与质控评分（互不依赖，串行会白等）
    const [raw, sr] = await Promise.all([getRawRecord(row.recordId), qcScore({ recordId: row.recordId })])
    // 4. 落原文与扣分明细，并回填修正框
    record.value = raw.data
    precheck.value = sr.data
    // 列表不再内联 structuredData（P5.3），一律以病历详情为准
    fillEditors(raw.data?.structuredData)
    try {
      // 5. 取 AI 预检建议，失败不影响复核（仅提示以扣分明细为准）。
      //    走异步路（15.1）：提交拿任务号再轮询，生成期间不占请求线程；
      //    返回的是 reply 本体（不是 axios 响应）。
      const ai = await runAiAsync('review', { recordId: row.recordId })
      aiAnswer.value = ai?.answer || ''
      aiSource.value = ai?.source || ''
    } catch {
      aiAnswer.value = '（AI 预检不可用，请以左侧扣分明细为准）'
    }
    // 展开后滚动到任务卡，避免用户以为「点了没反应」
    // 6. 等渲染完成再滚动到任务卡
    await Promise.resolve()
    document.querySelector('.task-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch {
    // 拦截器已提示
  } finally {
    // 7. 无论成败都收掉加载态
    detailLoading.value = false
  }
}

const exitDetail = () => {
  current.value = null
  record.value = null
  precheck.value = null
  aiAnswer.value = ''
  aiSource.value = ''
  remark.value = ''
  Object.keys(editValues).forEach((k) => delete editValues[k])
  originalMap.value = {}
}

const closeReview = () => {
  exitDetail()
  result.value = null
  emit('closed')
}

const submit = async (withCorrection) => {
  // 1. 进入提交态并清掉上一次的提交反馈
  submitting.value = true
  result.value = null
  try {
    // 2. 组装请求体：仅「保存修改」才带人工修正数据，备注非空才带
    const body = {}
    if (withCorrection) body.correctedData = buildCorrected()
    if (remark.value.trim()) body.comment = remark.value.trim()
    // 2.1 携带读时指纹：服务端据此拒绝「有人在你读取后改过这条病历」的提交（批次 25.16）
    if (record.value?.fingerprint) body.fingerprint = record.value.fingerprint
    // 3. 提交复核并回填反馈（状态 / 重评分数 / 提交时间）
    const res = await submitReview(current.value.recordId, body)
    result.value = res.data
    submittedAt.value = new Date().toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
    ElMessage.success(`复核完成：${res.data.status}`)
    // 复核通过即退出详情：任务已办结，无需用户再手动关一次
    // 4. 仅「复核通过」才退出详情，「保存修改」留在原地继续改
    if (withCorrection) exitDetail()
    // 5. 刷新列表，把该任务移出待复核
    emit('submitted')
  } catch {
    // 拦截器已提示
  } finally {
    // 6. 无论成败都收掉提交态
    submitting.value = false
  }
}

const loadThresholds = async () => {
  try {
    // 1. 取后端分级规则
    const res = await getQcRules()
    // 2. 定位到分级阈值
    const t = res.data?.rules?.thresholds
    // 3. 取到则覆盖兜底阈值，取不到沿用初值
    if (t) thresholds.value = { qualified: t.qualified, invalid: t.invalid }
  } catch {
    // 拦截器已提示；沿用兜底阈值，不阻塞复核
  }
}

onMounted(() => { loadThresholds() })

defineExpose({ open: openReview, close: closeReview })
</script>

<style scoped>
.no-task-tip {
  color: var(--text-sub-strong);
  font-size: var(--fs-xs);
}

/* ===== ② 当前任务卡 ===== */
.task-card {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
  background: var(--surface);
  border: 1px solid var(--line);
  border-left: 4px solid var(--ochre);
  border-radius: 6px;
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: 14px;
}
.task-id {
  font-size: var(--fs-title);
  font-weight: bold;
  color: var(--ink);
}
.tag {
  display: inline-block;
  padding: var(--sp-1) var(--sp-2);
  font-size: var(--fs-xs);
  border-radius: 2px;
  line-height: 20px;
}
.tag-score {
  color: var(--ochre-text);
  background: var(--ochre-light);
  border: 1px solid #e0cdb0;
}
.tag-issue {
  color: var(--danger);
  background: var(--danger-surface);
  border: 1px solid #e3c3bb;
}
.deadline {
  margin-left: auto;
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
}
.deadline b {
  color: var(--danger);
}
/* 详情区右上角出口：与病历详情弹窗右上角关闭同侧，
   进入复核即可见，不必先滚到页面底部 */
.task-card .close-top {
  flex-shrink: 0;
}

/* ===== ③ 原文折叠 ===== */
.raw-panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  margin-bottom: 14px;
}
.raw-panel summary {
  padding: var(--sp-3) var(--sp-4);
  font-size: var(--fs-base);
  font-weight: bold;
  color: var(--ink);
  cursor: pointer;
  list-style: none;
}
.raw-panel summary::-webkit-details-marker {
  display: none;
}
.raw-panel summary::before {
  content: '▸ ';
  color: var(--ink-mid);
}
.raw-panel[open] summary::before {
  content: '▾ ';
}
.raw-panel summary:hover {
  background: var(--surface-sub);
}
/* M25：原文折叠默认收起后，把「为什么收起」写在标题行，避免被当成排版缺失 */
.raw-hint {
  margin-left: var(--sp-2);
  font-size: var(--fs-xs);
  font-weight: normal;
  color: var(--text-sub-strong);
}
.raw-bd {
  padding: var(--sp-1) 20px var(--sp-4);
  border-top: 1px solid var(--el-border-color-lighter);
}
.raw-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 28px;
}
.raw-item {
  display: flex;
  padding: var(--sp-2) 0;
  border-bottom: 1px dashed var(--line-soft);
  font-size: var(--fs-base);
}
.raw-item.full {
  grid-column: 1 / -1;
}
.raw-item .k {
  width: 76px;
  flex-shrink: 0;
  color: var(--text-sub-strong);
}
.raw-item .v {
  flex: 1;
  color: var(--text);
  word-break: break-all;
}

/* ===== ④ 左右对比 ===== */
.compare {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-bottom: 14px;
}
.compare .panel {
  margin-bottom: 0;
  /* 补齐面板外框：本页自写 .panel / .panel-hd / .panel-bd，
     原先漏了 .panel 的外框，左右对比区看起来没有边界，与病历数据页不一致 */
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow: hidden;
}
.panel-hd {
  margin: 0;
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: var(--fs-title);
  font-weight: bold;
  display: flex;
  align-items: center;
  gap: 10px;
}
/* 标题左侧的竖条装饰：与 PanelCard.vue 同一视觉语言（本页自写面板时漏了它） */
.panel-hd::before {
  content: '';
  width: 3px;
  height: 14px;
  background: var(--ink-mid);
}
.panel-hd.hd-left {
  border-bottom-color: #eee4d3;
  background: #faf6ee;
  color: var(--ochre-text);
}
.panel-hd.hd-right {
  border-bottom-color: var(--el-color-primary-light-8);
  background: var(--el-color-primary-light-9);
  color: var(--ink-mid);
}
.mini-tag {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  border: 1px solid var(--line);
  border-radius: 2px;
  padding: 0 var(--sp-2);
  line-height: 18px;
}
.panel-bd {
  padding: var(--sp-2) var(--sp-4) var(--sp-4);
}
.field-row {
  display: flex;
  align-items: flex-start;
  min-height: 40px;
  padding: var(--sp-2) 0;
  border-bottom: 1px dashed var(--line-soft);
}
.field-row:last-of-type {
  border-bottom: none;
}
/* 修正过的字段整行高亮，与原型一致 */
.field-row.fixed {
  background: var(--el-color-primary-light-9);
  border-radius: 2px;
  padding-left: var(--sp-2);
  padding-right: var(--sp-2);
  margin: 0 -8px;
}
.flabel {
  width: 78px;
  flex-shrink: 0;
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
  padding-top: 6px;
}
.fvalue {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-base);
}
.miss {
  color: var(--danger);
  background: var(--danger-surface);
  padding: var(--sp-1) var(--sp-3);
  border-radius: 2px;
  font-size: var(--fs-xs);
  display: inline-block;
}
.term-note {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin: 6px 0 var(--sp-2) 78px;
}
.ded-hd {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin: var(--sp-3) 0 var(--sp-2);
}
.ded-item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  background: var(--ochre-light);
  border-radius: 2px;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: 6px;
  font-size: var(--fs-xs);
}
.ded-item .pts {
  color: var(--danger);
  font-weight: bold;
  flex-shrink: 0;
}
.structured-miss {
  background: var(--ink-light);
  color: var(--text-sub-strong);
}
.ok {
  padding: var(--sp-2) 0;
  color: var(--ink-mid);
  font-size: var(--fs-xs);
}
.ai-box {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: var(--sp-3) var(--sp-3);
  font-size: var(--fs-xs);
  line-height: 1.8;
  color: var(--ink);
}
.ai-box p {
  margin: 0 0 var(--sp-1);
}
.ai-src {
  display: inline-block;
  margin-top: var(--sp-1);
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}
.preview {
  margin-top: var(--sp-3);
  background: var(--ink-light);
  border: 1px solid #cddcd2;
  border-radius: 2px;
  padding: var(--sp-2) var(--sp-4);
  font-size: var(--fs-base);
  color: var(--ink-mid);
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.preview b {
  font-size: var(--fs-title);
}
.tag-ok {
  color: var(--ink-mid);
  background: var(--surface);
  border: 1px solid var(--ink-mid);
  border-radius: 2px;
  padding: var(--sp-1) var(--sp-2);
  font-size: var(--fs-xs);
}
.est-note {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}

/* ===== ⑤ 提交反馈条 ===== */
.result-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-left: 4px solid var(--ink-mid);
  border-radius: 6px;
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.result-bar .rk {
  font-weight: bold;
  color: var(--ink-mid);
}
.result-bar .rv {
  color: var(--text-sub-strong);
  font-size: var(--fs-base);
}
.result-bar b {
  color: var(--ink-mid);
}

/* ===== ⑥ 底部操作 ===== */
/* 页面根为 flex 列 + min-height：内容不足一屏时 margin-top: auto 把底栏顶到底部，
   不再浮在页面中部（data-v 作用域元素浮中部问题）；内容超长时 sticky 仍吸底可见。
   注意 min-height 必须减掉同层前面的面包屑与隐藏 h1（21 + 12 + 1 = 34px）——
   main 的 content box 高度里已经含了它们，不减就会恒多撑 34px，表现是
   「还没进入复核」就冒出一条页面滚动条，且底栏被推进 main 的 84px 底部留白里。
   实测 1440×900 与 1366×768 都是溢出 33px（未进入复核时） */

.footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: var(--sp-3) 20px;
  flex-wrap: wrap;
  /* 吸底：对照区很长，关闭 / 提交入口始终可见，不必滚到底 */
  position: sticky;
  bottom: 0;
  margin-top: auto;
  z-index: 3;
  box-shadow: 0 -2px 8px rgba(47, 70, 57, 0.06);
}
.footer-bar .tip {
  flex: 1;
  min-width: 240px;
}
.btns {
  display: flex;
  gap: 10px;
}

@media (max-width: 1200px) {
  .compare,
  .raw-grid {
    grid-template-columns: 1fr;
  }
}
</style>
