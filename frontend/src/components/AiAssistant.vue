<template>
  <!-- AI 助手：右下角可拖动悬浮球 + 展开式非模态面板。
       面板可拖动、位置存 localStorage；本会话多轮对话，刷新即清空 -->
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

        <!-- 明示可查范围：让用户知道助手只能看这几类数据，避免误以为能查全库 -->
        <div class="aii-scope">可查范围：本人最近 50 条操作 · 看板统计 · 当前打开的病历</div>

        <!-- 预设问题：点击即提问，省去用户想怎么问 -->
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
                <!-- 来源标签：区分「规则回答」与「AI 回答」，不让规则输出看起来像模型产出 -->
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
// AI 助手：全局悬浮球 + 非模态对话面板。
// 提问带上「当前打开的病历」与近期上文（追问），由后端决定用规则还是 LLM 回答。
import { computed, ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { aiChat } from '@/api/ai'
import { useAiStore } from '@/stores/ai'

// 跨组件读取「当前打开的病历」，作为提问上下文
const aiStore = useAiStore()
// 拖动位置的 localStorage 键；BALL = 悬浮球边长（用于边界收敛与默认落点）
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

// 预设问题：覆盖「质控判定原因 / 统计 / 用法 / 待办 / 操作留痕」五类高频问法
const COMMANDS = [
  '这份病历为什么被判定待复核',
  '当前合格率与待复核构成',
  '这个功能怎么用',
  '我接下来该做什么',
  '我刚才操作了什么'
]

// 回答按空行拆段渲染，避免整块文字堆成一段
const lines = (t) => (t || '').split('\n').filter((l) => l.trim() !== '')

// ===== 拖动（球与面板共用；位置存 localStorage）=====
// 默认落在右下角（留 26px 边距，再减去球自身尺寸）
const defaultPos = () => ({
  x: Math.max(8, window.innerWidth - 26 - BALL),
  y: Math.max(8, window.innerHeight - 26 - BALL)
})
const pos = ref(defaultPos())

// 面板与悬浮球的定位：把 pos（视口坐标）转成行内 left / top；坐标已在 clampPos 收敛到视口内
const containerStyle = computed(() => ({ left: `${pos.value.x}px`, top: `${pos.value.y}px` }))

// 边界收敛：保证整颗球始终在视口内（左右各留 4px）
const clampPos = (x, y) => ({
  x: Math.min(Math.max(4, x), window.innerWidth - BALL - 4),
  y: Math.min(Math.max(4, y), window.innerHeight - BALL - 4)
})

// 落盘当前位置；隐私模式下 localStorage 可能不可用，失败静默忽略
const savePos = () => {
  try {
    localStorage.setItem(POS_KEY, JSON.stringify(pos.value))
  } catch {
    // 忽略：隐私模式下 localStorage 可能不可用
  }
}

// 拖动过程量：dragging 表示按下并监听中；moved 用于区分「拖动」与「点击」
let dragging = false
let moved = false
let startX = 0
let startY = 0
let originX = 0
let originY = 0

// 拖动中：位移超过 4px 即认定为拖动（避免手抖把点击判成拖动）
const onMove = (e) => {
  if (!dragging) return
  const dx = e.clientX - startX
  const dy = e.clientY - startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true
  pos.value = clampPos(originX + dx, originY + dy)
}
// 抬起：只有真的拖动过才落盘，纯点击不写 localStorage
const onUp = () => {
  if (!dragging) return
  dragging = false
  if (moved) savePos()
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
}
// 按下：只响应左键；记录起点与当前落点，监听窗口级 move/up（拖出元素也能跟手）
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
// 球的点击：若刚刚是拖动（moved），吃掉这次 click，不触发展开/收起
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
// 收起面板；不影响已产生的对话内容与拖动位置
const close = () => {
  open.value = false
}
// 清空本会话消息；展开状态与拖动位置不受影响
const clearChat = () => {
  messages.value = []
}

// 展开时焦点给输入框，收起时焦点还给球（非模态浮窗不做焦点陷阱，只做转移）
watch(open, async (v) => {
  await nextTick()
  if (v) inputRef.value?.focus()
  else ballRef.value?.focus()
})

// 滚到底：等 DOM 更新后再量 scrollHeight
const scrollBottom = async () => {
  await nextTick()
  if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
}

// ===== 提问（含追问：带近期上文）=====
// 取最近 6 条消息拼成上文，标注问/答，供后端做多轮理解
const historyText = () => messages.value
  .slice(-6)
  .map((m) => (m.role === 'user' ? '问：' : '答：') + m.text)
  .join('\n')

// 发送一轮提问：预设按钮与输入框共用入口。副作用为追加用户消息、清空输入、切换 loading，
// 并把当前打开的病历 ID 与最近 6 条上文一并发给后端（由后端决定用规则还是 LLM 作答）
const ask = async (preset) => {
  // 1. 取问题：预设按钮优先，其次输入框；空问题直接拦掉
  const q = (preset || draft.value || '').trim()
  if (!q) {
    ElMessage.warning('请输入问题')
    return
  }
  if (loading.value) return
  // 2. 先把用户消息入列并清空输入框，让界面立刻有反馈
  messages.value.push({ role: 'user', text: q })
  draft.value = ''
  loading.value = true
  scrollBottom()
  try {
    // 3. 带上当前病历与近期上文；后端据 source 决定回答来自规则还是模型
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
    // 4. 收起 loading 并再滚一次（回答可能很长）
    loading.value = false
    scrollBottom()
  }
}

// Esc 收起面板（仅展开时响应，不干扰页面其它 Esc 行为）
const onKeydown = (e) => {
  if (e.key === 'Escape' && open.value) close()
}
// 窗口尺寸变化时把球重新收敛回视口内
const onResize = () => {
  pos.value = clampPos(pos.value.x, pos.value.y)
}

onMounted(() => {
  // 1. 恢复上次位置（非法值 / 解析失败都退回默认落点）
  try {
    const saved = JSON.parse(localStorage.getItem(POS_KEY) || 'null')
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) {
      pos.value = clampPos(saved.x, saved.y)
    }
  } catch {
    // 忽略
  }
  // 2. 监听窗口级事件（Esc、尺寸变化）
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onResize)
  // 拖到一半被卸载时，清掉挂在 window 上的 move/up 监听
  onUp()
})
</script>

