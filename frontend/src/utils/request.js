import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 401：清登录态（token / role / menus）并回登录页；403：仅提示无权限，不跳转
function redirectToLogin() {
  const current = router.currentRoute.value
  useUserStore().logout()
  if (current.path !== '/login') {
    // 带上被中断的目标页（含 query），登录成功后由 Login.vue 还原（UX-07）
    router.push({ path: '/login', query: { redirect: current.fullPath } })
  }
}

request.interceptors.response.use(
  (response) => {
    // 文件流（blob）不套 Result，直接返回
    if (response.config.responseType === 'blob') {
      return response.data
    }
    const res = response.data
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
    const status = error.response && error.response.status
    const msg = error.response && error.response.data && error.response.data.msg
    if (status === 401) {
      // 凭证错误 / token 过期：以后端 msg 为准（登录页密码错误也走这里）
      ElMessage.error(msg || '登录已过期，请重新登录')
      redirectToLogin()
    } else if (status === 403) {
      ElMessage.error(msg || '无权限执行该操作')
    } else {
      ElMessage.error(msg || error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

export default request
