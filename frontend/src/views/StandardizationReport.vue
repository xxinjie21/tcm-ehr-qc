<template>
  <div class="std-report">
    <!-- 数据来源提示：放在顶部但用轻量样式，不用刺眼的告警条。
         这批数据只有 10 个模板，数字不能当真实病历性能看，但也不该拦住用户往下读。 -->
    <div class="src-note">
      <span class="src-icon">i</span>
      <span>
        当前数据共 {{ report?.dataset?.recordCount ?? 0 }} 条病历，主诉只有
        {{ report?.dataset?.chiefComplaintTemplates ?? 0 }} 种写法，属于测试数据。
        下面的数字用于<strong>验证词典建设进度</strong>，不代表真实病历上的准确率。
      </span>
    </div>

    <!-- 第一屏：一句话结论 + 三个关键卡。看这一屏就知道该做什么、去哪看。 -->
    <div class="headline" :class="headline.tone">
      <div class="hl-icon">{{ headline.icon }}</div>
      <div class="hl-text">
        <div class="hl-title">{{ headline.title }}</div>
        <div class="hl-desc">{{ headline.desc }}</div>
      </div>
    </div>

    <div class="kpi-row">
      <div v-for="k in kpis" :key="k.label" class="kpi" :class="k.tone">
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-value">{{ k.value }}</div>
        <div class="kpi-note">{{ k.note }}</div>
      </div>
    </div>

    <!-- 第二屏：待办清单。这是本页最有价值的部分 ——
         把「多少条未归一」翻译成「该做什么、归谁、值多少」。 -->
    <PanelCard title="建议的下一步">
      <template #extra>
        <span class="tip">按影响面排序，先做第一条</span>
      </template>
      <ol class="todo-list">
        <li v-for="(t, i) in todos" :key="i" class="todo">
          <div class="todo-idx">{{ i + 1 }}</div>
          <div class="todo-body">
            <div class="todo-title">{{ t.title }}</div>
            <div class="todo-desc">{{ t.desc }}</div>
          </div>
          <el-tag size="small" :type="t.tagType" effect="plain">{{ t.owner }}</el-tag>
        </li>
      </ol>
      <div v-if="!todos.length" class="empty-tip">
        当前没有明显短板。词典规模、归一与评分都处在合理区间。
      </div>
    </PanelCard>

    <!-- 第三屏：明细默认收起。业务用户通常不需要逐类看，展开即可。 -->
    <el-collapse class="detail">
      <el-collapse-item name="dict">
        <template #title>
          <span class="ct">各词典明细</span>
          <span class="ct-sub">词条数、别名与编码情况</span>
        </template>
        <el-table :data="report?.dictQuality || []" border size="small">
          <el-table-column prop="label" label="类型" width="90" />
          <el-table-column prop="termCount" label="词条数" width="90" align="right" />
          <el-table-column label="有别名" width="100" align="right">
            <template #default="{ row }">{{ row.aliasedCount }}</template>
          </el-table-column>
          <el-table-column label="有国标编码" width="120" align="right">
            <template #default="{ row }">
              <span :class="{ warn: row.codedCount === 0 }">{{ row.codedCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="别名重复" width="100" align="right">
            <template #default="{ row }">
              <span :class="{ warn: row.selfAliasCount > 0 }">{{ row.selfAliasCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="source" label="词表来源" min-width="170" show-overflow-tooltip />
        </el-table>
        <p v-if="report?.crossTypeDuplicates?.length" class="detail-note">
          另有 {{ report.crossTypeDuplicates.length }} 个术语同时出现在多本词典里
          （如 {{ report.crossTypeDuplicates.slice(0, 6).join('、') }}），
          同一词可能在不同类型下命中不同词典，建议只在一处保留。
        </p>
      </el-collapse-item>

      <el-collapse-item name="cover">
        <template #title>
          <span class="ct">各类术语归一情况</span>
          <span class="ct-sub">抽取了多少、归一了多少</span>
        </template>
        <el-table :data="coverageRows" border size="small">
          <el-table-column prop="label" label="类型" width="90" />
          <el-table-column prop="total" label="抽取到" width="100" align="right" />
          <el-table-column prop="normalized" label="已归一" width="100" align="right" />
          <el-table-column label="归一率" min-width="110">
            <template #default="{ row }">{{ row.rate }}</template>
          </el-table-column>
        </el-table>
      </el-collapse-item>

      <el-collapse-item name="detail-misc">
        <template #title>
          <span class="ct">诊断明细</span>
          <span class="ct-sub">可用于对外核对口径</span>
        </template>
        <div class="misc-grid">
          <div><span>报告时间</span><b>{{ report?.generatedAt || '—' }}</b></div>
          <div><span>病历总数</span><b>{{ report?.dataset?.recordCount ?? 0 }}</b></div>
          <div><span>主诉写法种类</span><b>{{ report?.dataset?.chiefComplaintTemplates ?? 0 }}</b></div>
          <div><span>来自患者口语的记录</span><b>{{ report?.dataset?.recordsWithColloquialSymptom ?? 0 }}</b></div>
          <div><span>评分区间</span><b>{{ scoreRange }}</b></div>
          <div><span>平均分</span><b>{{ report?.score?.avg ?? '—' }}</b></div>
        </div>
        <p class="detail-note">{{ report?.disclaimer }}</p>
      </el-collapse-item>
    </el-collapse>

    <div class="foot">
      <el-button size="small" :loading="exporting" @click="handleExport">导出 CSV</el-button>
      <span class="tip">导出全部明细指标，便于存档或与他人核对口径</span>
    </div>
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
import { ref, computed, onMounted } from 'vue'
import PanelCard from '@/components/PanelCard.vue'
import { getStandardizationReport } from '@/api/stats'
import { saveBlob } from '@/utils/download'

const report = ref(null)
const loading = ref(false)
const exporting = ref(false)

const pct = (part, total) => (total ? `${((part / total) * 100).toFixed(1)}%` : '—')

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
  const extracted = new Map((d.coverage || []).map((c) => [c.field, c.total]))
  // field -> 词典类型 key 的映射从 coverage 与 dictQuality 的 label 对齐：
  // 两者都用同一份 label，直接按 label 建索引，避免在前端硬编码映射表
  const byLabel = new Map((d.dictQuality || []).map((x) => [x.label, x]))
  const candidates = (d.dictQuality || [])
    .filter((x) => x.termCount > 0 && (extracted.get(x.label) || 0) > 0)
  if (!candidates.length) return null
  // 未归一量 × 词表规模的组合：词表越小越该补，且优先补未归一多的那类
  const unmatchedOf = (label) => {
    const c = (d.coverage || []).find((x) => x.label === label)
    return c ? c.total - c.normalized : 0
  }
  return candidates
    .map((x) => ({ ...x, unmatched: unmatchedOf(x.label) }))
    .sort((a, b) => (b.unmatched - a.unmatched) || (a.termCount - b.termCount))[0]
})

// ---------------- 关键卡 ----------------
// 三张卡各回答一个业务问题：词典够不够、归一顺不顺、评分灵不灵
const kpis = computed(() => {
  const d = report.value
  if (!d) return []
  const gap = d.unmatched || {}
  const gapRate = gap.total ? gap.dictionaryGap / gap.total : 0
  const b = bottleneck.value
  const noCode = (d.dictQuality || []).filter((x) => x.codedCount === 0).length
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
    },
    {
      label: '国标编码',
      value: noCode === 0 ? '已覆盖' : `${noCode} 类缺`,
      note: noCode === 0 ? '词表已带编码' : '缺编码时术语无法与国标库对接',
      tone: noCode === 0 ? 'ok' : 'warn'
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

// ---------------- 一句话结论 ----------------
const headline = computed(() => {
  const d = report.value
  if (!d) return { icon: '·', tone: '', title: '正在读取…', desc: '' }
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

  if (u.dictionaryGap > 0) {
    const b = bottleneck.value
    list.push({
      title: b ? `补充${b.label}标准词表` : '补充标准词表',
      desc: b
        ? `按国家/行业标准术语集补录，不从现有数据反推。当前${b.label}只有 ${b.termCount} 条词，`
          + `补齐后可解决 ${b.unmatched} 条未归一；全部词表缺口合计 ${u.dictionaryGap} 条。`
        : `按国家/行业标准术语集补录，合计 ${u.dictionaryGap} 条实体因词表未收录而无法归一。`,
      owner: '词表',
      tagType: 'warning'
    })
  }
  if (u.misrouted > 0) {
    list.push({
      title: '修复脉象、舌象被当成症状',
      desc: `有 ${u.misrouted} 条「脉细数」「左尺无力」这类脉象要素被归进了症状，`
        + '会让症状归一率被拉低，也可能影响完整性判定。',
      owner: '抽取',
      tagType: 'warning'
    })
  }
  const noCode = (d.dictQuality || []).filter((x) => x.codedCount === 0).length
  if (noCode > 0) {
    list.push({
      title: '补录国标编码',
      desc: `${noCode} 类词典尚无国标编码。编码是术语与国标/医保/ICD 对接的钥匙，`
        + '缺编码时词典只能用于本系统内部匹配。',
      owner: '词表',
      tagType: 'info'
    })
  }
  const s = d.score || {}
  if (s.total && s.capped / s.total > 0.3) {
    list.push({
      title: '放宽质控扣分上限',
      desc: `${pct(s.capped, s.total)} 的病历「未归一术语」一项被扣满上限，`
        + '后续再扣也不增加扣分，导致分数失去区分度。建议按未归一数量分段扣分。',
      owner: '规则',
      tagType: 'warning'
    })
  }
  const selfAlias = (d.dictQuality || []).reduce((a, b) => a + (b.selfAliasCount || 0), 0)
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
      title: '减少抽取残词',
      desc: `有 ${u.fragment} 条未归一实体是抽取时截断的残字（如单字），`
        + '属抽取质量问题，与词表无关。',
      owner: '抽取',
      tagType: 'info'
    })
  }
  return list
})

// ---------------- 明细 ----------------
const coverageRows = computed(() =>
  (report.value?.coverage || []).map((c) => ({
    label: c.label,
    total: c.total,
    normalized: c.normalized,
    rate: c.total ? pct(c.normalized, c.total) : '未抽取'
  }))
)

const scoreRange = computed(() => {
  const s = report.value?.score
  return s && s.total ? `${s.min} ~ ${s.max}` : '—'
})

const loadReport = async () => {
  loading.value = true
  try {
    const res = await getStandardizationReport()
    report.value = res.data || null
  } catch {
    // 拦截器已提示，这里不叠加泛化文案
    report.value = null
  } finally {
    loading.value = false
  }
}

/** 导出 CSV：BOM 头让 Excel 正确识别 UTF-8，否则中文全是乱码 */
const handleExport = () => {
  const r = report.value
  if (!r) return
  exporting.value = true
  try {
    const rows = [['区块', '指标', '数值', '说明']]
    r.dictQuality.forEach((d) => {
      rows.push(['词典质量', `${d.label} 词条数`, d.termCount, d.source || ''])
      rows.push(['词典质量', `${d.label} 有别名`, d.aliasedCount, `共 ${d.termCount} 条`])
      rows.push(['词典质量', `${d.label} 有国标编码`, d.codedCount, `共 ${d.termCount} 条`])
      rows.push(['词典质量', `${d.label} 别名重复`, d.selfAliasCount, '别名含标准词本身会自命中'])
    })
    rows.push(['词典质量', '同名术语跨词典', r.crossTypeDuplicates.length, '同一词出现在多本词典'])
    r.coverage.forEach((c) => {
      rows.push(['归一情况', `${c.label} 归一率`, c.total ? pct(c.normalized, c.total) : '未抽取',
        `抽取 ${c.total} 条`])
    })
    const u = r.unmatched
    rows.push(['未归一构成', '词表未收录', u.dictionaryGap, '责任：词表'])
    rows.push(['未归一构成', '脉/舌被当成症状', u.misrouted, '责任：抽取'])
    rows.push(['未归一构成', '体征被当成症状', u.physicalSign, '责任：抽取'])
    rows.push(['未归一构成', '抽取残词', u.fragment, '责任：抽取'])
    rows.push(['未归一构成', '合计', u.total, ''])
    const s = r.score
    rows.push(['评分', '扣分相同占比', pct(s.capped, s.total), `平均分 ${s.avg}，区间 ${s.min}~${s.max}`])
    rows.push(['数据集', '病历总数', r.dataset.recordCount, ''])
    rows.push(['数据集', '主诉写法种类', r.dataset.chiefComplaintTemplates, '远小于病历数说明是测试数据'])
    rows.push(['数据集', '来自患者口语', r.dataset.recordsWithColloquialSymptom, '口语不是标准症状词'])
    rows.push(['声明', r.disclaimer, '', r.generatedAt])

    const csv = rows
      .map((x) => x.map((c) => `"${String(c ?? '').replace(/"/g, '""')}"`).join(','))
      .join('\r\n')
    saveBlob(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }),
      `标准化质量报告-${r.generatedAt.replace(/[-: ]/g, '')}.csv`)
  } finally {
    exporting.value = false
  }
}

