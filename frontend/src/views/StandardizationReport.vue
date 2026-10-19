<template>
  <div class="std-report">
    <!-- 范围查询（P0-1）：原先嵌在报告头卡内（.rh-tools），与其余页「内容区最顶部一条」
         的位置不一致。现提到报告头之前，用全站共享的 ScopeBar + RangeFilter：
         六页同一位置、同一外形。近一年/近三年是范围的快捷选法，同属筛选、一起放在条内；
         「导出 CSV」是业务动作（不改变筛选），留在报告头右侧。 -->
    <ScopeBar>
      <RangeFilter v-model="filters" :show-grade="false" :show-import="true" />
      <el-button size="default" :disabled="loading" @click="quickRange(1)">近一年</el-button>
      <el-button size="default" :disabled="loading" @click="quickRange(3)">近三年</el-button>
      <el-button type="primary" size="default" :loading="loading" @click="loadReport">
        {{ loadError ? '重试' : '查 询' }}
      </el-button>
      <el-button size="default" :disabled="loading" @click="resetFilters">重置</el-button>
      <span class="tip">范围对本页全部指标与图表生效</span>
    </ScopeBar>

    <!-- 报告头：一句话结论 + 统计口径 + 时间区间 / 刷新 / 导出，全部收进一张卡。
         此前「质控门禁条 / 加载失败条 / 数据来源条 / 时间工具条 / 结论条」是五条各自
         带左边框的长横幅，叠在正文顶部像五道横杠。现合并为单一头部：
         状态随「加载中 / 加载失败 / 质控未完成 / 无数据 / 短板 / 正常」变化，
         时间区间与导出集中在右侧，异常（失败 / 质控未完成）在同处就近处理。 -->
    <header class="rpt-head" :class="headline.tone">
      <div class="rh-status" aria-live="polite">
        <span class="rh-icon" aria-hidden="true">{{ headline.icon }}</span>
        <div class="rh-text">
          <div class="rh-title">{{ headline.title }}</div>
          <p v-if="headline.desc" class="rh-desc">{{ headline.desc }}</p>
          <!-- 质控未完成：补一句「哪些部分不受影响」，避免用户以为整页失效 -->
          <p v-if="showQcGate" class="rh-note">
            只想看词典建设进度可继续往下看 —— 那部分与质控无关。
          </p>
          <!-- 数据来源提示：只在真拿到报告后渲染 —— 加载中 / 失败时它会陈述「0 条病历」，
               那是错误陈述（不是 0 条，是还没读到 / 没读到）。 -->
          <p v-else-if="report && !loadError" class="rh-caveat">
            当前数据共 {{ report?.dataset?.recordCount ?? 0 }} 条病历、主诉
            {{ report?.dataset?.chiefComplaintTemplates ?? 0 }} 种写法，属测试数据；
            下列数字用于验证词典建设进度，不代表真实病历上的准确率。
          </p>
        </div>
      </div>

      <!-- P0-1：范围查询与查询/重置已上移到页首 ScopeBar；此处只留报告自身的动作 -->
      <div class="rh-tools">
        <el-button size="default" :loading="exporting" @click="handleExport">导出 CSV</el-button>
      </div>

      <!-- 质控未完成时的引导：本页只读，不提供重跑入口。
           重跑是「质控校验」与「结构化解析」两页的职责，本页再实现一套提交 + 轮询属重复；
           2026-10-15 按用户要求把两个重跑按钮改为提示 + 跳转。 -->
      <div v-if="showQcGate" class="rh-ops">
        <span class="tip">
          本页只读。重跑请到「质控校验」或「结构化解析」页发起，完成后回到本页刷新即可。
        </span>
        <el-button type="primary" size="small" @click="$router.push('/qc-check')">
          去质控校验
        </el-button>
        <el-button size="small" @click="$router.push('/nlp-extract')">
          去结构化解析
        </el-button>
        <span v-if="qcLast" class="tip">上次完成：{{ qcLast }}</span>
      </div>

      <p v-if="rangeTip" class="rh-range">{{ rangeTip }}</p>
    </header>

    <!-- P0-6：加载态给固定高度骨架（与终态同为 .kpi、同 min-height），
         KPI 区不再从 0px 一次性撑开 546px。 -->
    <div v-if="!loadError" class="kpi-row">
      <template v-if="loading">
        <div v-for="n in (kpis.length || 3)" :key="'kpi-skel-' + n" class="kpi kpi-skel" aria-hidden="true">
          <span class="sk sk-label" />
          <span class="sk sk-value" />
          <span class="sk sk-note" />
        </div>
      </template>
      <template v-else>
        <div v-for="k in kpis" :key="k.label" class="kpi" :class="k.tone">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value">{{ k.value }}</div>
          <div class="kpi-note">{{ k.note }}</div>
        </div>
      </template>
    </div>

    <!-- 28.22：归一率与缺词的图形视图。原先这两项只在下方收起区的 el-table 里，
         业务用户要先展开、再逐行读才知道短板在哪。
         失败态由报告头接管（不再重复渲染一屏失败文案），故这里直接不渲染。 -->
    <StandardizationCharts
      v-if="!loadError"
      :coverage="report?.coverage || []"
      :unmatched="report?.unmatched || {}"
      :loading="loading"
      :error="loadError"
      :range-label="chartRangeLabel"
      @retry="loadReport"
    />

    <!-- 第二屏：待办清单。这是本页最有价值的部分 ——
         把「多少条未归一」翻译成「该做什么、归谁、值多少」。 -->
    <PanelCard v-if="!loadError" title="建议的下一步">
      <template #extra>
        <span class="tip">按影响面排序，先做第一条</span>
      </template>
      <!-- P0-6：加载态给 2~3 行占位，列表不再从 0px 撑开 -->
      <ol v-if="loading" class="todo-list" aria-hidden="true">
        <li v-for="n in 3" :key="'todo-skel-' + n" class="todo">
          <div class="todo-idx" />
          <div class="todo-body">
            <div class="sk sk-line sk-w40" />
            <div class="sk sk-line sk-w90" />
          </div>
          <span class="sk sk-tag" />
        </li>
      </ol>
      <ol v-else class="todo-list">
        <li v-for="(t, i) in todos" :key="i" class="todo">
          <div class="todo-idx">{{ i + 1 }}</div>
          <div class="todo-body">
            <div class="todo-title">{{ t.title }}</div>
            <div class="todo-desc">{{ t.desc }}</div>
          </div>
          <!-- P1-8：右侧「标签 + 操作」固定宽度成列 —— 无按钮的行用同宽占位，
               否则第 1 条的标签被按钮挤到左边 101px，右侧列视觉断裂。 -->
          <div class="todo-ops">
            <el-tag size="small" :type="t.tagType" effect="plain">{{ t.owner }}</el-tag>
            <!-- 批次2：把这条待办的「待补词」复制走 —— 直达词典导入页粘贴即可，
                 避免用户看完「先补这 8 个词」还得自己手抄。无接口调用，不会有副作用。 -->
            <span class="todo-act">
              <el-button
                v-if="t.words && t.words.length"
                size="small"
                @click="copyGapWords(t.words)"
              >复制待补词</el-button>
            </span>
          </div>
        </li>
      </ol>
      <div v-if="!loading && !todos.length" class="empty-tip">
        {{ emptyTip }}
      </div>
    </PanelCard>

    <!-- 第三屏：明细默认收起，只剩「诊断明细」一项（2026-10-15 删去按月份 / 各词典明细 /
         各类术语归一情况三项：业务侧不需要，归一率已有上方图表）。 -->
    <StandardizationDetails
      v-if="!loadError"
      :report="report"
      :has-stale-rows="hasStaleRows"
      :qc-scored="qcScored"
      :qc-total="qcTotal"
      :qc-last="qcLast"
      :score-range="scoreRange"
    />

    <!-- 导出按钮已上移到报告头右侧，页面底部不再留孤立的操作行 -->
  </div>
