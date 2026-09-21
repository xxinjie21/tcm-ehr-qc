<template>
  <el-dialog
    v-model="visible"
    class="llm-dialog"
    title="导入 LLM"
    width="min(900px, 94vw)"
    top="6vh"
    :close-on-click-modal="false"
    @open="loadConfig"
  >
    <p class="llm-tip">
      配置运行时生效的模型通道：<b>ollama</b> 走本机、无需 API Key；<b>openai</b> 兼容三方网关，
      改接口地址即可接入 DeepSeek / 通义 / 智谱等。保存后立即生效，<b>无需重启</b>。
    </p>

    <!-- 左右两栏（UX-65 修订）：原先 7 个表单项纵向堆叠，窗口一矮就要滚动才能
         填到最后一项、也看不到探测结果。改为左「通道与接入」/ 右「模型参数 + 探测结果」，
         一屏内可填完并即时看到连接是否可用 -->
    <div v-loading="loading" class="llm-body">
      <div class="llm-col">
        <div class="col-hd">通道与接入</div>
        <el-form label-position="top" class="llm-form">
          <el-form-item label="启用 LLM">
            <el-switch v-model="form.enabled" />
            <span class="llm-hint">关闭时 AI 解读 / 助手 / 复核意见全部回退规则输出</span>
          </el-form-item>

          <el-form-item label="通道">
            <el-radio-group v-model="form.provider">
              <el-radio value="ollama">ollama（本机）</el-radio>
              <el-radio value="openai">openai（含兼容三方）</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="接口地址">
            <el-input
              v-model="form.baseUrl"
              :placeholder="form.provider === 'ollama' ? 'http://localhost:11434' : 'https://api.openai.com/v1'"
            />
            <span class="llm-hint">留空用通道默认地址</span>
          </el-form-item>

          <el-form-item v-if="form.provider === 'openai'" label="API Key">
            <el-input
              v-model="form.apiKey"
              type="password"
              show-password
              :placeholder="config.apiKeySet ? `已配置 ${config.apiKeyMask}，留空表示不修改` : '必填'"
            />
            <span class="llm-hint">密钥只保存在服务端内存，不会写入配置文件或前端存储</span>
          </el-form-item>
        </el-form>
      </div>

      <div class="llm-col">
        <div class="col-hd">模型参数</div>
        <el-form label-position="top" class="llm-form">
          <el-form-item label="模型名">
            <el-input
              v-model="form.model"
              :placeholder="form.provider === 'ollama' ? '如 qwen2.5:7b' : '如 gpt-4o-mini'"
            />
            <span class="llm-hint">留空用通道默认模型</span>
          </el-form-item>

          <div class="llm-row">
            <el-form-item label="温度">
              <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" />
            </el-form-item>
            <el-form-item label="超时（毫秒）">
              <el-input-number v-model="form.timeout" :min="1000" :max="300000" :step="1000" />
            </el-form-item>
          </div>
        </el-form>

        <!-- 探测结果就地展示，不用额外弹窗（UX-66 口径） -->
        <div v-if="testResult" class="llm-result" :class="testResult.ok ? 'is-ok' : 'is-bad'">
          <template v-if="testResult.ok">
            连接正常 · {{ testResult.provider }} / {{ testResult.model }} · 往返 {{ testResult.latencyMs }} ms
            <div v-if="testResult.reply" class="llm-reply">模型回复：{{ testResult.reply }}</div>
          </template>
          <template v-else>{{ testResult.message }}</template>
        </div>
        <div v-else class="llm-result is-idle">
          点左下「测试连接」可就地看到结果，不必先保存
        </div>
      </div>
    </div>

    <template #footer>
      <el-button :loading="testing" @click="handleTest">测试连接</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存并生效</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getLlmConfig, updateLlmConfig, testLlmConfig } from '@/api/llm'
import { apiErrorMessage } from '@/utils/request'

const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['saved'])

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

// 当前生效配置（只读展示用，含掩码）
const config = reactive({
  apiKeySet: false,
  apiKeyMask: '',
  available: false
})

