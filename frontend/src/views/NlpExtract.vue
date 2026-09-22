<template>
  <div>
    <!-- ① 选择病历：看板式列表，常驻可见（第七轮确认：载入后仍需看到列表，方便随时切换病历） -->
    <PanelCard title="选择病历">
      <RangeFilter v-model="query" />
      <div class="actions">
        <el-button type="primary" :loading="listLoading" @click="search(1)">查 询</el-button>
        <el-button :disabled="listLoading" @click="resetQuery">重置</el-button>
        <span class="tip">共 {{ total }} 条，点击行即载入该病历原文</span>
      </div>

      <!-- max-height 必须 ≥「表头 + 每页 10 行」= 32 + 32×10 = 352px，否则默认每页 10 条会多出
           一条内部滚动条。实测：写 320 时可视区只有 288px，差 32px 就冒滚动条；写 360 与人工复核、
           质控校验同口径，10 行正好铺满且不滚（>10 条时仍保留滚动，属预期）。
           改小这个值等于把滚动条加回来 -->
      <el-table
        v-loading="listLoading"
        :data="rows"
        border
        size="small"
        max-height="360"
        highlight-current-row
        :row-class-name="rowClass"
        style="margin-top: 12px"
        @row-click="loadRecord"
      >
        <!-- 列口径与「病历数据」的病历列表一致（病历ID 为 UUID，36 字符，需给足宽度）。
             原「登记号」列已删：/records/search 返回的 SearchVO.Item 里没有该字段，恒为空 -->
        <el-table-column prop="id" label="病历ID" width="320" show-overflow-tooltip />
        <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
        <el-table-column prop="grade" label="分级" width="90" />
        <!-- 接诊时间（第八轮）：常态只到日，悬停给秒级原值。
             只到日是有意的 —— 演示数据的时间分量是脱敏噪声（57% 落在非门诊时段，
             会出现凌晨 2 点接诊），常态展示等于把噪声摆在列表上；hover 保留完整精度用于核对 -->
        <el-table-column label="接诊时间" width="110">
          <template #default="{ row }">
            <el-tooltip :content="fmtDateTime(row.visitTime, 'second')" placement="top">
              <span>{{ fmtDateTime(row.visitTime, 'date') }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <!-- 年龄/性别（第八轮）：单块自包含，需回滚时整块删掉即可 ——
             后端两字段是追加、向后兼容，回滚不需要动后端 -->
        <el-table-column label="年龄/性别" width="110">
          <template #default="{ row }">
            <span>{{ [row.age ? row.age + '岁' : '', row.gender].filter(Boolean).join(' / ') || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="loadRecord(row)">载入</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="无符合条件的病历" :image-size="80" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="search"
        @size-change="handleSizeChange"
      />
    </PanelCard>

    <el-tabs v-model="activeTab" class="nlp-tabs">
      <!-- ============ 单条解析 ============ -->
      <el-tab-pane label="单条解析" name="single">
        <PanelCard title="NLP 结构化解析">
          <div class="loaded-bar">
            <template v-if="recordId">
              已载入病历：<b>{{ loadedLabel }}</b>
              <span v-if="loadedMeta" class="tip">{{ loadedMeta }}</span>
              <span class="tip">关闭详情将清空当前病历与抽取结果</span>
              <!-- 与人工复核一致的「关闭详情」出口（第七轮）：清空当前病历，列表仍常驻可见 -->
              <el-button
                link
                type="primary"
                class="picker-toggle"
                :disabled="extracting"
                @click="closeDetail"
              >
                关闭详情
              </el-button>
            </template>
            <span v-else class="tip">请在上方「选择病历」列表中点选一份病历</span>
          </div>

          <!-- 无产出时把原因讲清楚（UX-63 第七轮）：术语归一依赖上游实体，
               没有实体就没有可归的内容，不能让用户以为「归一没执行」。
               第八轮：这段是给用户看的，一律说人话 —— 配置项、端口、字段名等技术细节
               已挪到后端 slf4j 日志（PythonNlpClient 启动时 warn 一次），此处不再出现。 -->
          <div v-if="emptyReason" class="nlp-off">
            <template v-if="emptyReason === 'off'">
              <b>本次没有抽取到病历要素</b>（疾病、证候、症状、中药等）。自动抽取功能当前未开启，
              或抽取服务暂时不可用，所以没有产出内容。
              这不是「术语归一」没执行 —— <b>归一一直在跑</b>，只是没有可归的要素。
              需要开启请联系系统管理员；也可以先用下方「术语归一试算」直接查词典。
            </template>
            <template v-else>
              抽取已完成，但没有识别出可归一的要素（疾病、证候、症状、中药等）。
              请确认原文是否写了主诉、四诊、辨证结论、用药等内容。
            </template>
          </div>

          <div class="split">
            <!-- 原文：按字段模块化，可单独修改（UX-62） -->
            <div class="pane">
              <div class="pane-hd">原文（按字段模块化，可单独修改）</div>
              <!-- 分区常显 + 多列栅格（UX-70）：原先 4 组折叠、默认只开 2 组，
                   用户仍要逐组展开、整页依旧要滚动；与病历数据页 UX-51 同一口径。
                   第七轮再压控件高度（标签左置 + small + 文本域单行起步，见 theme.css） -->
              <el-form
                class="compact-form field-form"
                label-width="68px"
                @submit.prevent
              >
                <div v-for="g in FIELD_GROUPS" :key="g.title" class="form-group">
                  <div class="group-hd">{{ g.title }}</div>
                  <div class="form-grid">
                    <el-form-item v-for="f in fieldsOf(g)" :key="f.key" :label="f.label" :class="{ wide: f.wide }">
                      <el-input
                        v-model="fields[f.key]"
                        :type="f.multi ? 'textarea' : 'text'"
                        :rows="1"
                        :autosize="f.multi ? { minRows: 1, maxRows: 2 } : false"
                        clearable
                      />
                    </el-form-item>
                  </div>
                </div>
              </el-form>

              <!-- 整段文本只读对照：抽取请求就是这段拼接结果（UX-62） -->
              <details class="composed-panel">
                <summary class="composed-hd">整段文本（只读对照）</summary>
                <div class="composed">{{ composedText || '（当前无内容）' }}</div>
              </details>

              <div class="actions pane-actions">
                <el-button type="primary" :loading="extracting" :disabled="!composedText" @click="runExtract">
                  执行抽取
                </el-button>
                <el-button :disabled="!canSave" @click="save">保存到病历</el-button>
                <!-- 禁用时说明原因，而不是让用户猜（UX-01） -->
                <span v-if="!recordId" class="tip">先在上方列表点选一份病历才能保存</span>
                <span v-else-if="!result" class="tip">先执行抽取才能保存</span>
              </div>
            </div>

            <!-- 抽取结果：术语已归一，展示「原文 → 标准词」对照（UX-63） -->
            <div class="pane">
              <div class="pane-hd">
                抽取结果
                <span v-if="result" class="src-note" :class="{ warn: !result.modelAvailable }">
                  {{ result.modelAvailable ? '模型抽取 + 规则补充' : '自动抽取未开启（本次无要素）' }}
                </span>
              </div>
              <!-- 归一状态行（UX-63 第七轮）：明说「归一跑没跑、跑出了什么」 -->
              <div v-if="result" class="norm-note" :class="{ warn: !!emptyReason }">
                <template v-if="emptyReason">
                  本次没有抽取到要素，<b>术语归一没有可归的内容</b>（原因见上方提示）。
                </template>
                <template v-else>
                  已按词典归一：实体显示为<b>标准术语</b>，灰色小字为归一前原文，鼠标悬停可看命中层级。
                </template>
              </div>
              <StructuredDataCard v-if="result" :data="result" />
              <el-empty v-else description="尚未抽取" :image-size="80" />

              <!-- 术语归一试算（UX-63 第七轮）：词典直查，不依赖 Python NLP 服务，
                   让用户在本页就能亲自跑一次归一、看到「原文 → 标准词」 -->
              <div class="norm-tool">
                <div class="nt-hd">术语归一试算（直查词典，不依赖 NLP 服务）</div>
                <div class="nt-row">
                  <!-- 这一行只有 placeholder、没有视觉标签，补 aria-label 让屏幕阅读器
                       与 Chrome 的「No label associated with a form field」检查能认出它们
                       （placeholder 不算无障碍名称） -->
                  <el-select v-model="normType" size="small" aria-label="词典类型" style="width: 116px">
                    <el-option v-for="t in NORM_TYPES" :key="t.value" :label="t.label" :value="t.value" />
                  </el-select>
                  <el-input
                    v-model="normTerm"
                    size="small"
                    aria-label="待归一的术语"
                    placeholder="如：脾肾阳虚"
                    clearable
                    @keyup.enter="runNormalize"
                  />
                  <el-button
                    size="small"
                    :loading="normLoading"
                    :disabled="!normTerm.trim()"
                    @click="runNormalize"
                  >
                    归 一
                  </el-button>
                </div>
                <div v-if="normResult" class="nt-result">
                  <span class="nt-in">{{ normResult.term }}</span>
                  <span class="nt-arrow">→</span>
                  <span class="nt-out" :class="{ miss: !normResult.source }">{{ normResult.standardTerm }}</span>
                  <span class="nt-src">
                    {{ normResult.source ? `命中词典 · ${normResult.source}` : '未命中词典，返回原词' }}
                  </span>
                </div>
                <div v-else class="nt-hint">
                  命中示例：证候「脾肾阳虚」、中药「炙甘草」；词典未收录的口语词（如「嗓子疼」）会原样返回并标为未命中。
                </div>
              </div>
            </div>
          </div>
        </PanelCard>
      </el-tab-pane>

      <!-- ============ 批量解析（页面内嵌，不用弹窗） ============ -->
      <el-tab-pane label="批量解析" name="batch" lazy>
        <PanelCard title="批量结构化解析">
          <div class="tip">
            先按条件选定病历范围，再逐条执行抽取并保存到病历。条数较多时耗时较长，
            <b>请勿关闭页面</b>；需要中断可点「取消」，已处理的不回滚。
          </div>

          <div class="batch-filter">
            <div class="bf-title">筛选范围</div>
            <RangeFilter v-model="batchFilters" />
          </div>

          <div class="batch-row">
            <span>处理条数上限</span>
            <el-input-number v-model="batchLimit" :min="1" :max="500" size="small" :disabled="batchRunning" />
            <span class="tip">（最多处理符合条件的前 N 条）</span>
          </div>

          <div class="actions">
            <el-button v-if="!batchRunning" type="primary" @click="runBatch">开始批量解析</el-button>
            <el-button v-else @click="batchCancelled = true">取消</el-button>
          </div>

          <div v-if="batchRunning || batchProgress.done" class="batch-progress">
            <div class="bp-hd">
              正在处理第 {{ Math.min(batchProgress.done + 1, batchProgress.total) }}/{{ batchProgress.total }} 条：
              <b>{{ batchProgress.current || '准备中…' }}</b>
            </div>
            <el-progress
              :percentage="batchProgress.total ? Math.round((batchProgress.done / batchProgress.total) * 100) : 0"
              :stroke-width="10"
            />
            <div class="bp-sub">成功 {{ batchProgress.success }} 条，失败 {{ batchProgress.failed }} 条</div>
          </div>

          <!-- 失败清单（UX-61）：不只是一句「失败 N 条」，要能查到是哪几条 -->
          <div v-if="batchFailures.length" class="batch-failures">
            <div class="bf-hd">失败清单（{{ batchFailures.length }} 条）</div>
            <el-table :data="batchFailures" border size="small" max-height="240">
              <el-table-column prop="label" label="病历" width="200" show-overflow-tooltip />
              <el-table-column prop="reason" label="原因" show-overflow-tooltip />
            </el-table>
          </div>
        </PanelCard>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StructuredDataCard from '@/components/StructuredDataCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import { extractNlp } from '@/api/nlp'
import { normalize } from '@/api/governance'
import { searchRecords, getRawRecord, updateRecord } from '@/api/records'
import { fmtDateTime } from '@/utils/format'
import { apiErrorMessage } from '@/utils/request'

const activeTab = ref('single')

// ===== 病历列表（UX-61）=====
const query = reactive({ department: '', dateRange: null, pattern: '', grade: '' })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const listLoading = ref(false)

const search = async (p) => {
  if (typeof p === 'number') page.value = p
  listLoading.value = true
  try {
    const res = await searchRecords({ ...query, page: page.value, pageSize: pageSize.value })
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    // 拦截器已提示
  } finally {
    listLoading.value = false
  }
}

const handleSizeChange = () => {
  page.value = 1
  search()
}

const resetQuery = () => {
  query.department = ''
  query.dateRange = null
  query.pattern = ''
  query.grade = ''
  search(1)
}

const recordId = ref('')
/** 已载入病历的展示标识（优先登记号），保存确认与成功提示都要回显它（UX-01） */
const loadedLabel = ref('')
/** 病历基本信息（只读）：给出上下文，但不参与抽取 */
const loadedMeta = ref('')

const rowClass = ({ row }) => (row.id === recordId.value ? 'row-active' : '')

// ===== 原文：模块化字段（UX-62）=====
/**
 * 抽取输入字段与分组，与「病历数据」的单条新增保持同一套分区命名；
 * 只列真正参与抽取的 12 个字段，基本信息另在载入条里只读展示。
 */
const FIELD_GROUPS = [
  { title: '主诉与病史', keys: ['chiefComplaint', 'selfReport', 'presentIllness'] },
  { title: '四诊', keys: ['inspection', 'tongue', 'pulse', 'physicalExam'] },
  { title: '诊断', keys: ['tcmDiagnosis', 'pattern'] },
  { title: '处方与随访', keys: ['prescription', 'followUp', 'treatmentEffect'] }
]

/**
 * 字段标签与形态（UX-70 第七轮）。
 *
 * <p>`wide` 只留给长叙述（主诉 / 自诉 / 现病史 / 草药），占 2 列；
 * `multi` 用文本域、单行起步随输入自增，其余短字段单行输入。
 * 之前 12 个字段里 10 个是 wide，span 2 在 3 列栅格排不紧，纵向多出好几行。</p>
 */
const FIELD_LABELS = {
  chiefComplaint: { label: '主诉', multi: true, wide: true },
  selfReport: { label: '自诉', multi: true, wide: true },
  presentIllness: { label: '现病史', multi: true, wide: true },
  inspection: { label: '望诊', multi: true },
  tongue: { label: '舌诊', multi: true },
  pulse: { label: '脉诊', multi: true },
  physicalExam: { label: '查体', multi: true },
  tcmDiagnosis: { label: '中医诊断', multi: true },
  pattern: { label: '辨证结论', multi: true },
  prescription: { label: '草药', multi: true, wide: true },
  followUp: { label: '随访', multi: true },
  treatmentEffect: { label: '治疗效果', multi: true }
}

const ALL_KEYS = FIELD_GROUPS.flatMap((g) => g.keys)
const fieldsOf = (group) => group.keys.map((k) => ({ key: k, ...FIELD_LABELS[k] }))

const emptyFields = () => ALL_KEYS.reduce((o, k) => ({ ...o, [k]: '' }), {})
const fields = reactive(emptyFields())

/** 抽取请求的文本：字段拼接，分隔符与后端既有口径一致（后端接口不变） */
const composedText = computed(() => ALL_KEYS
  .map((k) => fields[k])
  .filter((s) => s && String(s).trim())
  .map((s) => String(s).trim())
  .join('。'))

const text = ref('')
const result = ref(null)
const extracting = ref(false)

const canSave = computed(() => !!recordId.value && !!result.value)

/** 载入一份病历：换病历时必须清空上一次抽取结果，否则会把 A 的结果存进 B（UX-01） */
const loadRecord = async (row) => {
  if (!row?.id) return
  listLoading.value = true
  try {
    const res = await getRawRecord(row.id)
    const d = res.data || {}
    Object.assign(fields, emptyFields())
    ALL_KEYS.forEach((k) => {
      fields[k] = d[k] == null ? '' : String(d[k])
    })
    recordId.value = row.id
    loadedLabel.value = d.registrationNo || row.registrationNo || row.id
    loadedMeta.value = [d.gender, d.age ? `${d.age} 岁` : '', d.department,
      d.visitTime ? String(d.visitTime).replace('T', ' ').substring(0, 16) : '']
      .filter(Boolean).join(' · ')
    result.value = null
    text.value = composedText.value
    activeTab.value = 'single'
  } catch {
    // 拦截器已提示
  } finally {
    listLoading.value = false
  }
}

/** 关闭详情（与人工复核一致的出口，第七轮）：清空当前病历与抽取结果，列表常驻可见 */
const closeDetail = () => {
  recordId.value = ''
  loadedLabel.value = ''
  loadedMeta.value = ''
  Object.assign(fields, emptyFields())
  result.value = null
  text.value = ''
}

const runExtract = async () => {
  extracting.value = true
  try {
    const res = await extractNlp({ text: composedText.value })
    result.value = res.data
    if (!res.data.modelAvailable) {
      ElMessage.warning('本次没有抽取到病历要素，术语归一没有可归的内容')
    }
  } catch {
    // 拦截器已提示
  } finally {
    extracting.value = false
  }
}

/**
 * 抽取无产出（UX-63 第七轮）。
 *
 * <p>用户在第七轮再次提出「结构化解析也应该执行术语归一」。实测
 * {@code NlpController.extract()} 第 43 行确实调用了 {@code EntityNormalizer.normalize}，
 * 归一一直有跑；之所以看起来「没执行」，是因为 {@code nlp.enabled=false} 时上游
 * 只回空 9 类，归一没有可归的内容。所以这里把「有没有产出、为什么没有」直接讲出来，
 * 而不是让一片空白自己表达。</p>
 *
 * <p>第八轮补充：{@code modelAvailable} 只是一个布尔值，「配置没开」与「服务挂了」在前端
 * 无法区分，所以 'off' 分支的文案只能说「未开启，或服务暂时不可用」，不能断言其一。
 * 要让它说准需要后端多下发一个原因字段（本次未做）。</p>
 */
const ENTITY_KEYS = [
  'diseases', 'symptoms', 'tongueList', 'pulseList', 'patternList',
  'causeList', 'treatmentList', 'formulaList', 'herbs'
]
const resultEmpty = computed(() => {
  const r = result.value
  if (!r) return false
  return ENTITY_KEYS.every((k) => !(Array.isArray(r[k]) && r[k].length))
})
/** ''＝有产出；'off'＝NLP 未启用；'none'＝已启用但未识别出实体 */
const emptyReason = computed(() => {
  if (!result.value || !resultEmpty.value) return ''
  return result.value.modelAvailable ? 'none' : 'off'
})

// ===== 术语归一试算（UX-63 第七轮）=====
/** 类型取自接口契约 NormalizeDTO.type 的枚举，不在此另立「字段 → 词典类型」映射 */
const NORM_TYPES = [
  { value: 'disease', label: '疾病' },
  { value: 'pattern', label: '证候' },
  { value: 'symptom', label: '症状' },
  { value: 'herb', label: '中药' },
  { value: 'formula', label: '方剂' }
]
const normType = ref('pattern')
const normTerm = ref('')
const normLoading = ref(false)
const normResult = ref(null)

/** 直接查词典归一（POST /api/governance/normalize），不经过 Python NLP 服务 */
const runNormalize = async () => {
  const term = normTerm.value.trim()
  if (!term) return
  normLoading.value = true
  try {
    const res = await normalize({ type: normType.value, term })
    const d = res.data || {}
    normResult.value = {
      term,
      standardTerm: d.standardTerm || term,
      source: d.source || ''
    }
  } catch {
    // 拦截器已提示
  } finally {
    normLoading.value = false
  }
}

const save = async () => {
  const label = loadedLabel.value || recordId.value
  try {
    await ElMessageBox.confirm(
      `将本次抽取结果写入病历「${label}」的结构化数据，覆盖原有内容。确认？`,
      '保存到病历',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await updateRecord(recordId.value, { structuredData: result.value })
    ElMessage.success(`已保存到病历「${label}」`)
  } catch {
    // 拦截器已提示
  }
}

// ===== 批量解析（UX-52 / UX-61：页面内嵌，不用弹窗）=====
const batchLimit = ref(50)
const batchRunning = ref(false)
const batchCancelled = ref(false)
const batchProgress = reactive({ done: 0, total: 0, current: '', success: 0, failed: 0 })
const batchFailures = ref([])
/** 批量范围条件（科室 / 就诊时间 / 证候 / 分级），与病历数据页同一套筛选 */
const batchFilters = reactive({ department: '', dateRange: null, pattern: '', grade: '' })

/**
 * 按筛选条件取病历，逐条抽取并保存。
 *
 * <p>后端暂无批量接口，这里在前端串行推进 —— 相比人工逐条点开仍是质变，
 * 且能给出真实进度与取消入口。若后续补批量任务接口，替换此循环即可。</p>
 */
const runBatch = async () => {
  batchRunning.value = true
  batchCancelled.value = false
  batchFailures.value = []
  Object.assign(batchProgress, { done: 0, total: 0, current: '', success: 0, failed: 0 })
  try {
    const res = await searchRecords({ ...batchFilters, page: 1, pageSize: batchLimit.value })
    const list = res.data?.records || []
    if (!list.length) {
      ElMessage.warning('当前筛选范围内没有可解析的病历')
      return
    }
    batchProgress.total = list.length
    for (let i = 0; i < list.length; i++) {
      if (batchCancelled.value) break
      const item = list[i]
      const label = item.registrationNo || item.id
      batchProgress.current = label
      try {
        const raw = await getRawRecord(item.id)
        const d = raw.data || {}
        const text = ALL_KEYS
          .map((k) => d[k])
          .filter((s) => s && String(s).trim())
          .map((s) => String(s).trim())
          .join('。')
        if (!text) {
          batchFailures.value.push({ label, reason: '该病历无可抽取的文本字段' })
          batchProgress.failed += 1
        } else {
          const ex = await extractNlp({ text })
          await updateRecord(item.id, { structuredData: ex.data })
          batchProgress.success += 1
        }
      } catch (e) {
        // 单条失败不影响后续；原因优先取后端 msg —— axios 的英文兜底对用户没有信息量
        batchFailures.value.push({ label, reason: apiErrorMessage(e, '抽取或保存失败') })
        batchProgress.failed += 1
      }
      batchProgress.done = i + 1
    }
    const tail = batchCancelled.value ? '（已取消，未处理剩余病历）' : ''
    ElMessage.success(`批量解析完成：成功 ${batchProgress.success} 条，失败 ${batchProgress.failed} 条${tail}`)
    search(1)
  } catch {
    // 拦截器已提示
  } finally {
    batchRunning.value = false
  }
}

onMounted(() => search(1))
</script>

<style scoped>
.tip { font-size: 12.5px; color: var(--text-sub); line-height: 1.7; }
.actions { margin-top: 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.nlp-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.nlp-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }
/* 「换病历」入口（UX-70 第七轮）：选择病历卡收起后挂在载入条右侧 */
.picker-toggle { font-weight: normal; margin-left: auto; }
.loaded-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  background: var(--ink-light);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 9px 14px;
  font-size: 12.5px;
  color: var(--text-sub);
  margin-bottom: 14px;
}
.loaded-bar b { color: var(--ink); }
/* 左栏（原文）给 1.5 份宽：3 列栅格才有可用宽度（UX-70）。
   定高一屏（UX-70 第七轮，真机量测定值）：12 个字段都是带正文的长文本，
   加上右侧对照区，整页在 1600×900 下量到 1033px、1366×768 下 997px，仍要下拉整页。
   把对照区框在一屏内、滚动收到框内部之后：① 页面本身不再出现下拉条；
   ② 左栏吸底的主操作条改成吸在这个框的底部，恒在视口内，不必滚到底再点「执行抽取」 */
.split {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
  max-height: calc(100vh - 385px);
  min-height: 280px;
  overflow: auto;
}
.pane { min-width: 0; }
.pane-hd { font-size: 13px; font-weight: bold; color: var(--ink); margin-bottom: 8px; }
.src-note { font-size: 11.5px; color: var(--ink-mid); font-weight: normal; margin-left: 8px; }
.src-note.warn { color: var(--danger); }
.norm-note {
  font-size: 12px;
  color: var(--text-sub);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 7px 11px;
  margin-bottom: 10px;
  line-height: 1.7;
}
.norm-note b { color: var(--ink); font-weight: normal; }
.norm-note.warn {
  background: #fdf6f4;
  border-color: #e3c3bb;
  color: #8a3d33;
}
/* 无产出提示（UX-63 第七轮）：把「为什么没有结果」摆到填写区上方，不藏在空态里 */
.nlp-off {
  background: #fdf6f4;
  border: 1px solid #e3c3bb;
  border-left: 3px solid var(--danger);
  border-radius: 6px;
  padding: 9px 13px;
  margin-bottom: 12px;
  font-size: 12.5px;
  line-height: 1.8;
  color: #8a3d33;
}
.nlp-off b { color: var(--danger); }

/* 术语归一试算（UX-63 第七轮）：词典直查，不依赖 NLP 服务 */
.norm-tool {
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.nt-hd { font-size: 12px; color: var(--text-sub); margin-bottom: 8px; }
.nt-row { display: flex; gap: 8px; align-items: center; }
.nt-result {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 9px;
  padding-top: 9px;
  border-top: 1px dashed #ece8dc;
  font-size: 12.5px;
}
.nt-in { color: var(--text-sub); }
.nt-arrow { color: #c9c3b4; }
.nt-out { color: var(--ink); font-weight: bold; }
.nt-out.miss { color: var(--danger); font-weight: normal; }
.nt-src { font-size: 11.5px; color: var(--text-sub); margin-left: auto; }
.nt-hint { margin-top: 8px; font-size: 11.5px; color: var(--text-sub); line-height: 1.7; }

/* 原文模块化字段（UX-62）；分区常显 + 3 列栅格（UX-70，与病历数据页同一口径）：
   wide（长文本）占 2 列而非整行，否则每行拉满宽度、纵向白白多出数行。
   第七轮再收紧行距与列间距，控件高度由 .compact-form 统一压到 small（24px） */
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 10px; }
.form-grid .wide { grid-column: span 2; }
.form-group { margin-bottom: 4px; }
.group-hd {
  position: relative;
  padding: 3px 0 4px 9px;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: bold;
  color: var(--ink);
  border-bottom: 1px solid var(--line);
}
.group-hd::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  width: 3px;
  height: 12px;
  background: var(--ink-mid);
}
.form-grid :deep(.el-form-item) { margin-bottom: 6px; }
.form-grid :deep(.el-form-item__label) { font-size: 12px; color: var(--text-sub); line-height: 1.5; padding-bottom: 0; }
.field-form { margin-bottom: 6px; }
/* 整段文本只读对照：默认收起，不占填写区版面 */
.composed-panel {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 0 12px;
  margin-bottom: 10px;
}
.composed-hd {
  cursor: pointer;
  list-style: none;
  padding: 9px 0;
  font-size: 12.5px;
  color: var(--ink-mid);
}
.composed-hd::-webkit-details-marker { display: none; }
.composed-hd::before { content: '▸ '; color: var(--ink-mid); }
.composed-panel[open] .composed-hd::before { content: '▾ '; }
.composed-panel[open] { padding-bottom: 10px; }
/* 执行抽取 / 保存吸底（UX-70）：字段区较长，主操作始终可见 */
.pane-actions {
  position: sticky;
  bottom: 0;
  background: #fff;
  padding: 8px 0;
  border-top: 1px solid var(--line);
  z-index: 1;
}
.composed {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--text);
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  margin-bottom: 10px;
}
:deep(.row-active) td { background: var(--ink-light) !important; }

/* 批量解析（页面内嵌） */
.batch-filter {
  margin: 14px 0 4px;
  padding: 12px 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.bf-title { font-size: 12.5px; color: var(--text-sub); margin-bottom: 8px; }
.batch-row { display: flex; align-items: center; gap: 10px; margin: 14px 0 4px; }
.batch-progress {
  margin-top: 14px;
  padding: 10px 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.bp-hd { font-size: 12.5px; color: var(--text); margin-bottom: 8px; }
.bp-hd b { color: var(--ink); }
.bp-sub { margin-top: 8px; font-size: 12px; color: var(--text-sub); }
.batch-failures { margin-top: 14px; border-top: 1px dashed #ece8dc; padding-top: 12px; }
.bf-hd { font-size: 12.5px; color: var(--text-sub); margin-bottom: 8px; }

@media (max-width: 1400px) {
  .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 1200px) {
  .split { grid-template-columns: 1fr; }
}
@media (max-width: 900px) {
  .form-grid { grid-template-columns: 1fr; }
  .form-grid .wide { grid-column: auto; }
}
</style>