</template>

<script setup>
// 标准化质量报告（批次 24）。
//
// 设计取向：这页的读者是质控科业务用户，不是工程师。所以
//   ① 先给结论，再给数字，最后才给明细；
//   ② 把「多少条没归一」翻译成「该做什么、归谁管」；
//   ③ 明细默认收起，需要时再展开。
// 技术口径（甲类/乙类、normLevel、可归一实体）只在本文件内部使用，不出现在界面上。
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import RangeFilter from '@/components/RangeFilter.vue'
import ScopeBar from '@/components/ScopeBar.vue'
import StandardizationDetails from '@/components/StandardizationDetails.vue'
import StandardizationCharts from '@/components/StandardizationCharts.vue'
import { getStandardizationReport, exportStandardizationReport } from '@/api/stats'
import { saveBlob } from '@/utils/download'

const report = ref(null)
const loading = ref(false)
// P0-4：加载失败与加载中互斥 —— null 只表示「还没读到」，失败另有其位，
// 否则失败会被渲染成「正在读取…」+ 空态 + 绿色结论（把故障读成了一切正常）。
const loadError = ref(false)
const exporting = ref(false)

const fmtDate = (d) => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

// 默认口径：今天导入的数据（不分科室）。
// 导入时间（importRange）与就诊时间（dateRange）是两个独立维度，可同时生效、取交集；
// 用户想放宽到全量，把导入时间清空即可（后端按「未给」处理）。
const today = fmtDate(new Date())
const filters = reactive({ department: '', dateRange: null, grade: '', importRange: [today, today] })