// 表单：apiKey 恒为空 = 不修改；用户输入新值才提交
const form = reactive({
  enabled: false,
  provider: 'ollama',
  baseUrl: '',
  apiKey: '',
  model: '',
  temperature: 0.2,
  timeout: 60000
})

async function loadConfig() {
  loading.value = true
  testResult.value = null
  try {
    const { data } = await getLlmConfig()
    form.enabled = !!data.enabled
    form.provider = data.provider || 'ollama'
    form.baseUrl = data.baseUrl || ''
    form.apiKey = ''
    form.model = data.model || ''
    form.temperature = data.temperature ?? 0.2
    form.timeout = data.timeout || 60000
    config.apiKeySet = !!data.apiKeySet
    config.apiKeyMask = data.apiKeyMask || ''
    config.available = !!data.available
  } finally {
    loading.value = false
  }
}

function payload() {
  return {
    enabled: form.enabled,
    provider: form.provider,
    baseUrl: form.baseUrl,
    apiKey: form.apiKey,
    model: form.model,
    temperature: form.temperature,
    timeout: form.timeout
  }
}

async function handleTest() {
  testing.value = true
  testResult.value = null
  try {
    const { data } = await testLlmConfig(payload())
    testResult.value = { ok: true, ...data }
  } catch (e) {
    // request.js 已弹过 toast；这里保留就地结果，避免用户滚动后找不到原因。
    // 必须取后端 msg：探测失败时后端回 502 + code=1009，该消息已脱敏、契约上就是给用户看的；
    // 直接用 e.message 会退化成 axios 的 "Request failed with status code 502"，等于把原因丢掉。
    testResult.value = { ok: false, message: apiErrorMessage(e, '连接失败') }
  } finally {
    testing.value = false
  }
}

async function handleSave() {
  saving.value = true
  try {
    await updateLlmConfig(payload())
    ElMessage.success('LLM 配置已生效')
    visible.value = false
    emit('saved')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.llm-tip {
  margin: 0 0 14px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--ochre);
  background: var(--ochre-light);
  font-size: 12px;
  line-height: 1.8;
  color: #6b5a44;
}
.llm-tip b {
  color: var(--ink);
  font-weight: normal;
}

/* 左右两栏：窄屏（<720px）自动收为单栏，不产生横向滚动 */
.llm-body {
  display: flex;
  gap: 26px;
  align-items: flex-start;
}
.llm-col {
  flex: 1;
  min-width: 0;
}
.llm-col + .llm-col {
  border-left: 1px solid var(--line);
  padding-left: 26px;
}
@media (max-width: 720px) {
  .llm-body {
    flex-direction: column;
    gap: 0;
  }
  .llm-col + .llm-col {
    border-left: 0;
    padding-left: 0;
  }
}
.col-hd {
  font-size: 12.5px;
  font-weight: bold;
  color: var(--ink);
  padding-bottom: 6px;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.llm-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.llm-form :deep(.el-form-item__label) {
  font-size: 12px;
  color: var(--text-sub);
  padding-bottom: 2px;
  line-height: 1.6;
}
.llm-hint {
  font-size: 11.5px;
  color: var(--text-sub);
  line-height: 1.7;
}
.llm-row {
  display: flex;
  gap: 16px;
}
.llm-row :deep(.el-form-item) {
  flex: 1;
  min-width: 0;
}
.llm-result {
  margin-top: 2px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  font-size: 12px;
  line-height: 1.8;
  word-break: break-all;
}
.llm-result.is-ok {
  border-left: 3px solid var(--ink-mid);
  background: var(--ink-light);
  color: var(--ink);
}
.llm-result.is-bad {
  border-left: 3px solid var(--danger);
  background: #fdf3f1;
  color: #8a3d33;
}
.llm-result.is-idle {
  border-style: dashed;
  color: var(--text-sub);
}
.llm-reply {
  color: var(--text-sub);
}
</style>
