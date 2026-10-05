import { ref } from 'vue'

/**
 * 分页列表的公共骨架：加载态 / 失败态 / 竞态取号 / 失败清空。
 *
 * 抽它的理由：五个页面（病历 / 解析 / 质控 / 日志 / 复核）各自把同一段二十行抄了一遍，
 * 抄歪的地方各不相同 —— 有的是失败不清空、有的是没取号。这段逻辑一错就是
 * 「慢的旧响应覆盖新结果」或「加载失败被显示成没有数据」，两类都不报错。
 *
 * ⚠️ 三个行为开关**不是配置洁癖**：接入的六处实例行为本来就不同（见《计划》批次 12 #3
 * 的行为矩阵），统一改行为会破坏「五页列表 loading 与错误提示行为不变」的验收。
 * 开关的作用是把这些差异**显式写在调用处**，而不是藏在各自的复制粘贴里。
 *
 * @param {() => Promise<*>} fetcher 发请求（页面自己带上查询条件与分页参数）
 * @param {(res: *) => {list?: any[], total?: number}} extract 从响应里取列表与总数
 * @param {(res: *) => void} [onLoaded] 成功后的页面特有余项（如 skippedMissing）
 * @param {boolean} [race] 是否用 latest-wins 取号：慢的旧响应整体丢弃（默认 true）
 * @param {boolean} [clearOnFailure] 失败时是否清空列表与总数（默认 true）
 * @param {boolean} [trackFailure] 是否维护失败标记（默认 true）
 * @returns {{list: import('vue').Ref, total: import('vue').Ref, loading: import('vue').Ref<boolean>,
 *            failed: import('vue').Ref<boolean>, load: () => Promise<void>}}
 */
export function usePagedList({
  fetcher,
  extract,
  onLoaded,
  race = true,
  clearOnFailure = true,
  trackFailure = true
}) {
  const list = ref([])
  const total = ref(0)
  const loading = ref(false)
  const failed = ref(false)
  // 竞态取号：发起时取号，回来时号不是最新就整体丢弃（范式同 components/TermInput.vue）
  let seq = 0

  const load = async () => {
    // 1. 取本次请求的号（不用取号时固定 0，下面的判断也随之跳过）
    const mine = race ? ++seq : 0
    // 2. 进入加载态，并清掉上一次的失败标记（重试时能重新给出 loading）
    loading.value = true
    if (trackFailure) {
      failed.value = false
    }
    try {
      const res = await fetcher()
      if (race && mine !== seq) return
      const next = extract(res) || {}
      list.value = next.list || []
      total.value = next.total || 0
      if (onLoaded) {
        onLoaded(res)
      }
    } catch {
      if (race && mine !== seq) return
      if (clearOnFailure) {
        list.value = []
        total.value = 0
      }
      if (trackFailure) {
        failed.value = true
      }
      // 具体提示由拦截器统一给，这里只管状态
    } finally {
      // 3. 只有最新一次请求才收掉加载态，否则会把还在飞的请求的 loading 提前收掉
      if (!race || mine === seq) {
        loading.value = false
      }
    }
  }

  return { list, total, loading, failed, load }
}