onMounted(loadReport)
</script>

<style scoped>
/* 数据来源提示：轻量，不拦截阅读 */
.src-note {
  display: flex;
  gap: var(--sp-2);
  align-items: flex-start;
  padding: var(--sp-2) var(--sp-3);
  margin-bottom: var(--sp-3);
  background: var(--surface-sub);
  border-radius: 4px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}
.src-icon {
  flex: 0 0 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  border-radius: 50%;
  background: var(--text-sub);
  color: var(--surface);
  font-size: 11px;
  font-weight: 600;
}
/* 结论条：一句话把「该做什么」说清 */
.headline {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: var(--sp-3);
  border-radius: 4px;
  border-left: 3px solid var(--ink-mid);
  background: var(--ink-light);
}
.headline.warn {
  border-left-color: var(--ochre);
  background: var(--ochre-surface);
}
.headline.ok {
  border-left-color: var(--success, #3a7d44);
  background: var(--surface-sub);
}
.hl-icon {
  flex: 0 0 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 700;
  color: var(--surface);
  background: var(--ink-mid);
}
.headline.warn .hl-icon { background: var(--ochre); }
.headline.ok .hl-icon { background: var(--success, #3a7d44); }
.hl-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.hl-desc {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-sub);
}
/* 关键卡 */
.kpi-row {
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: var(--sp-4);
}
.kpi {
  flex: 1 1 200px;
  min-width: 190px;
  padding: var(--sp-3);
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--surface);
}
.kpi.warn { border-left: 3px solid var(--ochre); }
.kpi.ok { border-left: 3px solid var(--success, #3a7d44); }
.kpi-label {
  font-size: 12.5px;
  color: var(--text-sub);
  margin-bottom: 2px;
}
.kpi-value {
  font-size: 21px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.3;
}
.kpi-note {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-sub);
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
  font-size: 12px;
  font-weight: 600;
}
.todo-body { flex: 1 1 auto; min-width: 0; }
.todo-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.todo-desc {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}
.empty-tip {
  padding: var(--sp-3) 0;
  font-size: 13px;
  color: var(--text-sub);
}
/* 明细折叠 */
.detail { margin-bottom: var(--sp-3); }
.ct { font-size: 13.5px; font-weight: 600; color: var(--ink); }
.ct-sub { margin-left: var(--sp-2); font-size: 12px; color: var(--text-sub); font-weight: 400; }
.detail-note {
  margin-top: var(--sp-2);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}
.misc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--sp-2) var(--sp-4);
  font-size: 12.5px;
}
.misc-grid span { color: var(--text-sub); margin-right: 6px; }
.misc-grid b { color: var(--ink); font-weight: 600; }
.warn { color: var(--ochre); font-weight: 600; }
.foot {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}
</style>