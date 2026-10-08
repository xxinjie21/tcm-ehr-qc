import request from '@/utils/request'

// AI 质控解读：规则出结论 + LLM 叙述；LLM 不可用降级模板
export function aiInterpret(data) {
  return request.post('/ai/interpret', data)
}

// AI 助手问答：业务问题 LLM+规则检索；技术实现问题兜底拒答
export function aiChat(data) {
  return request.post('/ai/chat', data)
}

// AI 复核预检意见：基于规则预检单生成复核建议
export function aiReview(data) {
  return request.post('/ai/review', data)
}

// ---- 异步（批次 15 · 15.1）----
// 同步接口那条路会让请求线程等 LLM 几十秒。异步路：提交拿任务号，再轮询结果。
// 好处不只是「不占线程」：用户可以离开页面，回来时任务号还在，再查一次就能拿到结论。

/** 提交一次 AI 生成，立即拿到任务号 */
function aiSubmit(kind, data) {
  return request.post(`/ai/async/${kind}`, data)
}

/** 查任务结果：state = RUNNING | DONE | FAILED */
function aiResult(taskId) {
  return request.get(`/ai/async/${taskId}`)
}

const POLL_INTERVAL_MS = 1000
// 与后端 AiAsyncTasks 的超时（120 秒）对齐并留一点余量：后端判超时后我们要能读到那次失败，
// 不能自己先放弃 —— 那样用户看到的是「未知错误」，而不是后端给的「生成超时，请重试」
const POLL_TIMEOUT_MS = 150000

/**
 * 走异步路跑一次 AI 生成并等结果。
 *
 * @param {'interpret'|'chat'|'review'} kind
 * @param {object} data 请求体（与同步接口一致）
 * @param {(state: string) => void} [onState] 每次轮询回调当前状态，用于显示「正在生成…」
 * @returns {Promise<object>} reply（结构与同步接口的返回值一致）
 */
export async function runAiAsync(kind, data, onState) {
  const submitted = await aiSubmit(kind, data)
  const taskId = submitted?.data?.taskId
  if (!taskId) {
    throw new Error('提交失败：未拿到任务号')
  }
  const deadline = Date.now() + POLL_TIMEOUT_MS
  // 轮询而不是长连接：用户离开页面再回来时，组件重新挂载会重新提交一次；
  // 任务号没有持久化到 localStorage，是因为 AI 结论与组织数据域绑定，跨会话保留反而有泄露风险。
  for (;;) {
    if (Date.now() > deadline) {
      throw new Error('生成超时，请重试')
    }
    const res = await aiResult(taskId)
    const state = res?.data?.state
    if (onState) onState(state)
    if (state === 'DONE') {
      return res.data.reply
    }
    if (state === 'FAILED') {
      throw new Error(res?.data?.error || '生成失败，请重试')
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
  }
}

/**
 * AI 术语补词建议（termsuggest）。
 *
 * 后端会先按字面相似度从现有词表召回候选，再让 LLM 在候选约束下判断
 * 「挂别名 / 新建标准词 / 忽略」。**只出候选，不落库** —— 必须人工确认后才录入。
 *
 * 走异步路：一次要跑 LLM，同步接口会把请求线程占住几十秒。
 *
 * @param {{terms: string[], termType?: string}} data terms=待规范原文；termType 留空按 symptom
 * @param {(state: string) => void} [onState]
 * @returns {Promise<object>} AiReplyVO，结果在 termSuggestions 里
 */
export function runTermSuggest(data, onState) {
  return runAiAsync('termsuggest', data, onState)
}
