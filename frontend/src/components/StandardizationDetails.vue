<template>
  <!-- 标准化报告第三屏：明细默认收起。业务用户通常不需要逐类看，展开即可。
       从 StandardizationReport.vue 抽出：纯展示，数据全部由父页传入。 -->
      <el-collapse class="detail">
        <el-collapse-item name="month">
          <template #title>
            <span class="ct">按月份看</span>
            <span class="ct-sub">补词表后逐月对比，看新词表吃到了哪些数据</span>
          </template>
          <el-table :data="report?.byMonth || []" border size="small" max-height="320">
            <el-table-column prop="month" label="月份" width="110" />
            <el-table-column prop="records" label="病历数" width="90" align="right" />
            <el-table-column label="症状归一率" min-width="120">
              <template #default="{ row }">
                <span v-if="row.symptomRate">{{ row.symptomRate }}</span>
                <span v-else class="tip">未抽取</span>
              </template>
            </el-table-column>
            <el-table-column prop="dictionaryGap" label="词表缺口" width="100" align="right" />
            <el-table-column prop="avgScore" label="平均分" width="90" align="right" />
            <el-table-column label="扣分封顶" width="100" align="right">
              <template #default="{ row }">
                <span :class="{ warn: row.capped > 0 }">{{ row.capped }}</span>
              </template>
            </el-table-column>
          </el-table>
          <p class="detail-note">
            「词表缺口」是该月未归一里属于「标准词但词表没有收录」的部分 ——
            补词表能直接解决的就是它。若某个月缺口明显比别的月多，多半是那个月的
            数据还没重跑过解析。
          </p>
        </el-collapse-item>

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
          <el-table-column prop="termCount" label="词条数" width="90" align="right" />
          <el-table-column label="归一率" min-width="110">
            <template #default="{ row }">{{ row.rate }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="150">
            <template #default="{ row }">
              <span v-if="row.stale" class="stale-tag">解析早于词表，需重跑</span>
            </template>
          </el-table-column>
        </el-table>
      </el-collapse-item>

      <el-collapse-item name="detail-misc">
        <template #title>
          <span class="ct">诊断明细</span>
          <span class="ct-sub">可用于对外核对口径</span>
        </template>
        <!-- 体验修复（2026-10-05）：此前页面没有任何「本页不自动刷新」的说明，
             用户不知道报告何时更新、该点哪个按钮才生效。 -->
        <div style="margin-bottom:8px; padding:var(--sp-2) var(--sp-3); font-size: var(--fs-xs); line-height:1.7;
                    color:var(--text); background:var(--ochre-light); border-left:3px solid var(--ochre); border-radius:4px;">
          <b>本页不会自动刷新。</b>怎么让它更新：
          <b>补了词表</b> → 点「重跑『解析 + 质控』」（新词表只对重跑过解析的病历生效）；
          <b>只改质控规则</b> → 点「立即重跑质控」即可；
          <b>只是想再看一遍</b> → 点右上角「刷新」。
          下方列表里带「解析早于词表，需重跑」标记的行，就是还没吃到新词表的病历。
        </div>
        <div class="misc-grid">
                    <FreshnessTag :time="report?.generatedAt" :stale="hasStaleRows"
                        reason="部分病历的解析早于词表，需重跑" />
          <!-- 对标 A5 的口径见后端 StandardizationReportVO.sourceVersion：
               本页**刻意不展示**它 —— 这页的读者是质控科业务用户（见文件头设计取向），
               而它的值是一串 type:hash 拼接（8 类各一段），属于技术口径，按要求"只在本文件内部使用"。
               后端字段保留：工程师排查「换词表前后数字变化」时仍可取到。 -->
          <div>
            <span>质控完成</span>
            <b>{{ qcScored }} / {{ qcTotal }}{{ qcLast ? `（${qcLast}）` : '' }}</b>
          </div>
          <div><span>病历总数</span><b>{{ report?.dataset?.recordCount ?? 0 }}</b></div>
          <div><span>主诉写法种类</span><b>{{ report?.dataset?.chiefComplaintTemplates ?? 0 }}</b></div>
          <div><span>来自患者口语的记录</span><b>{{ report?.dataset?.recordsWithColloquialSymptom ?? 0 }}</b></div>
          <div><span>评分区间</span><b>{{ scoreRange }}</b></div>
          <div><span>平均分</span><b>{{ report?.score?.avg ?? '—' }}</b></div>
        </div>
        <p class="detail-note">{{ report?.disclaimer }}</p>
      </el-collapse-item>
    </el-collapse>
</template>

<script setup>
import FreshnessTag from '@/components/FreshnessTag.vue'

defineProps({
  report: { type: Object, default: null },
  coverageRows: { type: Array, default: () => [] },
  hasStaleRows: { type: Boolean, default: false },
  qcScored: { type: Number, default: 0 },
  qcTotal: { type: Number, default: 0 },
  qcLast: { type: String, default: '' },
  scoreRange: { type: String, default: '' }
})
</script>

<style scoped>
/* 明细折叠 */
.detail { margin-bottom: var(--sp-3); }
.ct { font-size: var(--fs-base); font-weight: 600; color: var(--ink); }
.ct-sub { margin-left: var(--sp-2); font-size: var(--fs-xs); color: var(--text-sub); font-weight: 400; }
.detail-note {
  margin-top: var(--sp-2);
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub);
}
.misc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--sp-2) var(--sp-4);
  font-size: var(--fs-xs);
}
.misc-grid span { color: var(--text-sub); margin-right: 6px; }
.misc-grid b { color: var(--ink); font-weight: 600; }
.warn { color: var(--ochre); font-weight: 600; }
/* 「解析早于词表」标记：这一种补词表无效，得重跑解析，所以要显式标出来 */
.stale-tag {
  display: inline-block;
  padding: var(--sp-1) var(--sp-2);
  border-radius: 3px;
  background: var(--ochre-surface);
  color: var(--ochre);
  font-size: var(--fs-xs);
  font-weight: 600;
}
</style>
