<template>
  <div class="ai-assistant">
    <!-- 展开面板 -->
    <transition name="aii-fade">
      <section v-if="open" class="aii-panel">
        <header class="aii-hd">
          <span class="aii-dot" />
          <span class="aii-title">AI 助手</span>
          <span class="aii-sub">业务问答 · 规则兜底</span>
          <button class="aii-close" title="收起" @click="open = false">×</button>
        </header>

        <div class="aii-commands">
          <button
            v-for="c in COMMANDS"
            :key="c"
            class="cmd"
            :class="{ on: lastQuestion === c }"
            :disabled="loading"
            @click="ask(c)"
          >{{ c }}</button>
        </div>

        <div class="aii-body">
          <div v-if="!lastQuestion" class="aii-empty">
            你好，我是本系统的使用助手。可以问我数据统计、功能用法、业务流程、质控判定原因；技术实现问题请查看设计文档。
          </div>
          <template v-else>
            <div class="msg user">{{ lastQuestion }}</div>
            <div v-if="loading" class="msg ai loading">正在思考…</div>
            <div v-else-if="answer" class="msg ai">
              <p v-for="(line, i) in answerLines" :key="i">{{ line }}</p>
              <span v-if="source" class="msg-src">{{ source === 'rule' ? '规则回答（LLM 未启用）' : 'AI 回答' }}</span>
            </div>
          </template>
        </div>

        <div class="aii-input">
          <el-input
            v-model="draft"
            size="small"
            placeholder="输入你的问题…"
            :disabled="loading"
            @keyup.enter="ask()"
          />
          <el-button size="small" type="primary" :loading="loading" @click="ask()">发送</el-button>
        </div>
      </section>
    </transition>

    <!-- 悬浮球 -->
    <button class="aii-ball" :class="{ open }" title="AI 助手" @click="open = !open">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <path d="M9 3h6l1.2 2.2L18.5 6l1 2.1-1.3 1.7.3 2.2-1.6 1.6-2.1 1.4h-3.6L7.1 13.6 5.5 12l.3-2.2L4.5 8.1 5.5 6l2.3-.8z" />
        <path d="M12 3v13.5" />
      </svg>
    </button>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiChat } from '@/api/ai'
import { useAiStore } from '@/stores/ai'

const aiStore = useAiStore()

const open = ref(false)
const draft = ref('')
const loading = ref(false)
const lastQuestion = ref('')
const answer = ref('')
const source = ref('')

const COMMANDS = [
  '这份病历为什么被判定待复核',
  '当前合格率与待复核构成',
  '这个功能怎么用',
  '我接下来该做什么',
  '术语标准化的依据是什么'
]

const answerLines = computed(() => (answer.value || '').split('\n').filter((l) => l.trim() !== ''))

const ask = async (preset) => {
  const q = (preset || draft.value || '').trim()
  if (!q) {
    ElMessage.warning('请输入问题')
    return
  }
  if (loading.value) return
  lastQuestion.value = q
  draft.value = ''
  answer.value = ''
  source.value = ''
  loading.value = true
  try {
    const res = await aiChat({ question: q, recordId: aiStore.activeRecord?.id || '' })
    answer.value = res.data?.answer || ''
    source.value = res.data?.source || ''
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.ai-assistant {
  /* z-index 置顶但不遮菜单（菜单 z-index 更低，面板贴右下） */
  position: fixed;
  right: 26px;
  bottom: 26px;
  z-index: 2000;
}
.aii-ball {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: 1px solid #2f4639;
  background: var(--ink, #2f4639);
  color: #e9e3d2;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-left: auto;
  box-shadow: 0 4px 14px rgba(47, 70, 57, 0.28);
  transition: transform 0.15s ease;
}
.aii-ball:hover { transform: translateY(-2px); }
.aii-ball.open { background: #3d5a4c; }
.aii-ball svg { width: 24px; height: 24px; }

.aii-panel {
  width: 360px;
  height: 480px;
  background: #fbfaf6;
  border: 1px solid var(--line, #e4dfd2);
  border-radius: 8px;
  box-shadow: 0 8px 28px rgba(47, 70, 57, 0.18);
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.aii-hd {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: var(--ink, #2f4639);
  color: #f1ede0;
}
.aii-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #9bb7a4;
}
.aii-title { font-size: 13.5px; font-weight: bold; }
.aii-sub { font-size: 11px; color: #c9b99a; margin-left: 2px; }
.aii-close {
  margin-left: auto;
  background: transparent;
  border: none;
  color: #d8dfd9;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}
.aii-close:hover { color: #fff; }

.aii-commands {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 12px;
  border-bottom: 1px solid #ece8dc;
}
.cmd {
  font-size: 11.5px;
  color: var(--ink, #2f4639);
  background: #fff;
  border: 1px solid var(--line, #e4dfd2);
  border-radius: 12px;
  padding: 3px 10px;
  cursor: pointer;
}
.cmd:hover { background: var(--ink-light, #eef3ee); }
.cmd.on { border-color: var(--ink-mid, #4d6b58); color: var(--ink-mid, #4d6b58); }
.cmd:disabled { opacity: 0.6; cursor: default; }

.aii-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  font-size: 12.5px;
  line-height: 1.7;
}
.aii-empty { color: var(--text-sub, #8a8578); }
.msg { border-radius: 6px; padding: 8px 10px; margin-bottom: 10px; word-break: break-word; }
.msg.user {
  background: var(--ink-light, #eef3ee);
  color: var(--ink, #2f4639);
  margin-left: 40px;
}
.msg.ai {
  background: #fff;
  border: 1px solid var(--line, #e4dfd2);
  color: var(--ink, #2f4639);
}
.msg.ai p { margin: 0 0 4px; }
.msg.ai p:last-child { margin-bottom: 0; }
.msg-src {
  display: inline-block;
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-sub, #8a8578);
}
.msg.loading { color: var(--text-sub, #8a8578); }

.aii-input {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid #ece8dc;
  background: #fff;
}

.aii-fade-enter-active,
.aii-fade-leave-active { transition: opacity 0.15s ease, transform 0.15s ease; }
.aii-fade-enter-from,
.aii-fade-leave-to { opacity: 0; transform: translateY(8px); }
</style>
