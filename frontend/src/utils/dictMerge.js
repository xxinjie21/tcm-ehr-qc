/**
 * 词典合并 / 去重（本地个人词典侧）。
 *
 * <p><b>口径与后端同源</b>：{@code DictionaryServiceImpl.mergeEntries:541-557} —— 同一个标准词
 * 两条合并时**别名取并集**（保序去重），并剔除「别名等于标准词本身」的自指项。
 * 本地侧若自己另立一套口径，就会出现「本机看着 100 条、提交上去变成 98 条」这种对不上的情况。</p>
 *
 * <p><b>为什么要单独抽出来</b>：拉取（组内 → 本地）与批量导入（文件 → 本地）是两条入口，
 * 但都必须走同一套合并 —— 否则「先拉取再导入」和「先导入再拉取」会得到两份不同的本地词典。</p>
 *
 * <p><b>合并不是版本切换</b>：本地词典是一个**持续累积**的工作副本，拉取与导入都只往里加，
 * 不会把本地已有内容整份换掉。要删词只能在「我的词典」表里手动删。</p>
 */

/** 取标准词作为归一键；空标准词不参与合并 */
const keyOf = (t) => String(t?.standardTerm || '').trim()

/**
 * 别名归一：去空白、去重（保序）、剔除等于标准词本身的项。
 *
 * <p>与后端 {@code mergeEntries} 的清理逻辑逐条对应 —— 别名里留着标准词本身，
 * 归一时会「自己命中自己」，判定路径变成一条无意义的自环。</p>
 *
 * @param {string[]} aliases 原始别名
 * @param {string} standardTerm 该条标准词
 * @returns {string[]} 清洗后的别名
 */
export function normalizeAliases(aliases, standardTerm) {
  const std = String(standardTerm || '').trim()
  const out = []
  for (const raw of aliases || []) {
    const a = String(raw ?? '').trim()
    if (!a || a === std || out.includes(a)) continue
    out.push(a)
  }
  return out
}

/**
 * 把一份词条列表建成两张索引。
 *
 * <p>{@code owner} 是「某个字面归谁」—— 标准词归自己，别名归它的标准词。
 * 标准词优先登记，所以「一个词既是标准词、又是别人的别名」时，算作归它自己。</p>
 */
function buildIndex(list) {
  const byStd = new Map()
  for (const t of list || []) {
    const std = keyOf(t)
    if (!std) continue
    byStd.set(std, { standardTerm: std, aliases: normalizeAliases(t.aliases, std) })
  }
  const owner = new Map()
  for (const [std] of byStd) owner.set(std, std)
  for (const [std, e] of byStd) {
    for (const a of e.aliases) if (!owner.has(a)) owner.set(a, std)
  }
  return { byStd, owner }
}

/**
 * 合并两份词条列表，并把「需要人拍板」的部分单独拎出来。
 *
 * <p>两类需要确认：</p>
 * <ol>
 *   <li><b>同名标准词、别名不一致</b>（{@code sameTermDiff}）：默认按并集，但列出来让人过一眼 ——
 *       可能是本地改过、也可能新来的那版才是权威的，代码不该替人决定。</li>
 *   <li><b>同一字面归属不一致</b>（{@code collisions}）：比如「口干」在本地是标准词、
 *       在新来的那层却是别的标准词的别名。并集解决不了这种冲突（两边都要它），
 *       而且它**会真的改变归一结果**，所以必须人工选一边。</li>
 * </ol>
 *
 * @param {Array} current 本地现有词条
 * @param {Array} incoming 拉取 / 导入进来的词条
 * @returns {{merged: Array, added: number, sameTermDiff: Array, collisions: Array, total: number}}
 *          merged 是「默认口径」的结果（同名词取并集、冲突按本地），供弹窗直接预览
 */