/** 时间快捷按钮：只改写 dateRange，随后立即查询 */
function quickRange(years) {
  const end = new Date()
  const start = new Date(end.getFullYear() - years + 1, 0, 1)
  filters.dateRange = [fmtDate(start), fmtDate(end)]
  loadReport()
}

const resetFilters = () => {
  filters.department = ''
  filters.dateRange = null
  filters.grade = ''
  // 回到默认口径（今天导入），不是清空成全量 —— 与初始进入页面一致
  filters.importRange = [today, today]
  loadReport()
}

/** 报告 / 导出参数：两个时间维度都两端齐全才生效；department 为空发空串 */
const params = () => {
  const d = filters.dateRange
  const ir = filters.importRange
  return {
    department: filters.department || '',
    start: d && d.length === 2 ? d[0] : '',
    end: d && d.length === 2 ? d[1] : '',
    importStart: ir && ir.length === 2 ? ir[0] : '',
    importEnd: ir && ir.length === 2 ? ir[1] : ''
  }
}

const rangeTip = computed(() => {
  const r = report.value?.range
  if (!r) return ''
  const scope = []
  if (filters.department) scope.push(`科室＝${filters.department}`)
  const scopeTxt = scope.length ? scope.join(' · ') + '；' : ''
  // 导入区间（页面默认口径）优先展示；接诊区间是另一个维度，同时生效时并列
  if (r.importStart || r.importEnd) {
    const visit = (r.start || r.end)
      ? ` · 就诊 ${r.start || '不限'} ~ ${r.end || '不限'}`
      : ''
    const excl = r.excluded > 0 ? `（已排除 ${r.excluded} 条区间外数据）` : ''
    return `${scopeTxt}导入 ${r.importStart || '不限'} ~ ${r.importEnd || '不限'}，`
      + `区间内 ${r.records} 条${excl}${visit}`
  }
  if (r.start || r.end) {
    return `${scopeTxt}当前统计 ${r.start || '不限'} ~ ${r.end || '不限'}，区间内 ${r.records} 条`
      + (r.excluded > 0 ? `（已排除 ${r.excluded} 条区间外数据）` : '')
  }
  return `${scopeTxt}当前统计全部 ${r.records} 条`
})

/**
 * 「本期图景」面板的范围口径标签：与页头 {@code rangeTip} 同源，取
 * {@code report.range} 的后端回显（不是前端 filters 的直接映射 ——
 * 回显值才是后端实际生效的区间）。
 *
 * <p>只取导入区间这一维：页面默认口径就是「今天导入」，这是用户眼里
 * 「本期」的含义。两端都有且相同 → 单日；清空导入时间 → 全部导入。</p>
 */
const chartRangeLabel = computed(() => {
  const r = report.value?.range
  const s = r?.importStart || ''
  const e = r?.importEnd || ''
  if (s && e) return s === e ? `导入 ${s}` : `导入 ${s} ~ ${e}`
  if (s || e) return `导入 ${s || '不限'} ~ ${e || '不限'}`
  return '全部导入'
})

const pct = (part, total) => (total ? `${((part / total) * 100).toFixed(1)}%` : '—')

// ---------------- 质控完成度（决定报告有没有意义）
// 三态而不是两态：未加载 / 已加载且未完成 / 已加载且完成。
// 早前只用两态，页面打开时 report 还是 null，complete 取到 false，
// 「质控没跑完」的告警会先闪一下再消失 —— 看着像出了故障。
const qc = computed(() => report.value?.qc || null)
const qcLoaded = computed(() => qc.value !== null)
const qcComplete = computed(() => qc.value?.complete === true)
/**
 * 只在「确实拿到了结果、有数据、且不完整」时才提示。
 *
 * <p>空数据必须走空态（「本期无数据可评估」），不能被读成「质控没跑完」——
 * 默认口径收窄到「今天导入」后，今天没导数据是常见情形，那种情况下
 * {@code qc.total} 为 0、{@code complete} 为 false，若不短路会误报质控未完成。</p>
 */
const showQcGate = computed(() => qcLoaded.value && !isEmptyReport.value && !qcComplete.value)
const qcTotal = computed(() => qc.value?.total ?? report.value?.dataset?.recordCount ?? 0)
const qcScored = computed(() => qc.value?.scored ?? 0)
const qcLast = computed(() => qc.value?.lastScoredAt || '')
// P2-9：是否存在「解析早于词表」的类型 —— 新鲜度徽标的过期态取这里。
// 判据用 coverage 的 suspectedStaleExtraction，与下方 staleTypes 同源。
// 原先误读 report.monthly（后端字段叫 byMonth，且 MonthlyBucket 根本没有 stale 字段），
// 结果恒为 false ⇒ 徽标永不进入过期态。
const hasStaleRows = computed(() => (report.value?.coverage || [])
  .some((c) => c.suspectedStaleExtraction))

