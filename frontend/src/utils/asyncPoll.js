/**
 * 异步任务的退避轮询（AI-16）。
 *
 * <p>原来各处都是固定 1s 间隔：LLM 关闭时后端毫秒级返回，前端却至少多等 1s；
 * 一次 30s 生成要发 30 次请求。改为 100 → 200 → 500 → 1000ms 封顶：
 * 快任务回到毫秒级，慢任务请求数降 3–5 倍。**不引入 SSE / 长轮询** ——
 * 前端已统一走轮询，新增推送通道要同时改任务登记与各处面板，收益不抵改动面。</p>
 */

/** 退避序列；超过末项后固定为 1000ms */
const BACKOFF_MS = [100, 200, 500, 1000]

/**
 * 轮询一个「提交拿任务号 + 查结果」式的异步任务，直到终态。
 *
 * @param {() => Promise<object>} fetchResult 查结果（返回体形如 { data: { state, reply, error } }）
 * @param {object} opts
 * @param {number} opts.timeoutMs 总超时；应略大于后端任务超时，否则用户看到的是「未知错误」而不是后端给的失败原因
 * @param {(state: string) => void} [opts.onState] 每次轮询回调当前状态
 * @param {string} [opts.timeoutMessage]
 * @param {string} [opts.failureMessage]
 * @returns {Promise<object>} 终态载荷（state=DONE 时的 reply）
 */
export async function pollAsyncTask(fetchResult, {
  timeoutMs,
  onState,
  timeoutMessage = '生成超时，请重试',
  failureMessage = '生成失败，请重试',
}) {
  const deadline = Date.now() + timeoutMs
  let attempt = 0
  for (;;) {
    if (Date.now() > deadline) {
      throw new Error(timeoutMessage)
    }
    const res = await fetchResult()
    const state = res?.data?.state
    if (onState) onState(state)
    if (state === 'DONE') {
      return res.data.reply
    }
    if (state === 'FAILED') {
      throw new Error(res?.data?.error || failureMessage)
    }
    const wait = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]
    attempt += 1
    await new Promise((resolve) => setTimeout(resolve, wait))
  }
}
