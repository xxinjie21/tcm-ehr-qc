<template>
  <!-- AI 质控解读卡片：点「生成解读」后调后端，结果 = 规则预检结论 + LLM 叙述。
       未生成时只显示引导文案，不预先渲染空壳 -->
  <section class="ai-interpret">
    <div class="aii-hd">
      <span class="aii-title">AI 质控解读</span>
      <!-- 来源标签：明示这份解读是「规则 + AI」还是「仅规则」，
           不让用户把规则结论误当成模型产出 -->
      <span v-if="result" class="aii-src">{{ result.llmAvailable ? '规则 + AI 叙述' : '规则（LLM 未启用）' }}</span>
      <el-button
        class="aii-btn"
        size="small"
        type="primary"
        :loading="loading"
        :disabled="!recordId"
        @click="run"
      >{{ result ? '重新生成' : '生成解读' }}</el-button>
    </div>

    <!-- 骨架屏（约1秒模拟分析） -->
    <div v-if="loading" class="aii-skeleton">
      <div v-for="i in 4" :key="i" class="sk-line" :style="{ width: 92 - i * 12 + '%' }" />
      <div class="sk-grid">
        <div v-for="i in 4" :key="'g' + i" class="sk-box" />
      </div>
    </div>

    <template v-else-if="result">
      <!-- 要点摘要（LLM 可用时） -->
      <div v-if="result.summary" class="aii-summary">
        <!-- 只读展示，不是表单标签：用 span 避免出现无关联控件的 <label>（UX-34） -->
        <div class="sum-item"><span class="sum-key">主诉</span><span>{{ result.summary.chiefComplaint || '—' }}</span></div>
        <div class="sum-item"><span class="sum-key">诊断</span><span>{{ result.summary.diagnosis || '—' }}</span></div>
        <div class="sum-item"><span class="sum-key">辨证</span><span>{{ result.summary.syndrome || '—' }}</span></div>
        <div class="sum-item"><span class="sum-key">方药</span><span>{{ result.summary.prescription || '—' }}</span></div>
      </div>

      <!-- 叙述 -->
      <p class="aii-narrative">{{ result.answer }}</p>

      <!-- 规则结论 -->
      <div class="aii-blocks">
        <!-- 完整性 = 全部结构化字段（21 项）的覆盖情况，分母是字段总数 -->
        <div class="aii-block">
          <div class="blk-title">完整性（{{ result.completeness.present }}/{{ result.completeness.total }}）</div>
          <div v-if="result.completeness.missing.length" class="chips">
            <span v-for="m in result.completeness.missing" :key="m" class="chip miss">{{ m }}</span>
          </div>
          <div v-else class="blk-ok">21 字段完整</div>
        </div>

        <!-- 核心字段 = 必填的少数几项（不含全部 21 项），只有两处都没有才算缺失；
             与上一块的「完整性」分母不同，两者数值不一致属正常 -->
        <div class="aii-block">
          <div class="blk-title">缺项重点（核心字段）</div>
          <div v-if="result.coreMissing.length" class="chips">
            <span v-for="m in result.coreMissing" :key="m" class="chip core">{{ m }}</span>
          </div>
          <div v-else class="blk-ok">核心字段齐全</div>
        </div>

        <!-- 归一命中按三级判定分档计数；标签文案取自 utils/structured.js（唯一副本） -->
        <div class="aii-block">
          <div class="blk-title">归一命中（共 {{ result.normHits.total }} 处）</div>
          <div class="chips">
            <span class="chip exact">{{ LEVEL_TINY[1] }} {{ result.normHits.exact }}</span>
            <span class="chip contain">{{ LEVEL_TINY[2] }} {{ result.normHits.contain }}</span>
            <span class="chip fuzzy">{{ LEVEL_TINY[3] }} {{ result.normHits.fuzzy }}</span>
          </div>
        </div>

        <div class="aii-block">
          <div class="blk-title">关键提示</div>
          <ul v-if="result.keyHints.length" class="hints">
            <li v-for="h in result.keyHints" :key="h">{{ h }}</li>
          </ul>
          <div v-else class="blk-ok">无</div>
        </div>
      </div>

      <div class="aii-disclaimer">{{ result.disclaimer }}</div>
    </template>

    <div v-else class="aii-tip">点击「生成解读」，由规则预检出结论、AI 辅助叙述。</div>
  </section>
</template>

<script setup>
// AI 质控解读卡片：点「生成解读」后调后端取结果。
// 结果 = 规则预检结论（完整性 / 缺项 / 归一命中 / 关键提示）+ LLM 叙述；
// LLM 未启用时仍出规则部分，由标题旁的来源标签标明。
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { aiInterpret } from '@/api/ai'
import { LEVEL_TINY } from '@/utils/structured'

const props = defineProps({
  // 当前病历 ID；为空时「生成解读」按钮禁用
  recordId: { type: String, default: '' }
})

