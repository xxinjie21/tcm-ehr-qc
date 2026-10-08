/**
 * 面向用户的错误文案：本模块是它的唯一产出地。
 *
 * 为什么单列一个文件：文案与「拦截器怎么发请求」是两件事 ——
 * 混在 `request.js` 里时，改一句提示得先读懂 axios 拦截器；而这里全是纯函数，
 * 谁都能直接用（页面自己 catch 住错误时就是从这儿取文案）。
 */

/**
 * 从 axios 错误里取出「能给用户看」的那句文案。
 *
 * 后端统一回 Result{code,msg,data}，其中 msg 是面向用户的 —— 例如
 * LlmProbeException 的消息契约上就写明「已脱敏、可直接展示给用户」。
 * 而 axios 自己的 error.message 只有 "Request failed with status code 502"
 * 这种英文兜底。两个混用时用户看到的是后者，服务端已经准备好的原因被白白丢掉。
 *
 * @param {*} e axios 错误
 * @param {string} fallback 没有 msg 也没有 message 时的兜底文案
 * @returns {string} 可直接展示的文案
 */
export function apiErrorMessage(e, fallback = '请求失败') {
  return e?.response?.data?.msg || e?.message || fallback
}

/**
 * 把后端直通的「技术措辞」翻译成「哪一步、怎么办」（审查报告 M6）。
 *
 * 后端统一回 Result{msg}，多数 msg 已面向用户；少数异常层直出的还是
 * 实现措辞（如「术语类型非法」「缺少文件参数：file」），原样弹给用户
 * 既不知道是哪一步也不知道改什么。白名单式小表只追已知的几类，命中就换。
 */
const HINT_MAP = {
  '术语类型非法': '词典解析失败：术语类型无效，请确认已正确选择术语类型',
  '缺少文件参数：file': '文件未成功上传，请重新选择文件后再试',
  '缺少必填参数：type': '词典解析失败：缺少术语类型，请刷新当前页后重试'
}

function humanize(msg) {
  if (msg && HINT_MAP[msg]) return HINT_MAP[msg]
  return msg
}

/**
 * 判定一个失败响应「该提示什么、要不要清登录态」。
 *
 * 把判断与副作用分开：这里只做纯计算，清登录态与跳转由拦截器执行 ——
 * 于是文案能单独改、单独测，不必在一个会跳路由的函数里改字符串。
 *
 * @param {*} error axios 错误（走到 error 分支）
 * @returns {{message: string, logout: boolean}}
 */
export function describeHttpError(error) {
  const status = error?.response?.status
  const msg = error?.response?.data?.msg
  // 401：凭证错误 / token 过期，以后端 msg 为准（登录页密码错误也走这里）
  if (status === 401) {
    return { message: humanize(msg) || '登录已过期，请重新登录', logout: true }
  }
  // 403：无权限只提示，不跳转（跳走会让用户以为「被踢了」）
  if (status === 403) {
    return { message: humanize(msg) || '无权限执行该操作', logout: false }
  }
  return { message: humanize(msg) || error?.message || '网络异常', logout: false }
}

/**
 * 判定一个「HTTP 200 但 code ≠ 200」的业务失败该提示什么、要不要清登录态。
 *
 * @param {{code?: number, msg?: string}} body 统一响应体 Result
 * @returns {{message: string, logout: boolean}}
 */
export function describeBizError(body) {
  const code = body?.code
  return {
    message: humanize(body?.msg) || '请求失败',
    logout: code === 401
  }
}
