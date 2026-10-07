/**
 * 极简防抖（性能审查 P1-5 收尾 / B5）。
 *
 * 为什么需要：全站筛选查询都是「按钮 / 离散 change / 回车」触发（不存在逐键查询），
 * 但仍有两处快速连发会放大后端请求：复核页状态/超期开关连点、审计页关键词连按回车。
 * AbortController（usePagedList）负责「取消已发出的旧请求」，防抖负责「干脆别发出」——
 * 两者互补。
 *
 * 与 useTermOptions 里自带的 200ms 联想防抖不同：那个防的是「联想接口」，这里防的是「列表查询」。
 *
 * @param {Function} fn 目标函数（按最后一次调用的参数执行）
 * @param {number} [wait] 等待毫秒，默认 300
 * @returns {Function & {cancel: () => void}} 带 cancel 的包装函数
 */
export function debounce(fn, wait = 300) {
  let timer = null
  const wrapped = (...args) => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      fn(...args)
    }, wait)
  }
  wrapped.cancel = () => {
    if (timer) clearTimeout(timer)
    timer = null
  }
  return wrapped
}