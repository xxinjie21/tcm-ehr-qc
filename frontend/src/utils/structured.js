/**
 * 结构化数据（structuredData）的展示口径 —— 唯一副本。
 *
 * <p>解析页要做「归一汇总统计」、实体卡片要按分区渲染，两处必须用同一份分区与同一套措辞；
 * 各写一份必然漂移（改了这边忘了那边），所以统一放这里。</p>
 *
 * <p><b>dict 的口径与后端 {@code EntityNormalizer.dictionaryType} 对齐</b>：
 * 疾病 / 症状 / 证候 / 方剂 四类走词典，中药走 herb 词典（后端在 herbs 分支单独处理），
 * 舌象 / 脉象 / 病因 / 治法 四类没有独立词典、只保留原文。</p>
 */

/** 9 类实体分区（顺序即页面展示顺序） */
export const ENTITY_SECTIONS = [
  { key: 'diseases', label: '疾病', dict: true },
  { key: 'symptoms', label: '症状', dict: true },
  { key: 'tongueList', label: '舌象', dict: false },
  { key: 'pulseList', label: '脉象', dict: false },
  { key: 'patternList', label: '证候', dict: true },
  { key: 'causeList', label: '病因', dict: false },
  { key: 'treatmentList', label: '治法', dict: false },
  { key: 'formulaList', label: '方剂', dict: true },
  { key: 'herbs', label: '中药', dict: true }
]

/**
 * 归一命中层级 → 实体标签上的短字样。
 *
 * <p><b>刻意不带「命中」二字</b>（原为「精确命中」）。实测（1366×768、右栏约 488px 宽）：
 * 写「精确命中·ES」时实体卡高 938px，去掉「命中」后掉到地板值 701px —— 每多两个字就多一次
 * 折行，一次折行约值几十像素。图例已写明「描边标签＝归一结果，命中分三档」，所以不损失信息。
 * 同时对比过「隐藏置信 xx%」也能到 701，但置信度是用户明确要看的，宁舍「命中」不舍置信。</p>
 */
export const LEVEL_SHORT = { 1: '精确', 2: '包含', 3: '模糊' }

/** 归一命中层级 → 悬停里的完整说法 */
export const LEVEL_FULL = { 1: '精确匹配', 2: '包含匹配', 3: '模糊匹配' }

/** 归一途径 → 人话：ES＝走 ES 索引召回，MEMORY＝回退内存词典。用于悬停与图例 */
export const VIA_TEXT = { ES: 'ES 索引', MEMORY: '内存词典' }

/**
 * 归一途径 → 实体标签上的短写法。**标签上一律用这个，不要用 {@link VIA_TEXT}**。
 *
 * <p>实测（1366×768）：标签写「精确命中·ES 索引」时右栏实体卡高 1360px，改用
 * 「精确命中·ES」后显著回落 —— 右栏只有约 488px 宽，每多 3 个字就多一次折行，
 * 而一次折行会让整张卡多出几十像素。图例里已经写明「后缀是命中走的路径（ES / 内存）」，
 * 所以短写法不损失信息。</p>
 */
export const VIA_SHORT = { ES: 'ES', MEMORY: '内存' }

/** 实体名：中药取 name，其余取 content */
export const entityName = (sec, it) => (sec.key === 'herbs' ? it.name : it.content)

/**
 * 置信度显示：统一成百分比两位（0.9254 → 92.54%）。
 *
 * <p>后端 confidence 是 0~1 的原始小数且不保证位数（同一批数据里出现过 0.772 / 0.9254 / 0.9996），
 * 裸数字直出既没有标签说明含义、位数也不齐，看起来不像同一类值。</p>
 *
 * <p>只有 {@code source=model}（模型抽取）的要素有这个值；{@code source=rule}（规则命中）
 * 的不下发 confidence，因此不显示 —— 属预期，不是缺数据。</p>
 */
export const pct = (v) => {
  // 显式挡掉 null / undefined / 空串：Number(null) 和 Number('') 都是 0，
  // 不挡的话会渲染成「置信 0.00%」，比不显示更误导
  if (v === null || v === undefined || v === '') return ''
  const n = Number(v)
  return Number.isFinite(n) ? `${(n * 100).toFixed(2)}%` : ''
}

/**
 * 归一汇总：把「这次归一到底做了什么」算成可显示的数字。
 *
 * @param vo NlpExtractVO（或 structuredData 对象）
 * @returns 统计对象；入参为空时返回 null
 */
export function summarizeNorm(vo) {
  if (!vo) return null
  const s = {
    total: 0,      // 参与统计的实体总数（有词典的 5 类）
    hit: 0,        // 命中词典
    exact: 0,      // 1 精确
    contain: 0,    // 2 包含
    fuzzy: 0,      // 3 模糊
    miss: 0,       // 未命中词典
    es: 0,         // 走 ES 索引命中
    memory: 0,     // 回退内存词典命中
    noDict: 0      // 无独立词典、不参与归一的实体数（舌/脉/病因/治法）
  }
  for (const sec of ENTITY_SECTIONS) {
    const arr = Array.isArray(vo[sec.key]) ? vo[sec.key] : []
    if (!sec.dict) {
      s.noDict += arr.length
      continue
    }
    for (const it of arr) {
      if (!it) continue
      s.total += 1
      if (it.normLevel === 1) { s.hit += 1; s.exact += 1 } else if (it.normLevel === 2) { s.hit += 1; s.contain += 1 } else if (it.normLevel === 3) { s.hit += 1; s.fuzzy += 1 } else { s.miss += 1 }
      if (it.normVia === 'ES') s.es += 1
      else if (it.normVia === 'MEMORY') s.memory += 1
    }
  }
  return s
}
