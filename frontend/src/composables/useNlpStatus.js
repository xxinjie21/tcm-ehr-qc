import { ref } from 'vue'
import { getNlpHealth } from '@/api/nlp'

/**
 * NLP 抽取服务的健康探测结果（模块级单例）。
 *
 * 解析页与结构化数据卡片都要知道「抽取服务现在能不能用」，但两边关心的是同一件事：
 * 各探各的会变成同一次加载发两个请求，结论还可能先后不一致（一个说在跑、一个说连不上）。
 * 这里只保留一份探测结果，谁先加载谁触发、另一方直接复用 —— 25.7 的「一个探测解决两条」。
 *
 * 失败不抛、也不伪造结论：后端没起或未登录时 `status` 保持 null，调用方按「不知道」处理
 * —— 拿一个假的「连不上」去提示用户，比不提示更糟。
 */
const status = ref(null)     // { enabled, reachable, modelAvailable, unavailableReason }；null=尚未探到
const loading = ref(false)
let inflight = null          // 并发去重：同一时刻只发一次探测

export function useNlpStatus() {
  return { status, loading, probeNlp }
}

/**
 * 探测抽取服务状态。
 *
 * @param {boolean} force 忽略已有结论重探（「重新探测」按钮用）
 * @returns {Promise<object|null>} 探测结论；已有结论且未 force 时直接返回它，不发请求
 */
export function probeNlp(force = false) {
  // 1. 已有同一次探测在飞：搭它的车，不重复发
  if (inflight) return inflight
  // 2. 已有结论且不要求重探：直接用
  if (!force && status.value) return Promise.resolve(status.value)
  loading.value = true
  inflight = getNlpHealth()
    .then((res) => {
      status.value = res && res.data ? res.data : null
      return status.value
    })
    .catch(() => {
      // 拦截器已提示；结论保持 null，调用方按「不知道」处理
      return null
    })
    .finally(() => {
      loading.value = false
      inflight = null
    })
  return inflight
}