/**
 * 找出「最该补词表」的类型。
 *
 * 只在**实际抽取到实体**的类型里比大小：方剂只有 5 条、治法 0 条，
 * 但这两类在当前数据里一条实体都没抽到（数据本身没有对应文本），
 * 按条数排序会把它们排到最前头，给出「先补方剂词表」这种无效建议。
 * 补一张没数据可匹配的词表，对归一率毫无帮助 —— 要补的是
 * 「量最大 × 词表最小」的那一类，那才是真正的瓶颈。
 */
  const bottleneck = computed(() => {
    const d = report.value
    if (!d) return null
    // coverage.field 是 structured_data 的字段名（symptoms/diseases/…），
    // dictQuality.label 是中文名（症状/疾病/…），两者不同名。
    // 必须按 label 对齐 —— 早前误用 field 建 Map 再用 label 查，
    // 结果恒为 0，「最该补的词表」卡片就一直显示「—」。
    const byLabel = new Map((d.coverage || []).map((c) => [c.label, c]))
    const candidates = (d.dictQuality || []).filter((x) => {
      const c = byLabel.get(x.label)
      return x.termCount > 0 && c && c.total > 0
    })
    if (!candidates.length) return null
    // 未归一量降序、词表量升序：先补「缺口大且词表小」的那类
    return candidates
      .map((x) => {
        const c = byLabel.get(x.label)
        return { ...x, unmatched: c.total - c.normalized }
      })
      .sort((a, b) => (b.unmatched - a.unmatched) || (a.termCount - b.termCount))[0]
  })

// ---------------- 空数据判据（P0-5）----------------
// 「没有数据」绝不能渲染成「全部达标」：指标卡的绿色与结论条的绿色对勾都只在
// 真有数据可评时才允许出现。展示分支判定，不动后端任何计算口径。
const isEmptyReport = computed(() => {
  const d = report.value
  if (!d) return false
  return (d.dataset?.recordCount ?? 0) === 0 || (d.coverage || []).length === 0
})

// ---------------- 关键卡 ----------------
// 三张卡各回答一个业务问题：词典够不够、归一顺不顺、评分灵不灵
const kpis = computed(() => {
  const d = report.value
  if (!d) return []
  if (isEmptyReport.value) {
    return [
      { label: '最该补的词表', value: '—', note: '本期无数据可评估', tone: '' },
      { label: '术语归一率', value: '—', note: '本期无数据可评估', tone: '' },
      { label: '评分区分度', value: '—', note: '本期无数据可评估', tone: '' }
    ]
  }
  const gap = d.unmatched || {}
  const gapRate = gap.total ? gap.dictionaryGap / gap.total : 0
  const b = bottleneck.value
  const s = d.score || {}
  const cappedRate = s.total ? s.capped / s.total : 0

  return [
    {
      label: '最该补的词表',
      value: b ? `${b.label} ${b.termCount} 条` : '—',
      note: b
        ? `有 ${b.unmatched} 条实体抽到了却没归上，而它只有 ${b.termCount} 条词；`
        + '补这一类见效最快'
        : '暂无明显短板',
      tone: b && b.unmatched > 0 ? 'warn' : 'ok'
    },
    {
      label: '术语归一率',
      value: symRate(d),
      note: gapRate >= 0.5
        ? `未归一的 ${gap.total} 条里有 ${pct(gap.dictionaryGap, gap.total)} 是词表没收录`
        : '词表覆盖尚可',
      tone: symRateValue(d) < 0.5 ? 'warn' : 'ok'
    },
    {
      label: '评分区分度',
      value: pct(s.capped, s.total),
      note: cappedRate > 0.3
        ? `${pct(s.capped, s.total)} 的病历扣分相同，分数难以区分质量`
        : '扣分分布较分散，评分有区分度',
      tone: cappedRate > 0.3 ? 'warn' : 'ok'
    }
  ]
})

function symRate(d) {
  const sym = (d.coverage || []).find((c) => c.field === 'symptoms')
  return sym && sym.total ? pct(sym.normalized, sym.total) : '—'
}
function symRateValue(d) {
  const sym = (d.coverage || []).find((c) => c.field === 'symptoms')
  return sym && sym.total ? sym.normalized / sym.total : 0
}

