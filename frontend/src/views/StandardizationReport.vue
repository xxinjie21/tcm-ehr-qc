<template>
  <div class="std-report">
    <!-- 数据来源声明放在最上面：这份报告里的「归一率」来自当前数据集，
         不加声明会被当成真实病历上的准确率对外引用。服务端返回什么就显示什么，
         不在前端二次加工措辞。 -->
    <el-alert
      v-if="report?.disclaimer"
      type="warning"
      :closable="false"
      show-icon
      :title="report.disclaimer"
      class="disclaimer"
    />

    <PanelCard title="甲类 · 标准符合度">
      <template #extra>
        <span class="tip">换数据集也不变，这一层才是词表质量的目标口径</span>
      </template>
      <el-table :data="report?.dictQuality || []" border stripe size="small">
        <el-table-column prop="label" label="类型" width="100" />
        <el-table-column prop="termCount" label="词条数" width="90" align="right" />
        <el-table-column label="有编码" width="140" align="right">
          <template #default="{ row }">
            <span :class="{ 'no-code': row.codedCount === 0 }">
              {{ row.codedCount }}（{{ pct(row.codedCount, row.termCount) }}）
            </span>
          </template>
        </el-table-column>
        <el-table-column label="有别名" width="140" align="right">
          <template #default="{ row }">{{ row.aliasedCount }}（{{ pct(row.aliasedCount, row.termCount) }}）</template>
        </el-table-column>
        <el-table-column label="自别名" width="120" align="right">
          <template #default="{ row }">
            <span :class="{ 'no-code': row.selfAliasCount > 0 }">{{ row.selfAliasCount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" min-width="180" show-overflow-tooltip />
      </el-table>

      <div v-if="report?.crossTypeDuplicates?.length" class="dup-tip">
        跨类重名 {{ report.crossTypeDuplicates.length }} 个术语出现在多本词典
        （类型判定会有歧义，建议只保留一处）：
        <span class="mono">{{ report.crossTypeDuplicates.slice(0, 12).join('、') }}</span>
        <template v-if="report.crossTypeDuplicates.length > 12">…</template>
      </div>
    </PanelCard>

    <PanelCard title="乙类 · 数据集覆盖度">
      <template #extra>
        <span class="tip">只用于验证词表是否建全，不代表真实病历性能</span>
      </template>

      <div class="metric-row">
        <StatCard label="可归一实体归一率" :value="normalizableRate" />
        <StatCard label="质控封顶率" :value="cappedRate" :tone="cappedTone" />
        <StatCard label="片段率" :value="fragmentRate" />
      </div>

      <h4 class="sub-title">各类实体归一情况</h4>
      <el-table :data="coverageRows" border size="small">
        <el-table-column prop="label" label="类型" width="100" />
        <el-table-column prop="total" label="抽取数" width="100" align="right" />
        <el-table-column prop="normalized" label="已归一" width="100" align="right" />
        <el-table-column label="归一率" min-width="120">
          <template #default="{ row }">{{ pct(row.normalized, row.total) }}</template>
        </el-table-column>
      </el-table>

      <h4 class="sub-title">症状未归一实体的构成</h4>
      <el-table :data="unmatchedRows" border size="small">
        <el-table-column prop="label" label="类别" min-width="200" />
        <el-table-column prop="count" label="条数" width="90" align="right" />
        <el-table-column prop="owner" label="责任方" width="120" />
        <el-table-column label="占未归一" width="110" align="right">
          <template #default="{ row }">{{ pct(row.count, total) }}</template>
        </el-table-column>
      </el-table>

      <div class="dataset-note">
        当前数据集共 {{ report?.dataset?.recordCount ?? 0 }} 条病历，
        主诉去数字后仅 {{ report?.dataset?.chiefComplaintTemplates ?? 0 }} 种模板
        <template v-if="isTemplated">（模板数远小于病历数，说明是模板生成的合成数据）</template>；
        其中 {{ report?.dataset?.recordsWithColloquialSymptom ?? 0 }} 条的症状来自患者口语字段
        ——口语不是标准症状词，不计入「词表缺口」。
      </div>
    </PanelCard>

    <PanelCard title="导出">
      <el-button :loading="exporting" @click="handleExport">导出 CSV</el-button>
      <span class="tip">导出甲类与乙类全部指标，便于存档或与他人核对口径</span>
    </PanelCard>
  </div>
</template>

<script setup>
// 标准化质量报告（批次 24）。
// 页面刻意把「甲类·标准符合度」与「乙类·数据集覆盖度」分成两块呈现：
// 两者的性质完全不同，混在一张表里会让人把模板数据的归一率当成真实准确率。
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PanelCard from '@/components/PanelCard.vue'
import StatCard from '@/components/StatCard.vue'
import { getStandardizationReport } from '@/api/stats'
import { saveBlob } from '@/utils/download'

const report = ref(null)
const loading = ref(false)
const exporting = ref(false)

const pct = (part, total) => (total ? `${((part / total) * 100).toFixed(1)}%` : '—')

const total = computed(() => report.value?.unmatched?.total || 0)

const normalizableRate = computed(() => {
  const n = report.value?.normalizable
  return n && n.denominator ? pct(n.numerator, n.denominator) : '—'
})

const cappedRate = computed(() => {
  const s = report.value?.score
  return s && s.total ? pct(s.capped, s.total) : '—'
})

// 封顶率高说明多数病历扣分相同、评分失去区分度，用告警色提示
const cappedTone = computed(() => {
  const s = report.value?.score
  return s && s.total && s.capped / s.total > 0.3 ? 'ochre' : ''
})

const fragmentRate = computed(() => {
  const u = report.value?.unmatched
  return u && u.total ? pct(u.fragment, u.total) : '—'
})

const isTemplated = computed(() => {
  const d = report.value?.dataset
  return d && d.recordCount > 0 && d.chiefComplaintTemplates / d.recordCount <= 0.2
})

const coverageRows = computed(() =>
  (report.value?.coverage || []).map((c) => ({
    label: c.label,
    total: c.total,
    normalized: c.normalized
  }))
)

// 未归一四类：责任方不同，页面上直接标出来，避免只看总数不知道该修哪
const unmatchedRows = computed(() => {
  const u = report.value?.unmatched
  if (!u) return []
  return [
    { label: '抽取碎片（残词）', count: u.fragment, owner: '抽取侧' },
    { label: '分类错放（脉/舌进了症状）', count: u.misrouted, owner: '抽取侧' },
    { label: '体征错放（压痛等进了症状）', count: u.physicalSign, owner: '抽取侧' },
    { label: '词表缺口（标准词未收录）', count: u.dictionaryGap, owner: '词表侧' }
  ]
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
  if (!report.value) return
  exporting.value = true
  try {
    const rows = [['区块', '指标', '数值', '说明']]
    report.value.dictQuality.forEach((d) => {
      rows.push(['甲类·标准符合度', `${d.label} 词条数`, d.termCount, d.source || ''])
      rows.push(['甲类·标准符合度', `${d.label} 有编码`, d.codedCount, `共 ${d.termCount} 条`])
      rows.push(['甲类·标准符合度', `${d.label} 自别名`, d.selfAliasCount, '别名与标准词相同会自命中'])
    })
    rows.push(['甲类·标准符合度', '跨类重名', report.value.crossTypeDuplicates.length, '类型判定歧义'])
    report.value.coverage.forEach((c) => {
      rows.push(['乙类·数据集覆盖度', `${c.label} 归一率`, pct(c.normalized, c.total), `抽取 ${c.total} 条`])
    })
    const u = report.value.unmatched
    rows.push(['乙类·数据集覆盖度', '未归一·抽取碎片', u.fragment, '责任：抽取侧'])
    rows.push(['乙类·数据集覆盖度', '未归一·分类错放', u.misrouted, '责任：抽取侧'])
    rows.push(['乙类·数据集覆盖度', '未归一·体征错放', u.physicalSign, '责任：抽取侧'])
    rows.push(['乙类·数据集覆盖度', '未归一·词表缺口', u.dictionaryGap, '责任：词表侧'])
    const s = report.value.score
    rows.push(['乙类·数据集覆盖度', '质控封顶率', pct(s.capped, s.total), `平均分 ${s.avg}`])
    rows.push(['乙类·数据集覆盖度', '数据集模板数', report.value.dataset.chiefComplaintTemplates, `共 ${report.value.dataset.recordCount} 条病历`])
    rows.push(['声明', report.value.disclaimer, '', report.value.generatedAt])

    const csv = rows
      .map((r) => r.map((c) => `"${String(c ?? '').replace(/"/g, '""')}"`).join(','))
      .join('\r\n')
    saveBlob(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }),
      `standardization-report-${report.value.generatedAt.replace(/[-: ]/g, '')}.csv`)
  } finally {
    exporting.value = false
  }
}

onMounted(loadReport)
</script>

<style scoped>
.disclaimer {
  margin-bottom: var(--sp-3);
}
.sub-title {
  margin: var(--sp-4) 0 var(--sp-2);
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.metric-row {
  display: flex;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
/* 自别名 / 无编码：数字本身不是坏事，但「自别名」是缺陷，用提示色标出来 */
.no-code {
  color: var(--ochre);
  font-weight: 600;
}
.dup-tip {
  margin-top: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  background: var(--ochre-surface);
  border-radius: 4px;
  font-size: 12.5px;
  line-height: 1.7;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}
.dataset-note {
  margin-top: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  background: var(--surface-sub);
  border-radius: 4px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-sub);
}
</style>