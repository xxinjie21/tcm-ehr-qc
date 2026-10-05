import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 把列表页的筛选/分页状态同步到 URL（对标 E5「可分享视图」：筛选状态入 URL，刷新 / 分享保留）。
 *
 * <p>为什么是新组合式，而不是塞进 {@code usePagedList}：后者的契约是「页面自己带上查询条件」，
 * 它<b>刻意不认识</b>筛选字段的 schema。把 URL 序列化塞进去，等于让分页骨架去猜每个页面的
 * 筛选结构 —— 那是另一种耦合。这里由页面显式传入自己的状态对象，一处一行。</p>
 *
 * <p><b>三个刻意的取舍</b>：</p>
 * <ol>
 * <li><b>用 replace 而不是 push</b>：每改一个筛选就压一条历史，会让浏览器「后退」变成
 *     在筛选之间反复横跳，回不到上一页。分享/刷新只需要 URL 里有参数，不需要历史记录。</li>
 * <li><b>只写非默认值</b>：默认值不落 URL，否则链接又长又难读，而且以后改默认值会让
 *     旧链接把用户钉在上一版默认上。</li>
 * <li><b>还原时做类型兜底</b>：URL 是用户可编辑的（字符串），page 必须是正整数、
 *     dateRange 这类数组要能识别，坏值一律退回默认而不是把坏值发到后端。</li>
 * </ol>
 *
 * <p><b>调用时机很重要</b>：本函数在 setup 阶段<b>同步</b>还原状态，因此页面原有的
 * {@code onMounted(load)} 会自动带上还原后的条件 —— 不需要额外接线，
 * 也不要再单独调一次加载（那会发两次请求）。</p>
 *
 * @param {object} state 页面的筛选状态（reactive 对象；值为字符串/数组/null）
 * @param {import('vue').Ref<number>} page 页码
 * @param {import('vue').Ref<number>} pageSize 每页条数
 * @param {object} [options]
 * @param {string[]} [options.omit] 不写进 URL 的键（如仅前端使用的开关）
 * @param {object} [options.defaults] 各键的默认值；省略时按类型推断（'' / null / []）
 */
export function useUrlFilters(state, page, pageSize, options = {}) {
  const route = useRoute()
  const router = useRouter()
  const omit = new Set(options.omit || [])
  const defaults = options.defaults || {}

  const defaultOf = (k, v) => {
    if (k in defaults) return defaults[k]
    if (Array.isArray(v)) return []
    if (v === null || v === undefined) return null
    if (typeof v === 'number') return 0
    return ''
  }

  // ---- 1. 从 URL 还原（同步，先于页面的 onMounted 加载）----
  // 先记住"页面自带的每页条数"：URL 没给 pageSize 时它就是默认值，不该写进链接。
  // （不能用「比较 pageSize.value 与它自己」那种写法 —— 恒为假，等于 pageSize 永不入 URL。）
  const initialPageSize = pageSize ? pageSize.value : null
  // 有些页面把 page / pageSize 放在筛选对象**里**（如审计页 query={action,keyword,page,pageSize}），
  // 那种形态没有独立的 ref 可传。这里直接认 state 上的这两个键，调用处传 null 即可 ——
  // 与其逼所有页面改成同一种形状，不如让这个小工具接受两种都见过的形状。
  const inState = state != null && ('page' in state || 'pageSize' in state)
  const q = route.query || {}
  Object.keys(state).forEach((k) => {
    if (omit.has(k) || !(k in q)) return
    const raw = q[k]
    const cur = state[k]
    if (Array.isArray(cur)) {
      state[k] = Array.isArray(raw) ? raw.map(String) : String(raw).split(',').filter(Boolean)
    } else if (typeof cur === 'number') {
      state[k] = Number(raw) || defaultOf(k, cur)
    } else if (typeof cur === 'boolean') {
      state[k] = raw === 'true' || raw === '1'
    } else if (cur === null && /^\d{4}-\d{2}-\d{2}/.test(String(raw))) {
      // dateRange 这类以 null 为默认的日期区间：URL 里存成 "起,止"
      state[k] = String(raw).split(',').filter(Boolean)
    } else {
      state[k] = String(raw)
    }
  })
  // 分页只在 URL 里显式给了合法值时才还原（page/pageSize 在 state 里时，上面的通用循环已经处理过）
  if (!inState) {
    const p = Number(q.page)
    if (Number.isInteger(p) && p > 0) page.value = p
    const ps = Number(q.pageSize)
    if (Number.isInteger(ps) && ps > 0) pageSize.value = ps
  }

  // ---- 2. 状态变化写回 URL ----
  const write = () => {
    const next = {}
    Object.keys(state).forEach((k) => {
      if (omit.has(k)) return
      const v = state[k]
      const d = defaultOf(k, v)
      const isEmpty = v === d || v === '' || v === null || v === undefined ||
        (Array.isArray(v) && v.length === 0)
      if (!isEmpty) next[k] = Array.isArray(v) ? v.join(',') : String(v)
    })
    // 第一页是默认值，不进 URL：/records 与 /records?page=1 是同一个视图
    if (!inState) {
      if (page.value > 1) next.page = String(page.value)
      if (pageSize.value !== initialPageSize) next.pageSize = String(pageSize.value)
    }
    // replace：见类注释第 ① 条，避免污染后退历史
    router.replace({ query: next }).catch(() => {})
  }

  watch(inState ? [state] : [state, page, pageSize], write, { deep: true })
  return { write }
}