// ---------------- 一句话结论（报告头状态）----------------
// 五态互斥：加载失败 / 质控未完成 / 加载中 / 无数据 / 有话要说。
// 前两态此前各占一条独立横幅，现统一由报告头表达，操作就近放在右侧。
const headline = computed(() => {
  // P0-4：失败优先于一切 —— 不读 report（它是 null，会误导成「正在读取…」）
  if (loadError.value) {
    return {
      icon: '!',
      tone: 'danger',
      title: '报告加载失败',
      desc: '服务没有返回数据，本页不展示任何统计结论 —— 故障不能被读成「没有数据」或「一切正常」。'
    }
  }
  // 质控未完成：分数类结论不可信，先说清「为什么不可信」再给重跑入口
  if (showQcGate.value) {
    return {
      icon: '!',
      tone: 'warn',
      title: '质控评分还没跑完，现在的数字不可信',
      // 「当前范围」而非「本组织」：qcTotal 是范围内条数，默认口径是今天导入，
      // 说「本组织 N 条」会把范围数说成全组织数（范围外的未评分病历也被算进分母）
      desc: `当前范围 ${qcTotal.value} 条病历中，有 ${qcScored.value} 条已完成质控评分。`
        + '报告里的「评分区分度」「扣分封顶」都来自质控结果，质控完成后报告才有意义。'
    }
  }
  const d = report.value
  if (!d) return { icon: '·', tone: '', title: '正在读取…', desc: '' }
  // P0-5：空数据走中性分支，不得进入 ok 分支（绿色对勾 + 「合理区间」是假绿灯）
  if (isEmptyReport.value) {
    return {
      icon: '·',
      tone: '',
      title: '本期无数据可评估',
      desc: '统计区间内没有可用于评估的病历数据，换一个时间区间或先导入病历。'
    }
  }
  const u = d.unmatched || {}
  const b = bottleneck.value
  const s = d.score || {}

  if (u.total && u.dictionaryGap / u.total >= 0.5) {
    return {
      icon: '!',
      tone: 'warn',
      title: '词典建设是当前主要瓶颈',
      desc: `术语未归一的 ${u.total} 条里有 ${pct(u.dictionaryGap, u.total)} 是词表里没有收录。`
        + (b ? `最该补的是${b.label}：它只有 ${b.termCount} 条词，却有 ${b.unmatched} 条实体没能归上。` : '')
    }
  }
  if (s.total && s.capped / s.total > 0.5) {
    return {
      icon: '!',
      tone: 'warn',
      title: '评分区分度不足',
      desc: `${pct(s.capped, s.total)} 的病历因「未归一术语」被扣满上限，分数集中在 `
        + `${s.min}~${s.max}，难以据此判断病历质量。`
    }
  }
  return {
    icon: '✓',
    tone: 'ok',
    title: '词典覆盖与评分处于合理区间',
    desc: '暂未发现明显短板，可继续扩充词表提升覆盖面。'
  }
})