export function mergeTermLists(current, incoming) {
  const A = buildIndex(current)
  const B = buildIndex(incoming)

  // 1. 同名标准词：别名取并集；别名两边不一致的记一条待确认
  const merged = new Map()
  for (const [std, e] of A.byStd) merged.set(std, { ...e, aliases: [...e.aliases] })
  const sameTermDiff = []
  let added = 0
  for (const [std, e] of B.byStd) {
    const old = merged.get(std)
    if (!old) {
      merged.set(std, { ...e, aliases: [...e.aliases] })
      added++
      continue
    }
    const onlyLocal = old.aliases.filter((a) => !e.aliases.includes(a))
    const onlyIncoming = e.aliases.filter((a) => !old.aliases.includes(a))
    if (onlyLocal.length || onlyIncoming.length) {
      sameTermDiff.push({
        standardTerm: std,
        localAliases: [...old.aliases],
        incomingAliases: [...e.aliases],
        onlyLocal,
        onlyIncoming,
        unionAliases: normalizeAliases([...old.aliases, ...e.aliases], std),
        mode: 'union'
      })
    }
    merged.set(std, {
      standardTerm: std,
      aliases: normalizeAliases([...old.aliases, ...e.aliases], std)
    })
  }

  // 2. 归属冲突：同一字面在两边的归属标准词不一致
  const collisions = []
  for (const [word, ownerA] of A.owner) {
    const ownerB = B.owner.get(word)
    if (ownerB === undefined || ownerB === ownerA) continue
    collisions.push({
      word,
      localOwner: ownerA,
      incomingOwner: ownerB,
      // 两边各自怎么看这个词，弹窗里要直说，否则用户看不懂「归属」二字
      localView: ownerA === word ? '标准词' : `「${ownerA}」的别名`,
      incomingView: ownerB === word ? '标准词' : `「${ownerB}」的别名`,
      mode: 'local'
    })
  }
  // 归属冲突的默认是「按本地」：本地是用户手上这份，静默改掉会让归一结果悄悄变。

  return {
    merged: [...merged.values()],
    added,
    sameTermDiff,
    collisions,
    total: merged.size,
    // 弹窗要拿它跟「新增 / 合计」对比着说，所以一并带出来，免得调用方各自再数一遍
    incomingCount: (incoming || []).length
  }
}

/**
 * 把弹窗里的人工选择落到合并结果上。
 *
 * @param {object} result {@link mergeTermLists} 的返回值
 * @param {Object<string, 'union'|'local'|'incoming'>} sameTermModes 标准词 → 处理方式
 * @param {Object<string, 'local'|'incoming'>} collisionModes 冲突字面 → 按哪边
 * @returns {Array} 最终要写入本地的词条
 */
export function resolveMerged(result, sameTermModes = {}, collisionModes = {}) {
  const merged = new Map()
  for (const e of result.merged) merged.set(e.standardTerm, { ...e, aliases: [...e.aliases] })

  // 1. 同名差异：按人工选择重算这一条的别名
  for (const d of result.sameTermDiff) {
    const mode = sameTermModes[d.standardTerm] || 'union'
    const entry = merged.get(d.standardTerm)
    if (!entry) continue
    const aliases = mode === 'local'
      ? d.localAliases
      : mode === 'incoming' ? d.incomingAliases : d.unionAliases
    entry.aliases = normalizeAliases(aliases, d.standardTerm)
  }

  // 2. 归属冲突：按人工选择「按本地 / 按新来」重排
  for (const c of result.collisions) {
    const mode = collisionModes[c.word] || 'local'
    const winOwner = mode === 'local' ? c.localOwner : c.incomingOwner
    const loseOwner = mode === 'local' ? c.incomingOwner : c.localOwner

    // 2a. 从失败方那里摘掉这个词（败方不该再主张它）
    if (loseOwner !== c.word) {
      const lose = merged.get(loseOwner)
      if (lose) lose.aliases = lose.aliases.filter((a) => a !== c.word)
    }
    // 2b. 胜方认为它是标准词 → 保证它以标准词存在
    if (winOwner === c.word) {
      if (!merged.has(c.word)) merged.set(c.word, { standardTerm: c.word, aliases: [] })
      continue
    }
    // 2c. 胜方认为它是别名 → 挂到胜方名下，并把它自己那份标准词条目撤掉
    const win = merged.get(winOwner)
    if (win && !win.aliases.includes(c.word)) win.aliases.push(c.word)
    const dropped = merged.get(c.word)
    if (dropped) {
      // 撤掉的是「标准词身份」，它底下的别名不能一起消失 —— 转挂到胜方名下
      if (win) {
        for (const a of dropped.aliases) {
          if (a !== c.word && a !== winOwner && !win.aliases.includes(a)) win.aliases.push(a)
        }
      }
      merged.delete(c.word)
    }
  }

  // 3. 收尾：统一清洗别名，丢掉标准词为空的条目
  const out = []
  for (const e of merged.values()) {
    const std = String(e.standardTerm || '').trim()
    if (!std) continue
    out.push({ standardTerm: std, aliases: normalizeAliases(e.aliases, std) })
  }
  return out
}
