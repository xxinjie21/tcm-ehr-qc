/**
 * 术语录入相关的纯函数。
 *
 * 抽成公共模块的原因：词典页「新增标准词」与导入页「粘贴文本」都要把用户手输的
 * 别名串拆成数组。两处规则必须完全一致，否则同一个人从不同入口录入同一个词，
 * 会得到不同的结果 —— 这种漂移在界面上看不出来，只会让词表慢慢变得不可预期。
 */

/**
 * 把用户输入的别名串拆成数组。
 *
 * 分隔符覆盖顿号、中英文逗号、分号、空白与换行 —— 用户从各处复制来的格式不统一，
 * 与其要求他们改格式，不如一次都认。
 *
 * 同时做三件清理，挡住「写进去就是坏数据」的情况：
 *   1. 去掉空项（连续分隔符会产生空串）；
 *   2. 去重；
 *   3. 剔除与标准词同值的项 —— 别名等于标准词会在归一的一级精确匹配里「自己命中自己」，
 *      后端 mergeEntries 也会把它剔掉（DictionaryServiceImpl 的 #5 修复），这里先挡一道。
 *
 * @param {string} raw 原始别名串，如「神昏、神智不清」
 * @param {string} standard 对应的标准词，用于剔除同值项
 * @returns {string[]} 清理后的别名数组
 */
export function splitAliases(raw, standard) {
  const std = String(standard || '').trim()
  const seen = new Set()
  const out = []
  for (const part of String(raw || '').split(/[、,，;；\s\n]+/)) {
    const v = part.trim()
    if (!v || v === std || seen.has(v)) continue
    seen.add(v)
    out.push(v)
  }
  return out
}
