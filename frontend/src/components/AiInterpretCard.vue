<template>
  <section class="ai-interpret">
    <div class="aii-hd">
      <span class="aii-title">AI 质控解读</span>
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
        <div class="aii-block">
          <div class="blk-title">完整性（{{ result.completeness.present }}/{{ result.completeness.total }}）</div>
          <div v-if="result.completeness.missing.length" class="chips">
            <span v-for="m in result.completeness.missing" :key="m" class="chip miss">{{ m }}</span>
          </div>
          <div v-else class="blk-ok">21 字段完整</div>
        </div>

        <div class="aii-block">
          <div class="blk-title">缺项重点（核心字段）</div>
          <div v-if="result.coreMissing.length" class="chips">
            <span v-for="m in result.coreMissing" :key="m" class="chip core">{{ m }}</span>
          </div>
          <div v-else class="blk-ok">核心字段齐全</div>
        </div>

        <div class="aii-block">
          <div class="blk-title">归一命中（共 {{ result.normHits.total }} 处）</div>
          <div class="chips">
            <span class="chip exact">精确 {{ result.normHits.exact }}</span>
            <span class="chip contain">包含 {{ result.normHits.contain }}</span>
            <span class="chip fuzzy">模糊 {{ result.normHits.fuzzy }}</span>
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
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { aiInterpret } from '@/api/ai'

const props = defineProps({
  recordId: { type: String, default: '' }
})

const loading = ref(false)
const result = ref(null)

const run = async () => {
  if (!props.recordId) {
    ElMessage.warning('缺少病历ID')
    return
  }
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
    loading.value = false
  }
}

// 切换到另一条病历详情时重置，避免展示上一条的解读
watch(() => props.recordId, () => {
  result.value = null
})
</script>

<style scoped>
.ai-interpret {
  margin-top: 14px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 14px 16px;
}
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
.aii-btn {
  margin-left: auto;
}
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
.blk-ok {
  font-size: 12.5px;
  color: var(--ink-mid);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
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
@media (max-width: 1200px) {
  .aii-summary,
  .aii-blocks {
    grid-template-columns: 1fr;
  }
}
</style>
