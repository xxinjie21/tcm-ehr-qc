<template>
  <div class="ai-assistant" :style="containerStyle">
    <!-- 展开面板。非模态浮窗（用户仍可操作页面），故不做焦点陷阱，只做焦点转移 -->
    <transition name="aii-fade">
      <section v-if="open" class="aii-panel" role="dialog" aria-label="AI 助手">
        <header class="aii-hd" title="按住可拖动" @pointerdown="startDrag">
          <span class="aii-dot" />
          <span class="aii-title">AI 助手</span>
          <span class="aii-sub">可拖动</span>
          <button class="aii-clear" type="button" title="清空对话" aria-label="清空对话" @pointerdown.stop @click="clearChat">清空</button>
          <button class="aii-close" type="button" title="收起" aria-label="收起 AI 助手" @pointerdown.stop @click="close">×</button>
        </header>

        <div class="aii-scope">可查范围：本人最近 50 条操作 · 看板统计 · 当前打开的病历</div>

        <div class="aii-commands">
          <button
            v-for="c in COMMANDS"
            :key="c"
            type="button"
            class="cmd"
            :disabled="loading"
            @click="ask(c)"
          >{{ c }}</button>
        </div>

        <div ref="bodyRef" class="aii-body">
          <div v-if="!messages.length" class="aii-empty">
            你好，我是本系统的使用助手。可以问我数据统计、功能用法、业务流程、质控判定原因；
            技术实现问题请查看设计文档。
          </div>
          <template v-else>
            <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
              <template v-if="m.role === 'user'">{{ m.text }}</template>
              <template v-else>
                <p v-for="(line, j) in lines(m.text)" :key="j">{{ line }}</p>
                <span v-if="m.source" class="msg-src">{{ m.source === 'rule' ? '规则回答（LLM 未启用）' : 'AI 回答' }}</span>
              </template>
            </div>
            <div v-if="loading" class="msg ai loading">正在思考…</div>
          </template>
        </div>

        <div class="aii-input">
          <el-input
            ref="inputRef"
            v-model="draft"
            size="small"
            placeholder="输入你的问题，可继续追问…"
            :disabled="loading"
            @keyup.enter="ask()"
          />
          <el-button size="small" type="primary" :loading="loading" @click="ask()">发送</el-button>
        </div>
      </section>
    </transition>

    <!-- 悬浮球 -->
    <button
      ref="ballRef"
      class="aii-ball"
      :class="{ open }"
      :title="open ? '收起 AI 助手' : '打开 AI 助手（可拖动）'"
      :aria-label="open ? '收起 AI 助手' : '打开 AI 助手'"
      :aria-expanded="open"
      @pointerdown="startDrag"
      @click="onBallClick"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <path d="M9 3h6l1.2 2.2L18.5 6l1 2.1-1.3 1.7.3 2.2-1.6 1.6-2.1 1.4h-3.6L7.1 13.6 5.5 12l.3-2.2L4.5 8.1 5.5 6l2.3-.8z" />
        <path d="M12 3v13.5" />
      </svg>
    </button>
  </div>
</template>