<style scoped>
.ai-assistant {
  position: fixed;
  /* 必须高于 Element Plus 的遮罩：它的 z-index 从 2000 起「运行期自增」，
     每开一次 dialog / popper / select 下拉就 +1，而遮罩实测在 2008 起。
     原来写 2000 会被遮罩整个吞掉（球点不动、面板点不到）。
     5000 在一次会话里要分配 3000 次才可能被反超，实际到不了；
     代价是助手面板可能盖住弹窗内容 —— 面板可拖动，必要时移开。
     （更稳的长期做法是「弹窗打开时收起全局球、在弹窗内给入口」，见方案 B6-3 乙案） */
  z-index: 5000;
}
/* 悬浮球：深墨绿底 + 细描边；touch-action:none 让指针拖动不被浏览器手势抢走 */
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

/* 面板：锚在球上方 60px（留出球的空位），固定尺寸但受视口上限约束 */
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
/* 标题栏兼作拖动手柄 */
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
/* 标题左侧的状态点 */
.aii-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #9bb7a4;
}
.aii-title { font-size: 13.5px; font-weight: bold; }
.aii-sub { font-size: 11px; color: #c9b99a; margin-left: 2px; }
/* 「清空」「收起」用无底透明按钮，避免在深色标题栏上抢视觉 */
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

/* 可查范围提示条：浅色底与上方标题栏区分 */
.aii-scope {
  padding: 8px 12px;
  font-size: 11.5px;
  color: var(--text-sub, #8a8578);
  background: var(--ink-light, #eef3ee);
  border-bottom: 1px solid #ece8dc;
}

/* 预设问题：胶囊按钮换行排列 */
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

/* 消息区：唯一滚动容器，flex:1 吃掉剩余高度 */
.aii-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  font-size: 12.5px;
  line-height: 1.7;
}
.aii-empty { color: var(--text-sub, #8a8578); }
.msg { border-radius: 6px; padding: 8px 10px; margin-bottom: 10px; word-break: break-word; }
/* 用户消息靠右留白，与 AI 消息一眼分开 */
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
/* 回答来源标签 */
.msg-src {
  display: inline-block;
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-sub, #8a8578);
}
.msg.loading { color: var(--text-sub, #8a8578); }

/* 输入区固定在面板底部 */
.aii-input {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid #ece8dc;
  background: #fff;
}

/* 展开/收起过渡：淡入 + 上移 8px */
.aii-fade-enter-active,
.aii-fade-leave-active { transition: opacity 0.15s ease, transform 0.15s ease; }
.aii-fade-enter-from,
.aii-fade-leave-to { opacity: 0; transform: translateY(8px); }
</style>
