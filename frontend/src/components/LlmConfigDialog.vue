<template>
  <!-- 导入 LLM 配置弹窗（管理员）：切换运行时模型通道并就地探测连通性。
       每次打开都从服务端重读配置（@open）；禁止点遮罩关闭，避免填了一半误关。
       保存后立即生效，无需重启后端。 -->
  <el-dialog
    v-model="visible"
    class="llm-dialog"
    title="导入 LLM"
    width="min(900px, 94vw)"
    top="6vh"
    :close-on-click-modal="false"
    @open="loadConfig"
  >
    <!-- 两条告警互斥：读失败说明「表单里不是服务端现状」，存失败说明「改动没有生效」。
         都必须放在表单上方说清，否则用户会把旧值当成已保存的值 -->
    <p v-if="loadFailed" class="llm-warn">
      配置读取失败，下面显示的不是服务端的当前配置 —— 请关闭本弹窗后重开，或检查后端是否可用。
    </p>
    <p v-if="saveFailed" class="llm-warn">
      保存失败，本次修改<b>没有生效</b>；表单里是你刚填的值，可修正后重试。
    </p>

    <p class="llm-tip">
      配置运行时生效的模型通道：<b>ollama</b> 走本机、无需 API Key；<b>openai</b> 兼容三方网关，
      改接口地址即可接入 DeepSeek / 通义 / 智谱等。保存后立即生效，<b>无需重启</b>。
      通道与模型会保存、重启后仍回显；<b>API Key 不落盘，每次需重新填写</b>。
    </p>

    <!-- 左右两栏：原先 7 个表单项纵向堆叠，窗口一矮就要滚动才能
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

          <!-- API Key 只在 openai 通道出现；ollama 走本机不需要密钥 -->
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
            <!-- filterable + allow-create：既能从候选里挑，也能手输自定义模型名 -->
            <el-select
              v-model="form.model"
              filterable
              allow-create
              default-first-option
              :reserve-keyword="false"
              :placeholder="form.provider === 'ollama' ? '选择或输入，如 qwen2.5:7b' : '选择或输入，如 deepseek-chat'"
              style="width: 100%"
            >
              <el-option v-for="m in modelOptions" :key="m" :label="m" :value="m" />
            </el-select>
            <span class="llm-hint">从下拉选择，或直接输入自定义模型名</span>
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

        <!-- 探测结果就地展示，不用额外弹窗-->
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
// LLM 配置弹窗：左栏「通道与接入」，右栏「模型参数 + 探测结果」。
// 配置存在服务端内存（LlmConfigStore），保存即生效；API Key 不回显、不落盘。
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getLlmConfig, updateLlmConfig, testLlmConfig } from '@/api/llm'
import { apiErrorMessage } from '@/utils/request'

/** 常用模型（可选择或手输；避免用户不知道填什么） */
const MODEL_OPTIONS = {
  ollama: ['qwen2.5:7b', 'qwen2.5:14b', 'llama3.1', 'deepseek-r1:7b'],
  openai: ['deepseek-chat', 'deepseek-reasoner', 'gpt-4o-mini', 'qwen-plus', 'glm-4', 'moonshot-v1-8k']
}

// 显隐由父组件 v-model 控制；saved 事件用于通知父组件刷新当前通道显示
const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['saved'])

// loading 覆盖整个表单区（读配置时）；saving / testing 分别驱动对应按钮
const loading = ref(false)
const saving = ref(false)
/** 读配置失败 / 保存失败 —— 都不该让表单停在上一次的值而不说明 */
const loadFailed = ref(false)
const saveFailed = ref(false)
// testing 驱动「测试连接」按钮的 loading；testResult 为 null 表示尚未探测
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

/** 下拉候选：按当前通道给常用模型 */
const modelOptions = computed(() => MODEL_OPTIONS[form.provider] || MODEL_OPTIONS.openai)

// 每次打开弹窗都重读服务端配置，保证表单反映的是当前生效值
async function loadConfig() {
  // 1. 进入加载态，并清掉上一次的探测结果与失败标记
  loading.value = true
  testResult.value = null
  loadFailed.value = false
  try {
    // 2. 拉取服务端配置并逐项回填表单与只读展示
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
  } catch {
    // 原来只有 try/finally：读配置失败后表单还停在上一次的值，会被当成服务端现状
    loadFailed.value = true
  } finally {
    // 3. 无论成败都收起 loading
    loading.value = false
  }
}

// 组装提交体。apiKey 为空串 = 不修改（后端约定：空值或回显掩码都视为不修改），
// 所以这里不能做「空则不传」的裁剪，必须原样带上。
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

// 探测连通性：把当前表单值直接提交给后端试连，不要求先保存
async function handleTest() {
  testing.value = true
  // 1. 先清空上一次结果，避免旧的成功 / 失败信息与本次混淆
  testResult.value = null
  try {
    // 2. 成功：就地展示通道 / 模型 / 往返耗时与一句模型回复
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

// 保存并生效：成功后关窗，并通知父组件刷新
async function handleSave() {
  saving.value = true
  try {
    await updateLlmConfig(payload())
    ElMessage.success('LLM 配置已生效')
    // 1. 关窗 + 通知父组件（父组件据此更新当前通道标签）
    visible.value = false
    emit('saved')
  } catch {
    // 保存失败不关窗，用户改过的值还在；拦截器已按状态提示原因
    saveFailed.value = true
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* 顶部提示条：左竖线 + 浅色底；提示用 ochre、告警用 danger，两者一眼可辨 */
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
/* 读配置 / 保存失败时的告警条 */
.llm-warn {
  margin: 0 0 10px;
  padding: 9px 12px;
  border: 1px solid var(--danger);
  border-left: 3px solid var(--danger);
  background: #f8ece9;
  font-size: 12px;
  line-height: 1.8;
  color: var(--danger);
}

/* 提示条里的 <b> 只用来加重语义，不加粗，避免整段显得嘈杂 */
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
/* flex:1 + min-width:0：两栏等宽且可收缩，长模型名不会撑破一栏 */
.llm-col {
  flex: 1;
  min-width: 0;
}
/* 右栏左侧的分隔线（窄屏收单栏时在媒体查询里去掉） */
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
/* 栏标题：下边框与内容分隔 */
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
/* 字段下方的补充说明（比 label 更小、更弱） */
.llm-hint {
  font-size: 11.5px;
  color: var(--text-sub);
  line-height: 1.7;
}
/* 温度与超时并排一行 */
.llm-row {
  display: flex;
  gap: 16px;
}
.llm-row :deep(.el-form-item) {
  flex: 1;
  min-width: 0;
}
/* 探测结果：正常 / 失败 / 未探测三态，靠左边框与底色区分 */
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
/* 模型回复单独一行、用次级色，与「连接正常」这类结论区分开 */
.llm-reply {
  color: var(--text-sub);
}
</style>