// ---------------- 待办清单 ----------------
// 按影响面排序，每条说清「做什么、归谁、能解决多少」
const todos = computed(() => {
  const d = report.value
  if (!d) return []
  const u = d.unmatched || {}
  const list = []
  const b = bottleneck.value

  // ① 先说「解析早于词表建立」—— 它排在最前不是因为最严重，
  //    而是因为它会让后面所有词表相关的判断都失真：不重跑解析，
  //    你在这页看到的归一率全是旧的，补词表的效果也验证不了。
  if (staleTypes.value.length) {
    const names = staleTypes.value.map((c) => `${c.label}（${c.termCount} 词）`).join('、')
    list.push({
      title: '重跑结构化解析',
      desc: `${names} 已抽到实体却一条都没归上 —— 结构化数据早于词表建立，`
        + '不重跑解析，下面几条的效果都验证不了。',
      owner: '抽取',
      tagType: 'warning'
    })
  }

  if (u.dictionaryGap > 0) {
    const real = realGapTypes.value
    const realTotal = real.reduce((a, c) => a + (c.total - c.normalized), 0)
    // 批次2：后端新给的「词表缺口 TOP15」（键=实体原文，值=次数）—— 让这条待办从
    // 「有 N 条没归上」变成「先补这几个词」，用户可直接拿它去补词表（不再需要自己去翻数据）
    const topPairs = Object.entries(u.top || {})
      .sort((a, b) => b[1] - a[1])
      .slice(0, 8)
    const topWords = topPairs.map(([w, n]) => `${w}（${n} 次）`).join('、')
    list.push({
      title: b ? `补充${b.label}标准词表` : '补充标准词表',
      // 批次2：把 TOP8 词原文一并带上，供模板里的「复制待补词」按钮使用（可行动清单的出口）
      words: topPairs.map(([w]) => w),
      desc: `${b ? b.label : '症状'}类现有 ${b ? b.termCount : 72} 条词，`
        + (real.length > 1
          ? `未归一的 ${realTotal} 条分布在 ${real.map((c) => c.label).join('、')}。`
          : `未归一的 ${u.dictionaryGap} 条均属此类。`)
        // 病因自本批次起有独立词表（causes.json，出厂可能为空），其未归一属**词表缺口**
        // （尚未建词 / 未收录该词）而非解析问题；提示用户把病因标准词补上即可。
        + '病因类若未归一，属词表尚未建全而非解析问题。'
        + (topWords ? `最该先补：${topWords}。` : '')
        + '补完需重跑解析。',
      owner: '词表',
      tagType: 'warning'
    })
  }
  if (u.misrouted > 0) {
    list.push({
      title: '修复脉象、舌象被当成症状',
      desc: `${u.misrouted} 条「脉细数」这类脉象要素被归进了症状，会拉低症状归一率。`,
      owner: '抽取',
      tagType: 'warning'
    })
  }
  const s = d.score || {}
  if (s.total && s.capped / s.total > 0.3) {
    list.push({
      title: '放宽质控扣分上限',
      desc: `${pct(s.capped, s.total)} 的病历「未归一术语」被扣满上限，分数失去区分度。`
        + '建议按未归一数量分段扣分。',
      owner: '规则',
      tagType: 'warning'
    })
  }
  const selfAlias = (d.dictQuality || []).reduce((a, b2) => a + (b2.selfAliasCount || 0), 0)
  if (selfAlias > 0) {
    list.push({
      title: '清理重复别名',
      desc: `有 ${selfAlias} 个词条把标准词本身也写进了别名，`
        + '会在归一时自己命中自己，属词表数据缺陷。',
      owner: '词表',
      tagType: 'info'
    })
  }
  if (u.fragment > 0) {
    list.push({
      title: '补齐两字症状词',
      // 这一桶原先叫「减少抽取残词」，文案断言「抽取时截断的残字（如单字）… 与词表无关」。
      // 2026-10-15 实测推翻了这个归因：36739 条里**单字 0 条**、全部是 2 字；每一条的
      // 实体原文与 sourceText 长度**完全相等**（没有截断痕迹）；去重后 2642 个词
      // **一个都不在症状词典**（词典只有 110 条标准词），高频的是 鼻塞(1473)、咽痛(949)、
      // 咽痒(806)、腰酸(780)、恶寒(750) 这类**完整规范**的症状词 ⇒ 主体是词表缺口。
      // 低频长尾里确实混着真残字（无发、素情、D5…），但按词频切不可靠（阈值 3 时残字桶里
      // 仍留着 遗精、厌食、气促 这类正常词），所以不硬切两桶，只如实描述两段构成。
      desc: `有 ${u.fragment} 条未归一实体只有 2 个字，其中没有单字、实体原文与原文片段等长；`
        + '高频的是「鼻塞」「咽痛」「咽痒」这类完整症状词，属词表未收录，补进症状词典即可归一。'
        + '只有极低频的长尾（如「无发」「素情」）才是抽取截断的残字。',
      owner: '词表',
      tagType: 'info'
    })
  }
  return list
})

// 待办区的空态文案：真·空数据要说「无数据」，不能说「没有短板、一切合理」
const emptyTip = computed(() => (isEmptyReport.value
  ? '本期无可评估数据，暂无待办建议。'
  : '当前没有明显短板。词典规模、归一与评分都处在合理区间。'))

// ---------------- 明细 ----------------
/** 词表非空、也抽到了实体，却一条都没归上 —— 这是解析早于词表建立，补词表无效 */
const staleTypes = computed(() =>
  (report.value?.coverage || []).filter((c) => c.suspectedStaleExtraction)
)

/** 真正的「词表没收录」：排除了上述那类，剩下的才是补词表能解决的 */
const realGapTypes = computed(() =>
  (report.value?.coverage || [])
    .filter((c) => c.total > 0 && !c.suspectedStaleExtraction && c.total - c.normalized > 0)
)

const scoreRange = computed(() => {
  const s = report.value?.score
  return s && s.total ? `${s.min} ~ ${s.max}` : '—'
})

const loadReport = async () => {
loading.value = true
loadError.value = false
try {
      // 范围为空时各参数为 ''，后端按「未给」处理
      const res = await getStandardizationReport(params())
      report.value = res.data || null
    } catch {
      // 拦截器已提示，这里不叠加泛化文案；
      // P0-4：失败必须落进独立状态位，不能只把 report 置 null 了事
      report.value = null
      loadError.value = true
    } finally {
      loading.value = false
    }
  }

