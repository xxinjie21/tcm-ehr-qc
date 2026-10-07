import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'
import { describeBizError, describeHttpError } from '@/utils/errorMessage'

/**
 * 全局 axios 实例与两个拦截器。
 *
 * ⚠️ 本文件只管「怎么发请求、失败后做什么副作用」；**面向用户的文案在
 * `@/utils/errorMessage`**（`apiErrorMessage` / `describeHttpError` / `describeBizError`）。
 * 两者原先挤在一个文件里，改一句提示得先读懂拦截器。
 *
 * 文件流（responseType: 'blob'）不套 Result，拦截器直接透传 response.data。
 *
 * 静默开关：请求第二参可传 `{ silent: true }`（axios 会原样挂在 config 上），
 * 拦截器据此跳过 ElMessage —— 供健康探测 / 后台刷新这类「失败由页面自己降级」的请求使用；
 * 401 清登录态与跳登录不受它影响，任何请求都照旧执行。
 */

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use((config) => {
  // 1. token 统一从 user store 取，不直接读 localStorage ——
  //    stores/user.js 是 token 的唯一写入方，这里再读一次 localStorage 就是第二份读源：
  //    两者一旦不一致（store 已 logout、localStorage 还没清），请求会带着已注销的 token 发出，
  //    表现为「明明退出登录了还报 401」。
  //    依赖方向：request.js -> stores/user.js（user.js 不 import request.js，无环）。
  const token = useUserStore().token
  // 2. 有 token 则注入 Authorization 请求头
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 401：清登录态（token / role / menus）并回登录页；403：仅提示无权限，不跳转
function redirectToLogin() {
  // 1. 记录当前路由（用于登录后回跳）
  const current = router.currentRoute.value
  // 2. 清登录态
  useUserStore().logout()
  // 3. 非登录页则跳登录页并带上 redirect
  if (current.path !== '/login') {
    // 带上被中断的目标页（含 query），登录成功后由 Login.vue 还原
    router.push({ path: '/login', query: { redirect: current.fullPath } })
  }
}

request.interceptors.response.use(
  (response) => {
    // 文件流（blob）不套 Result，直接返回
    if (response.config.responseType === 'blob') {
      return response.data
    }
    // 统一响应体 Result
    const res = response.data
    // 非 200 视为失败：提示后 reject，401 额外清登录态
    if (res.code !== 200) {
      const { message, logout } = describeBizError(res)
      // silent 用于健康探测等后台请求，失败只走页面内降级提示，不弹全局红条
      // （例：/api/nlp/health 在未部署 NLP 的后端上是 404，打开结构化解析页
      //   就弹一条「接口不存在」纯属噪音，页面自己会显示「不知道服务状态」）。
      // ⚠️ 只跳过提示：logout（401 清登录态 / 跳登录）与 silent 无关，必须照旧执行，
      //    否则一次后台探测撞上过期 token，登录态会被静默吞掉、用户毫无感知。
      if (!response.config?.silent) {
        ElMessage.error(message)
      }
      if (logout) {
        redirectToLogin()
      }
      return Promise.reject(new Error(res.msg))
    }
    return res
  },
  (error) => {
    const { message, logout } = describeHttpError(error)
    // 同上：silent 只压提示不压副作用。error.config 在请求还没发出的错误里可能不存在
    // （如请求拦截器抛错），用可选链按「不静默」处理，宁可多提示也不吞掉真问题。
    if (!error.config?.silent) {
      ElMessage.error(message)
    }
    if (logout) {
      redirectToLogin()
    }
    return Promise.reject(error)
  }
)

export default request
