import { onBeforeUnmount, ref } from 'vue'
import { getTerms } from '@/api/dictionary'

/**
 * 术语候选的取数与检索（批次 15 工作项 2/3 的共用部分）。
 *
 * <p><b>为什么抽成 composable 而不是共用一个控件</b>：两处需求的数据形状不同——
 * 人工复核的术语框是<b>自由文本</b>（一个字段可填「肝郁、脾虚」，提交时按分隔符拆），
 * 质控规则配置的期望值是<b>离散多选</b>。硬套同一个控件会让复核页退化成只能选一个词。
 * 所以这里只共用「取数 + 防抖 + 首次预载」这段逻辑，控件各用各的合适形态。</p>
 *
 * <p><b>首次预载解决什么</b>：此前人工复核的术语框用 {@code el-autocomplete}，
 * 只有<b>输入</b>才出候选 —— 点开是空的，用户不知道「这里能搜标准术语」。
 * 现在展开/聚焦即预载一批（{@link PRELOAD_SIZE} 条），点开就有内容可看。</p>
 *
 * <p><b>同时解决质控规则配置的问题</b>：那里原先为喂一个「不能搜」的下拉，
 * 一次性把五类词典<b>全量</b>拉进内存（证候 2080 + 疾病 1364 + …≈ 3589 个 option）。
 * 改为远程检索后不再需要预载，页面初始负载同步降下来。</p>
 */

/** 展开时预载的条数：够看又不至于卡；证候词典 2080 条，必须靠虚拟滚动 + 远程检索 */
const PRELOAD_SIZE = 50

/** 输入防抖间隔（毫秒） */
const DEBOUNCE_MS = 200

// 模块级缓存：同一页多个同类下拉共用一份，避免并发打同一接口。
// key = `${type}|${keyword}`；value = {label,value}[]
const cache = new Map()

/** 把接口返回的 {standardTerm, aliases} 转成 el-select-v2 需要的 {label, value} */
function toOptions(terms) {
  return (terms || [])
    .map((t) => t.standardTerm)
    .filter(Boolean)
    .map((s) => ({ label: s, value: s }))
}

/**
 * @param {string|import('vue').Ref<string>} type 词典类型（disease/pattern/symptom/herb/formula）
 * @param {number} size 单次取回的条数上限
 */
export function useTermOptions(type, size = PRELOAD_SIZE) {
  const options = ref([])
  const loading = ref(false)

  const typeOf = () => (typeof type === 'function' ? type() : type?.value ?? type)
  const keyOf = (kw) => `${typeOf()}|${kw || ''}`

  let timer = null
  // latest-wins：每次真正发请求时取号，回来时号不是最新就丢弃，
  // 避免慢的旧响应覆盖快的新结果（沿用 TermInput 原有的口径）
  let seq = 0

  /** 立即取一批（走缓存） */
  async function fetchNow(keyword = '') {
    const kw = String(keyword || '').trim()
    const key = keyOf(kw)
    if (cache.has(key)) {
      options.value = cache.get(key)
      return options.value
    }
    loading.value = true
    const mine = ++seq
    try {
      const res = await getTerms({ type: typeOf(), keyword: kw, page: 1, size })
      const opts = toOptions(res?.data?.terms)
      if (mine !== seq) return options.value
      cache.set(key, opts)
      options.value = opts
      return opts
    } catch {
      // 词典接口异常 → 降级为无候选，不打断用户继续输入
      if (mine === seq) options.value = []
      return []
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  /** 防抖检索：连续输入只保留最后一次（供 el-select-v2 的 remote-method 用） */
  function search(keyword) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      fetchNow(keyword)
    }, DEBOUNCE_MS)
  }

  /**
   * 防抖检索并回调（供 el-autocomplete 用）。
   *
   * <p>{@code el-autocomplete} 的 {@code fetch-suggestions} 是同步回调契约：
   * 立刻 {@code cb(旧数据)} 它就照着旧数据渲染，<b>数据回来后不会自己刷新下拉</b>。
   * 所以这里等取回再 cb —— 这也是 el-autocomplete 做远程检索的常规写法。</p>
   */
  function searchWith(keyword, cb) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      fetchNow(keyword).then((opts) => cb(opts))
    }, DEBOUNCE_MS)
  }

  /** 展开 / 聚焦时预载一批，让下拉不再是空的 */
  function preload() {
    if (options.value.length === 0) fetchNow('')
  }

  /** 词典被导入/重建后需要失效缓存，否则用户搜不到刚导入的词 */
  function invalidate() {
    cache.clear()
    options.value = []
  }

  // 组件卸载时清掉尚未触发的防抖定时器：它回调里会写 options，
  // 卸载后再写虽不报错（ref 已脱离视图），但属于无意义的动作
  onBeforeUnmount(() => {
    if (timer) clearTimeout(timer)
  })

  return { options, loading, search, searchWith, preload, fetchNow, invalidate }
}