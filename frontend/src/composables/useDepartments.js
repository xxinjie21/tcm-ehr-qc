import { ref } from 'vue'
import { getDepartments } from '@/api/stats'

// 科室下拉的 Promise 单例缓存（P3.2）：Dashboard / Governance / RangeFilter 三处
// 各自拉过，重复进入不再发请求。模块级：
//   - 成功 → 结果冻结为已解决 Promise（列表短且会话内稳定）
//   - 失败 → 置回 null 便于下次重试，本次回退空数组（不阻断手输证候）
let cached = null

/**
 * 拉取可选科室列表（后端按当前组返回 DISTINCT department）。
 *
 * @returns {{ departments: import('vue').Ref<string[]>, reload: () => Promise<void> }}
 */
export function useDepartments() {
  const departments = ref([])
  const load = async () => {
    if (!cached) {
      cached = getDepartments()
        .then((res) => {
          const data = res.data || []
          cached = Promise.resolve(data)
          return data
        })
        .catch(() => {
          cached = null
          return []
        })
    }
    departments.value = await cached
  }
  return { departments, reload: load }
}