<script setup>
import { computed, ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { aiChat } from '@/api/ai'
import { useAiStore } from '@/stores/ai'

const aiStore = useAiStore()
const POS_KEY = 'aiAssistantPos'
const BALL = 48

const open = ref(false)
const ballRef = ref(null)
const inputRef = ref(null)
const bodyRef = ref(null)

/** 多轮消息（本会话；刷新即清空） */
const messages = ref([])
const draft = ref('')
const loading = ref(false)

const COMMANDS = [
  '这份病历为什么被判定待复核',
  '当前合格率与待复核构成',
  '这个功能怎么用',
  '我接下来该做什么',
  '我刚才操作了什么'
]

const lines = (t) => (t || '').split('\n').filter((l) => l.trim() !== '')

// ===== 拖动（球与面板共用；位置存 localStorage）=====
const defaultPos = () => ({
  x: Math.max(8, window.innerWidth - 26 - BALL),
  y: Math.max(8, window.innerHeight - 26 - BALL)
})
const pos = ref(defaultPos())

const containerStyle = computed(() => ({ left: `${pos.value.x}px`, top: `${pos.value.y}px` }))

const clampPos = (x, y) => ({
  x: Math.min(Math.max(4, x), window.innerWidth - BALL - 4),
  y: Math.min(Math.max(4, y), window.innerHeight - BALL - 4)
})

const savePos = () => {
  try {
    localStorage.setItem(POS_KEY, JSON.stringify(pos.value))
  } catch {
    // 忽略：隐私模式下 localStorage 可能不可用
  }
}

let dragging = false
let moved = false
let startX = 0
let startY = 0
let originX = 0
let originY = 0

const onMove = (e) => {
  if (!dragging) return
  const dx = e.clientX - startX
  const dy = e.clientY - startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true
  pos.value = clampPos(originX + dx, originY + dy)
}
const onUp = () => {
  if (!dragging) return
  dragging = false
  if (moved) savePos()
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
}
const startDrag = (e) => {
  if (e.button !== 0) return
  dragging = true
  moved = false
  startX = e.clientX
  startY = e.clientY
  originX = pos.value.x
  originY = pos.value.y
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}
const onBallClick = () => {
  if (moved) {
    moved = false
    return
  }
  toggle()
}

// ===== 展开/收起 =====
const toggle = () => {
  open.value = !open.value
}
const close = () => {
  open.value = false
}
const clearChat = () => {
  messages.value = []
}

watch(open, async (v) => {
  await nextTick()
  if (v) inputRef.value?.focus()
  else ballRef.value?.focus()
})

const scrollBottom = async () => {
  await nextTick()
  if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
}

// ===== 提问（含追问：带近期上文）=====
const historyText = () => messages.value
  .slice(-6)
  .map((m) => (m.role === 'user' ? '问：' : '答：') + m.text)
  .join('\n')

const ask = async (preset) => {
  const q = (preset || draft.value || '').trim()
  if (!q) {
    ElMessage.warning('请输入问题')
    return
  }
  if (loading.value) return
  messages.value.push({ role: 'user', text: q })
  draft.value = ''
  loading.value = true
  scrollBottom()
  try {
    const res = await aiChat({
      question: q,
      recordId: aiStore.activeRecord?.id || '',
      history: historyText()
    })
    messages.value.push({
      role: 'ai',
      text: res.data?.answer || '（无回答）',
      source: res.data?.source || ''
    })
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
    scrollBottom()
  }
}

const onKeydown = (e) => {
  if (e.key === 'Escape' && open.value) close()
}
const onResize = () => {
  pos.value = clampPos(pos.value.x, pos.value.y)
}

onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem(POS_KEY) || 'null')
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) {
      pos.value = clampPos(saved.x, saved.y)
    }
  } catch {
    // 忽略
  }
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onResize)
  onUp()
})
</script>

<style scoped>
.ai-assistant {
  position: fixed;
  z-index: 2000;
}
.aii-ball {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: 1px solid #2f4639;
  background: var(--ink, #2f4639);
  color: #e9e3d2;
  cursor: grab;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-left: auto;
  box-shadow: 0 4px 14px rgba(47, 70, 57, 0.28);
  transition: transform 0.15s ease;
  touch-action: none;
}
.aii-ball:hover { transform: translateY(-2px); }
.aii-ball.open { background: #3d5a4c; }
.aii-ball svg { width: 24px; height: 24px; }

.aii-panel {
  position: absolute;
  right: 0;
  bottom: 60px;
  width: 360px;
  height: 480px;
  max-width: 94vw;
  max-height: 76vh;
  background: #fbfaf6;
  border: 1px solid var(--line, #e4dfd2);
  border-radius: 8px;
  box-shadow: 0 8px 28px rgba(47, 70, 57, 0.18);
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
  cursor: grab;
  touch-action: none;
}
.aii-hd:active { cursor: grabbing; }
.aii-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #9bb7a4;
}
.aii-title { font-size: 13.5px; font-weight: bold; }
.aii-sub { font-size: 11px; color: #c9b99a; margin-left: 2px; }
.aii-clear,
.aii-close {
  background: transparent;
  border: none;
  color: #d8dfd9;
  cursor: pointer;
  line-height: 1;
}
.aii-clear { margin-left: auto; font-size: 12px; }
.aii-close { font-size: 18px; }
.aii-clear:hover, .aii-close:hover { color: #fff; }

.aii-scope {
  padding: 8px 12px;
  font-size: 11.5px;
  color: var(--text-sub, #8a8578);
  background: var(--ink-light, #eef3ee);
  border-bottom: 1px solid #ece8dc;
}

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
