import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use((config) => {
  // 1. 取本地登录 token
  const token = localStorage.getItem('token')
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
      // 1. 文件流直接透传原始数据
      return response.data
    }
    // 2. 取出统一响应体 Result
    const res = response.data
    // 3. 非 200 视为失败：提示后 reject，401 额外清登录态
    if (res.code !== 200) {
      ElMessage.error(res.msg || '请求失败')
      if (res.code === 401) {
        redirectToLogin()
      }
      return Promise.reject(new Error(res.msg))
    }
    return res
  },
  (error) => {
    // 1. 取出状态码与后端 msg
    const status = error.response && error.response.status
    const msg = error.response && error.response.data && error.response.data.msg
    // 2. 401 未授权：提示并清登录态
    if (status === 401) {
      // 凭证错误 / token 过期：以后端 msg 为准（登录页密码错误也走这里）
      ElMessage.error(msg || '登录已过期，请重新登录')
      redirectToLogin()
    } else if (status === 403) {
      // 3. 403 无权限：仅提示，不跳转
      ElMessage.error(msg || '无权限执行该操作')
    } else {
      // 4. 其余情况：通用错误提示
      ElMessage.error(msg || error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

/**
 * 从 axios 错误里取出「能给用户看」的那句文案。
 *
 * 后端统一回 Result{code,msg,data}，其中 msg 是面向用户的 —— 例如
 * LlmProbeException 的消息契约上就写明「已脱敏、可直接展示给用户」。
 * 而 axios 自己的 error.message 只有 "Request failed with status code 502"
 * 这种英文兜底。两个混用时用户看到的是后者，服务端已经准备好的原因被白白丢掉。
 */
export function apiErrorMessage(e, fallback = '请求失败') {
  return e?.response?.data?.msg || e?.message || fallback
}

export default request
