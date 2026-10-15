import request from '@/utils/request'
import { pollAsyncTask } from '@/utils/asyncPoll'

// AI 质控解读：规则出结论 + LLM 叙述；LLM 不可用降级模板
export function aiInterpret(data) {
  return request.post('/ai/interpret', data)
}

// AI 复核预检意见：基于规则预检单生成复核建议
export function aiReview(data) {
  return request.post('/ai/review', data)
}

// ---- 异步（批次 15 · 15.1）----
// 同步接口那条路会让请求线程等 LLM 几十秒。异步路：提交拿任务号，再轮询结果。
// 好处不只是「不占线程」：提交后请求线程立即释放、LLM 在后台跑，页面不必卡着等。
// ⚠️ 任务号**不持久化**：用户离开当前视图再回来是**重新提交一次**（理由见 runAiAsync）。

/** 提交一次 AI 生成，立即拿到任务号 */
function aiSubmit(kind, data) {
  return request.post(`/ai/async/${kind}`, data)
}

/** 查任务结果：state = RUNNING | DONE | FAILED */
function aiResult(taskId) {
  return request.get(`/ai/async/${taskId}`)
}

// 与后端 AsyncTaskRegistry 的超时（120 秒）对齐并留一点余量：后端判超时后我们要能读到那次失败，
// 不能自己先放弃 —— 那样用户看到的是「未知错误」，而不是后端给的「生成超时，请重试」
const POLL_TIMEOUT_MS = 150000

/**
 * 走异步路跑一次 AI 生成并等结果。
 *
 * @param {'interpret'|'review'|'termsuggest'} kind
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
  // 轮询而不是长连接：用户离开页面再回来时，组件重新挂载会重新提交一次；
  // 任务号没有持久化到 localStorage，是因为 AI 结论与组织数据域绑定，跨会话保留反而有泄露风险。
  return pollAsyncTask(() => aiResult(taskId), { timeoutMs: POLL_TIMEOUT_MS, onState })
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