// loading 驱动骨架屏；result 为 null 表示「尚未生成」
const loading = ref(false)
const result = ref(null)

// 生成解读：请求规则预检 + LLM 叙述。无 recordId 时直接拦下；
// 骨架屏与请求并行（至少展示 1 秒），切换病历会清空旧结果避免新旧同屏
const run = async () => {
  if (!props.recordId) {
    ElMessage.warning('缺少病历ID')
    return
  }
  // 1. 进入加载态并清空上一次结果，避免新旧解读同屏
  loading.value = true
  result.value = null
  try {
    // 骨架屏至少展示 1 秒（模拟分析过程），与请求并行
    const [res] = await Promise.all([
      aiInterpret({ recordId: props.recordId }),
      new Promise((r) => setTimeout(r, 1000))
    ])
    result.value = res.data
  } catch {
    // 拦截器已提示
  } finally {
    // 2. 无论成败都要收起骨架屏
    loading.value = false
  }
}

// 切换到另一条病历详情时重置，避免展示上一条的解读
watch(() => props.recordId, () => {
  result.value = null
})
</script>

<style scoped>
/* 卡片容器：浅纸色底 + 细边框，与详情弹窗内其他分区保持同一视觉层级 */
.ai-interpret {
  margin-top: 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px 16px;
}
/* 标题行：标题 + 来源标签 + 右侧按钮 */
.aii-hd {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.aii-title {
  font-size: 13.5px;
  font-weight: bold;
  color: var(--ink);
  border-left: 3px solid var(--ink-mid);
  padding-left: 8px;
}
.aii-src {
  font-size: 11.5px;
  color: var(--text-sub);
}
/* margin-left:auto 把按钮推到标题行最右 */
.aii-btn {
  margin-left: auto;
}
/* 骨架屏：渐变扫光动画；行宽由模板按序号递减，模拟段落长短不一 */
.aii-skeleton .sk-line {
  height: 12px;
  border-radius: 3px;
  margin-bottom: 8px;
  background: linear-gradient(90deg, #efece3 25%, #e4e0d5 37%, #efece3 63%);
  background-size: 400% 100%;
  animation: sk 1.2s ease infinite;
}
.aii-skeleton .sk-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 12px;
}
.aii-skeleton .sk-box {
  height: 40px;
  border-radius: 4px;
  background: linear-gradient(90deg, #efece3 25%, #e4e0d5 37%, #efece3 63%);
  background-size: 400% 100%;
  animation: sk 1.2s ease infinite;
}
@keyframes sk {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}
/* 要点摘要：两列网格 */
.aii-summary {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 12px;
}
.sum-item {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 8px 10px;
  display: flex;
  gap: 8px;
}
/* flex-shrink:0 保证标签不被长文本挤窄 */
.sum-item .sum-key {
  font-size: 12px;
  color: var(--text-sub);
  flex-shrink: 0;
}
.sum-item span {
  font-size: 12.5px;
  color: var(--ink);
  word-break: break-all;
}
/* LLM 叙述正文：加宽行距便于长段阅读 */
.aii-narrative {
  font-size: 13px;
  line-height: 1.8;
  color: var(--ink);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 12px;
}
/* 四块规则结论：两列网格 */
.aii-blocks {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.aii-block {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 10px 12px;
}
.blk-title {
  font-size: 12.5px;
  font-weight: bold;
  color: var(--ink);
  margin-bottom: 8px;
}
/* 「无缺项 / 无提示」时的正向文案，用次级色与警示标签区分 */
.blk-ok {
  font-size: 12.5px;
  color: var(--ink-mid);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
/* 描边标签：用于归一命中分档；实心感留给「怎么来的」类标签 */
.chip {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 3px;
  border: 1px solid var(--line);
  background: var(--paper);
  color: var(--ink);
}
.chip.miss { color: var(--danger); border-color: #e6c9c3; }
.chip.core { color: var(--ochre); border-color: #e2d3b8; }
.chip.exact { color: var(--ink-mid); }
.chip.contain { color: var(--ochre); }
.chip.fuzzy { color: var(--danger); }
.hints {
  margin: 0;
  padding-left: 16px;
  font-size: 12.5px;
  color: var(--ink);
  line-height: 1.8;
}
/* 免责声明：右上分隔线，与正文拉开距离 */
.aii-disclaimer {
  margin-top: 12px;
  font-size: 11.5px;
  color: var(--text-sub);
  text-align: right;
  border-top: 1px dashed #ece8dc;
  padding-top: 8px;
}
.aii-tip {
  font-size: 12.5px;
  color: var(--text-sub);
}
/* 窄屏（<1200px）摘要与结论块由两列塌成一列 */
@media (max-width: 1200px) {
  .aii-summary,
  .aii-blocks {
    grid-template-columns: 1fr;
  }
}
</style>
