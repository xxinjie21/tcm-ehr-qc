<template>
  <!-- 标准化报告第三屏：明细默认收起。
       从 StandardizationReport.vue 抽出：纯展示，数据全部由父页传入。
       2026-10-15：删去「按月份看 / 各词典明细 / 各类术语归一情况」三项 —— 业务侧不需要；
       归一率已有上方 StandardizationCharts 图表，词条数与别名质量属词典建设指标。
       只留「诊断明细」，它承载 disclaimer（后端 StandardizationReportVO 要求页面上原样显示）。 -->
      <el-collapse class="detail">
      <el-collapse-item name="detail-misc">
        <template #title>
          <span class="ct">诊断明细</span>
          <span class="ct-sub">可用于对外核对口径</span>
        </template>
        <!-- 体验修复（2026-10-05）：此前页面没有任何「本页不自动刷新」的说明，
             用户不知道报告何时更新、该点哪个按钮才生效。 -->
        <div style="margin-bottom:8px; padding:var(--sp-2) var(--sp-3); font-size: var(--fs-xs); line-height:1.7;
                    color:var(--text); background:var(--ochre-light); border-left:3px solid var(--ochre); border-radius:4px;">
          <b>本页不会自动刷新。</b>补了词表 → 重跑「解析 + 质控」；只改质控规则 → 「立即重跑质控」；
          只是想再看一遍 → 点右上角「刷新」。若下方时效标签提示「解析早于词表」，说明有病历还没吃到新词表。
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
/* P1-6：折叠展开/收起此前是 EP 默认的 ~350ms ease-in-out —— 超出全站最慢档
   （--dur-slow 280ms），同一次操作里「换页 200ms、换内容 200ms、展开 350ms」三种节奏。
   收口到 --dur-base + --ease-out，与页面/组件切换同档；分隔线用 --dur-fast。
   注意：只覆盖 transition 属性本身，动画曲线由 EP 的高度过渡驱动，行为不变。 */
.detail :deep(.el-collapse-item__wrap) {
  transition: height var(--dur-base) var(--ease-out);
}
.detail :deep(.el-collapse-item__header) {
  transition: border-bottom-color var(--dur-fast) var(--ease-out);
}
.ct { font-size: var(--fs-base); font-weight: 600; color: var(--ink); }
.ct-sub { margin-left: var(--sp-2); font-size: var(--fs-xs); color: var(--text-sub-strong); font-weight: 400; }
.detail-note {
  margin-top: var(--sp-2);
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-sub-strong);
}
.misc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--sp-2) var(--sp-4);
  font-size: var(--fs-xs);
}
.misc-grid span { color: var(--text-sub-strong); margin-right: 6px; }
.misc-grid b { color: var(--ink); font-weight: 600; }
</style>