// 导出 CSV（28.23）：改由后端生成文件流，与数据集/日志两条导出口径一致。
// 后端成功回文件流、失败回 JSON，故先判别 blob 类型，避免把错误 JSON 当 CSV 存下来。
const handleExport = async () => {
  const r = report.value
  if (!r) return
  exporting.value = true
  try {
    const blob = await exportStandardizationReport(params())
    if (blob && blob.type && blob.type.includes('application/json')) {
      let msg = '导出失败'
      try {
        msg = JSON.parse(await blob.text()).msg || msg
      } catch { /* 解析不出就沿用默认文案 */ }
      ElMessage.error(msg)
      return
    }
    saveBlob(blob, `标准化质量报告-${r.generatedAt.replace(/[-: ]/g, '')}.csv`)
    // P1-7：导出成功此前静默 —— 下载发生在浏览器层，页面必须给一句确认
    ElMessage.success('报告已导出')
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

// ---- 待补词复制 ----

/**
 * 复制待补词到剪贴板。
 *
 * 写成函数而不是模板内联表达式，是为了处理一个真实故障：navigator.clipboard
 * 只在安全上下文（HTTPS / localhost）下存在，内网 HTTP 部署时它是 undefined ——
 * 内联写法 `navigator.clipboard.writeText(...)` 会**同步抛 TypeError**，
 * 后面的 .catch 根本不会执行，用户看到的就是「点了没反应」。
 *
 * 所以走双通道：异步剪贴板 API 可用就优先用；不可用或失败则退回 execCommand 兜底；
 * 两条都失败时把词显示出来让用户手动复制 —— 保证任何情况下都有反馈，不静默失败。
 */
async function copyGapWords(words) {
  const list = words || []
  if (!list.length) return
  const okTip = `已复制 ${list.length} 个待补词，可到「术语词典 → 批量导入」粘贴`

  if (window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(list.join('、'))
      ElMessage.success(okTip)
      return
    } catch {
      // 权限被拒 / 文档未聚焦：落到下面的兜底通道
    }
  }
  if (copyByExecCommand(list.join('、'))) {
    ElMessage.success(okTip)
    return
  }
  // 两条通道都失败：至少让用户能手动复制，不给「静默失败」
  ElMessageBox.alert(list.join('\n'), '复制失败，请手动选中以下待补词复制', {
    confirmButtonText: '知道了'
  })
}

/** execCommand('copy') 兜底：HTTP 环境下的唯一可用通道（API 已废弃，但浏览器仍普遍支持） */
function copyByExecCommand(text) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  ta.style.position = 'fixed'
  ta.style.top = '-1000px'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  try {
    ta.select()
    ta.setSelectionRange(0, text.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(ta)
  }
}

// 默认口径即「今天导入」（不分科室），不再预选第一个科室 ——
// 科室下拉由 RangeFilter 自己加载（组件内 useDepartments），页面无需再拉一次。
onMounted(() => {
  loadReport()
})
</script>

<style scoped>
/* ===== 报告头：一句话结论 + 统计口径 + 时间区间 / 刷新 / 导出，单卡承载 =====
   此前「质控门禁条 / 加载失败条 / 数据来源条 / 时间工具条 / 结论条」五条各自
   带左边框的长横幅叠在正文顶部。现收敛为一张卡：左边是随状态变化的结论，
   右侧是操作，异常（失败 / 质控未完成）的引导与跳转就近放在结论下方。
   状态底色沿用旧结论条的三档语义：warn=赭石浅底，ok=米灰底，danger=警示浅底。 */
.rpt-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: var(--sp-3) var(--sp-4);
  padding: var(--sp-4);
  margin-bottom: var(--sp-3);
  background: var(--surface);
  border: 1px solid var(--line);
  border-left: 3px solid var(--ink-mid);
  /* P2-1：容器类统一 6px 圆角（与 PanelCard 同档） */
  border-radius: 6px;
}
.rpt-head.warn {
  border-left-color: var(--ochre);
  background: var(--ochre-surface);
}
.rpt-head.ok {
  border-left-color: var(--success);
  background: var(--surface-sub);
}
.rpt-head.danger {
  border-left-color: var(--danger);
  background: var(--danger-surface);
}
/* 状态区：占剩余宽度，窄则换行 */
.rh-status {
  flex: 1 1 420px;
  min-width: 0;
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
}
.rh-icon {
  flex: 0 0 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--ink-mid);
  color: var(--surface);
  font-size: var(--fs-xs);
  font-weight: 700;
}
.rpt-head.warn .rh-icon { background: var(--ochre-deep); }
.rpt-head.ok .rh-icon { background: var(--success); }
.rpt-head.danger .rh-icon { background: var(--danger); }
.rh-text { min-width: 0; }
.rh-title {
  font-size: var(--fs-title);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.rh-desc {
  margin: 0;
  font-size: var(--fs-base);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
/* 结论下方的两条次级说明：质控未完成时的「仍可看什么」、测试数据口径提示 */
.rh-note,
.rh-caveat {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
.rh-caveat { color: var(--text-sub); }
/* 操作区：时间区间 / 刷新 / 导出 */
.rh-tools {
  flex: 0 1 auto;
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}
/* 质控未完成时的引导与跳转：与状态同卡，靠上边框分组 */
.rh-ops {
  flex: 1 1 100%;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-1);
  padding-top: var(--sp-3);
  border-top: 1px solid var(--line-soft);
}
/* 当前统计区间的口径说明 */
.rh-range {
  flex: 1 1 100%;
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
}

/* 关键卡 */
.kpi-row {
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
  /* P2-2：区块间距收口到 --sp-3 —— 与上下相邻区块同一档 */
  margin-bottom: var(--sp-3);
}
.kpi {
  /* 三张卡等分一行：基宽按 3 等分取（扣掉 2 个 gap），禁止 grow ——
     此前误按 4 等分，三张卡只占 3/4 宽，右侧留出一条空白。
     窄屏走 2 列 / 1 列断点，同行永远等宽。 */
  flex: 0 1 calc((100% - 2 * var(--sp-3)) / 3);
  min-width: 0;
  /* P0-6：min-height 把骨架与终态钉在同一高度，加载不产生布局跳动 */
  min-height: 134px;
  padding: var(--sp-3);
  border: 1px solid var(--line);
  /* P2-1：容器类 6px */
  border-radius: 6px;
  background: var(--surface);
  /* 留白·对称：卡内 label / 大数字 / note 三段在固定高度内垂直分布，
     不再顶在卡头、底部留一大块空。骨架 .kpi-skel 自身 display:block 覆盖，不受影响。 */
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.kpi.warn { border-left: 3px solid var(--ochre); }
.kpi.ok { border-left: 3px solid var(--success); }
/* 窄视口：两列等宽（最后一张独占一行的场景由 flex-grow:0 保证不被拉满，保持卡宽一致） */
@media (max-width: 1100px) {
  .kpi { flex-basis: calc((100% - var(--sp-3)) / 2); }
}
@media (max-width: 700px) {
  .kpi { flex-basis: 100%; }
}
/* 骨架块 —— 静态占位（全站动效令牌约束：不允许新增字面量 transition/animation） */
.kpi-skel { display: block; }
.sk {
  display: block;
  background: var(--surface-sub);
  border: 1px solid var(--line);
  border-radius: 4px;
}
.sk-label { width: 72px; height: 14px; margin-bottom: 8px; }
.sk-value { width: 116px; height: 34px; margin-bottom: 10px; }
.sk-note { width: 70%; height: 14px; }
.kpi-label {
  font-size: var(--fs-xs);
  color: var(--text-sub-strong);
  margin-bottom: 2px;
}
.kpi-value {
  font-size: var(--fs-xl);
  font-weight: 600;
  color: var(--ink);
  line-height: 1.3;
}
.kpi-note {
  margin-top: 4px;
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--text-sub-strong);
}
/* 待办清单 */
.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.todo {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
  padding: var(--sp-3) 0;
  border-bottom: 1px dashed var(--line);
}
.todo:last-child { border-bottom: none; }
.todo-idx {
  flex: 0 0 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 50%;
  background: var(--ink-mid);
  color: var(--surface);
  font-size: var(--fs-xs);
  font-weight: 600;
}
.todo-body { flex: 1 1 auto; min-width: 0; }
.todo-title {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.todo-desc {
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
  /* P2-5：长说明限宽 —— 避免单行 85+ 字远超中文长文可读行宽 */
  max-width: 88ch;
}
/* P1-8：右侧固定宽度操作列。无按钮的行用 .todo-act 同宽占位，
     保证多条待办的标签左缘竖直成列（A17）。 */
.todo-ops {
  flex: 0 0 160px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sp-2);
}
.todo-act {
  flex: 0 0 96px;
  display: flex;
  justify-content: flex-end;
}
/* P0-6：待办区加载占位 —— 2~3 行，不再是 0 高 */
.todo-list .sk-line {
  height: 14px;
  border-radius: 4px;
  background: var(--surface-sub);
  margin-bottom: 6px;
}
.sk-w40 { width: 40%; }
.sk-w90 { width: 90%; }
.sk-tag {
  flex: 0 0 48px;
  height: 20px;
  border-radius: 4px;
  background: var(--surface-sub);
}
.empty-tip {
  padding: var(--sp-3) 0;
  font-size: var(--fs-base);
  color: var(--text-sub-strong);
}

/*
 * 打印 / 导出 PDF 功能已移除（2026-10-07）：原 28.18 的「打印 / 另存 PDF」按钮
 * 走浏览器打印，后端 pdfbox 从未使用且无 CJK 字体，实际依赖用户在打印对话框里
 * 「另存为 PDF」，体验鸡肋，按需求整段删除（按钮 / handlePrint / scoped @media print）。
 */
</style>